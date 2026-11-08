import { DurableObject } from 'cloudflare:workers';

// One Durable Object per agent ID. All sockets in that DO share ONLY that agent.
// Agent    : GET /agent?agent=<id> ; Authorization: Bearer <agent token>
// Client   : GET /connect?agent=<id>&port=<n> ; Authorization: Bearer <client token>
// Missing agent= defaults to 'main', preserving legacy clients.
// Agent text: {type:'open'|'ready'|'error'|'close', id, port?, message?}
// Agent binary: big-endian uint32 session ID + TCP bytes.
// Client binary: raw TCP bytes (max 64 KiB per WebSocket message).

const MAX_PAYLOAD = 64 * 1024;
const MAX_SESSIONS = 128;
const VALID_AGENT_ID = /^[a-z0-9][a-z0-9_-]{0,63}$/;

function parseAgentId(url) {
  const id = url.searchParams.get('agent') ?? 'main';
  return VALID_AGENT_ID.test(id) ? id : null;
}

function safeTokenMap(raw) {
  if (!raw) return null;
  try {
    const map = JSON.parse(raw);
    if (!map || typeof map !== 'object' || Array.isArray(map)) return null;
    return map;
  } catch {
    return null;
  }
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const agentRequest = url.pathname === '/agent';
    if (!agentRequest && url.pathname !== '/connect')
      return new Response('Not found', { status: 404 });
    if (request.method !== 'GET' || request.headers.get('Upgrade')?.toLowerCase() !== 'websocket')
      return new Response('WebSocket required', { status: 426 });

    const agentId = parseAgentId(url);
    if (!agentId) return new Response('Invalid agent ID (lowercase a-z, 0-9, _, -; max 64 chars)', { status: 400 });

    // If a token-map secret is configured, it takes precedence over the shared token.
    // This prevents one leaked agent credential from impersonating a different agent.
    const mapRaw = agentRequest ? env.AGENT_TOKENS : env.CLIENT_TOKENS;
    let expected;
    if (mapRaw !== undefined && mapRaw !== '') {
      const tokenMap = safeTokenMap(mapRaw);
      if (!tokenMap) return new Response('Invalid token map configuration', { status: 503 });
      expected = Object.hasOwn(tokenMap, agentId) ? tokenMap[agentId] : undefined;
    } else {
      expected = agentRequest ? env.AGENT_TOKEN : env.CLIENT_TOKEN;
    }
    if (typeof expected !== 'string' || !expected)
      return new Response('Agent not configured', { status: 403 });
    if (request.headers.get('Authorization') !== `Bearer ${expected}`)
      return new Response('Unauthorized', { status: 401 });

    if (!agentRequest) {
      const portText = url.searchParams.get('port');
      const port = Number(portText);
      if (!portText || !/^\d+$/.test(portText) || !Number.isInteger(port) || port < 1 || port > 65535)
        return new Response('Invalid port', { status: 400 });
      const allowed = String(env.ALLOWED_PORTS ?? '').split(',').map(x => Number(x.trim()));
      if (!allowed.includes(port)) return new Response('Port forbidden', { status: 403 });
    }
    // Keep the existing 'main' DO name to maintain backward compatibility.
    // Other IDs get isolated Durable Objects and isolated WebSocket connections.
    const name = agentId === 'main' ? 'main' : `agent:${agentId}`;
    return env.HUB.getByName(name).fetch(request);
  }
};

export class TunnelHub extends DurableObject {
  constructor(ctx, env) {
    super(ctx, env);
    this.agent = null;
    this.clients = new Map();
    this.nextId = 1;
    this.lastGeneration = 0;
    // Reconstruct all live sockets after Durable Object hibernation.
    for (const ws of ctx.getWebSockets()) {
      if (ws.readyState !== WebSocket.OPEN) continue;
      const meta = ws.deserializeAttachment();
      if (meta?.role === 'agent') {
        this.lastGeneration = Math.max(this.lastGeneration, Number(meta.generation) || 0);
        const oldGeneration = this.agent?.deserializeAttachment()?.generation ?? -1;
        if (!this.agent || meta.generation > oldGeneration) this.agent = ws;
      } else if (meta?.role === 'client' && Number.isInteger(meta.id) && meta.id > 0) {
        this.clients.set(meta.id, { ws, port: meta.port, ready: meta.ready === true });
        this.nextId = Math.max(this.nextId, meta.id + 1);
      }
    }
    if (this.nextId > 0xffffffff) this.nextId = 1;
  }

  isOnline(ws) { return !!ws && ws.readyState === WebSocket.OPEN; }
  closeSocket(ws, code, reason) {
    if (!ws) return;
    try { ws.close(code, reason); } catch (error) { console.warn('closeSocket:', String(error)); }
  }
  allocateId() {
    for (let i = 0; i <= MAX_SESSIONS + 1; ++i) {
      const id = this.nextId;
      this.nextId = id >= 0xffffffff ? 1 : id + 1;
      if (id > 0 && !this.clients.has(id)) return id;
    }
    throw new Error('No available session ID');
  }
  sendAgent(message) {
    if (!this.isOnline(this.agent)) return false;
    try { this.agent.send(JSON.stringify(message)); return true; }
    catch (err) {
      console.error('Agent send failed:', String(err));
      this.closeSocket(this.agent, 1011, 'Agent send failed');
      return false;
    }
  }
  sendAgentBinary(id, data) {
    if (!this.isOnline(this.agent)) return false;
    const bytes = new Uint8Array(4 + data.byteLength);
    new DataView(bytes.buffer).setUint32(0, id, false);
    bytes.set(new Uint8Array(data), 4);
    try { this.agent.send(bytes); return true; }
    catch (err) {
      console.error('Agent binary send failed:', String(err));
      this.closeSocket(this.agent, 1011, 'Agent send failed');
      return false;
    }
  }

  async fetch(request) {
    const url = new URL(request.url);
    const isAgent = url.pathname === '/agent';
    if (!isAgent && !this.isOnline(this.agent))
      return new Response('Agent offline', { status: 503 });
    if (!isAgent && this.clients.size >= MAX_SESSIONS)
      return new Response('Too many sessions', { status: 429 });

    const pair = new WebSocketPair();
    const client = pair[0], server = pair[1];
    if (isAgent) {
      const old = this.agent;
      // Only replace the Agent in THIS named Durable Object.
      for (const [id, item] of this.clients) {
        this.clients.delete(id);
        this.closeSocket(item.ws, 1012, 'Agent replaced');
      }
      this.ctx.acceptWebSocket(server);
      // Never reuse a generation on rapid reconnects, even within the same millisecond.
      const generation = this.lastGeneration = Math.max(Date.now(), this.lastGeneration + 1);
      server.serializeAttachment({role:'agent', generation});
      this.agent = server;
      if (old && old !== server) this.closeSocket(old, 1012, 'Agent replaced');
      // The TCP client must not consider the Agent usable until registration is acknowledged.
      // The attachment is already committed, so hibernation can reconstruct this connection.
      try {
        server.send(JSON.stringify({type:'registered', generation:server.deserializeAttachment().generation}));
      } catch (error) {
        console.error('Agent registration acknowledgement failed:', String(error));
        this.agent = null;
        this.closeSocket(server, 1011, 'Agent registration failed');
      }
      console.info('Agent registration processed', {generation, activeSessions: this.clients.size, online:this.isOnline(this.agent)});
    } else {
      const port = Number(url.searchParams.get('port'));
      const id = this.allocateId();
      this.ctx.acceptWebSocket(server);
      server.serializeAttachment({role:'client', id, port, ready:false});
      this.clients.set(id, {ws:server, port, ready:false});
      console.info('Client open', {id, port, agentOnline:this.isOnline(this.agent)});
      if (!this.sendAgent({type:'open', id, port})) {
        this.clients.delete(id);
        this.closeSocket(server, 1011, 'Agent send failed');
      }
    }
    return new Response(null, {status:101, webSocket:client});
  }

  async webSocketMessage(ws, data) {
    const meta = ws.deserializeAttachment();
    if (meta?.role === 'agent') {
      if (ws === this.agent) this.agentMessage(data);
      else console.warn('Ignoring message from stale Agent socket');
    } else if (meta?.role === 'client') {
      const entry = this.clients.get(meta.id);
      if (entry?.ws !== ws || !entry.ready || typeof data === 'string') return;
      if (!(data instanceof ArrayBuffer) || data.byteLength === 0 || data.byteLength > MAX_PAYLOAD) {
        this.closeSocket(ws, 1009, 'Invalid client frame');
        return;
      }
      if (!this.sendAgentBinary(meta.id, data)) this.closeSocket(ws, 1011, 'Agent unavailable');
    }
  }

  agentMessage(data) {
    if (typeof data === 'string') {
      let msg;
      try { msg = JSON.parse(data); } catch { return; }
      // Application-level heartbeat: only the current registered Agent reaches here.
      if (msg?.type === 'ping') {
        const nonce = msg.nonce;
        if (Number.isSafeInteger(nonce) && nonce >= 0) {
          if (!this.sendAgent({type:'pong', nonce}))
            console.warn('Failed to send Agent heartbeat acknowledgement');
        }
        return;
      }
      if (!Number.isInteger(msg?.id) || msg.id <= 0) return;
      const entry = this.clients.get(msg.id);
      if (!entry) return;
      if (msg.type === 'ready') {
        entry.ready = true;
        entry.ws.serializeAttachment({role:'client', id:msg.id, port:entry.port, ready:true});
        try { entry.ws.send(JSON.stringify({type:'ready'})); }
        catch (err) { console.warn('Client ready send failed:', String(err)); this.closeSocket(entry.ws, 1011, 'Client send failed'); }
      } else if (msg.type === 'error' || msg.type === 'close') {
        if (msg.type === 'error') {
          try { entry.ws.send(JSON.stringify({type:'error', message:String(msg.message ?? 'Connection failed').slice(0,200)})); } catch {}
        }
        this.clients.delete(msg.id);
        this.closeSocket(entry.ws, 1011, 'Remote closed');
      }
      return;
    }
    if (!(data instanceof ArrayBuffer) || data.byteLength < 5 || data.byteLength > MAX_PAYLOAD + 4) return;
    const id = new DataView(data).getUint32(0, false);
    const entry = this.clients.get(id);
    if (entry?.ready && this.isOnline(entry.ws)) {
      try { entry.ws.send(data.slice(4)); }
      catch (err) { console.warn('Client binary send failed:', String(err)); this.closeSocket(entry.ws, 1011, 'Client send failed'); }
    }
  }

  async webSocketClose(ws, code, reason, wasClean) {
    const meta = ws.deserializeAttachment();
    console.info('WebSocket closed', {role:meta?.role, id:meta?.id, code, wasClean, currentAgent:ws===this.agent, reason:String(reason).slice(0,120)});
    this.cleanup(ws);
    // Older compatibility dates require explicit close acknowledgement.
    this.closeSocket(ws, code === 1005 || code === 1006 ? 1000 : code, String(reason || '').slice(0,120));
  }
  async webSocketError(ws, err) {
    const meta = ws.deserializeAttachment();
    console.error('WebSocket error', {role:meta?.role, id:meta?.id, error:String(err)});
    this.cleanup(ws);
    this.closeSocket(ws, 1011, 'WebSocket error');
  }
  cleanup(ws) {
    const meta = ws.deserializeAttachment();
    if (meta?.role === 'agent') {
      if (this.agent !== ws) return;  // An older replaced Agent cannot kill a new one.
      this.agent = null;
      for (const [id, entry] of this.clients) {
        this.clients.delete(id);
        this.closeSocket(entry.ws, 1011, 'Agent disconnected');
      }
    } else if (meta?.role === 'client') {
      if (this.clients.get(meta.id)?.ws !== ws) return;
      this.clients.delete(meta.id);
      this.sendAgent({type:'close', id:meta.id});
    }
  }
}
