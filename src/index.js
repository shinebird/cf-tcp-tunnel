import { DurableObject } from 'cloudflare:workers';

// Protocol: JSON {type:'open'|'ready'|'error'|'close', id, port?,message?}
// Binary: uint32 big-endian session ID followed by arbitrary TCP bytes.
const MAX_FRAME = 64 * 1024 + 4;
const MAX_SESSIONS = 128;

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (request.method !== 'GET' || request.headers.get('Upgrade')?.toLowerCase() !== 'websocket')
      return new Response('WebSocket required', { status: 426 });
    if (!env.AGENT_TOKEN || !env.CLIENT_TOKEN)
      return new Response('Missing secrets', { status: 503 });
    const agent = url.pathname === '/agent';
    if (!agent && url.pathname !== '/connect') return new Response('Not found', { status: 404 });
    const expected = agent ? env.AGENT_TOKEN : env.CLIENT_TOKEN;
    if (request.headers.get('Authorization') !== `Bearer ${expected}`)
      return new Response('Unauthorized', { status: 401 });
    if (!agent) {
      const port = Number(url.searchParams.get('port'));
      if (!Number.isInteger(port) || port < 1 || port > 65535)
        return new Response('Invalid port', { status: 400 });
      const allowed = (env.ALLOWED_PORTS || '').split(',').map(x => Number(x.trim()));
      if (!allowed.includes(port)) return new Response('Port forbidden', { status: 403 });
    }
    return env.HUB.getByName('main').fetch(request);
  }
};

export class TunnelHub extends DurableObject {
  constructor(ctx, env) {
    super(ctx, env);
    this.agent = null;
    this.clients = new Map();
    this.nextId = 1;
    // The constructor runs again after hibernation. WebSocket attachments,
    // unlike the in-memory Map, survive it.
    for (const ws of ctx.getWebSockets()) {
      const meta = ws.deserializeAttachment();
      if (meta?.role === 'agent') {
        // An agent replacement can leave a closing socket behind.
        if (!this.agent || meta.generation > (this.agent.deserializeAttachment()?.generation ?? -1))
          this.agent = ws;
      } else if (meta?.role === 'client' && Number.isInteger(meta.id) && meta.id > 0) {
        this.clients.set(meta.id, { ws, port: meta.port, ready: meta.ready === true });
        this.nextId = Math.max(this.nextId, meta.id + 1);
      }
    }
    if (this.nextId > 0xffffffff) this.nextId = 1;
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
    // No server.accept() or addEventListener(): these prevent hibernation.
    if (isAgent) {
      const oldAgent = this.agent;
      // Shut down sessions bound to the old agent before replacing it.
      for (const [id, entry] of this.clients) {
        this.clients.delete(id);
        this.closeSocket(entry.ws, 1012, 'Agent replaced');
      }
      if (oldAgent) this.closeSocket(oldAgent, 1012, 'Agent replaced');
      this.ctx.acceptWebSocket(server);
      server.serializeAttachment({ role: 'agent', generation: Date.now() });
      this.agent = server;
    } else {
      const port = Number(url.searchParams.get('port'));
      const id = this.allocateId();
      this.ctx.acceptWebSocket(server);
      server.serializeAttachment({ role: 'client', id, port, ready: false });
      this.clients.set(id, { ws: server, port, ready: false });
      this.sendAgent({ type: 'open', id, port });
    }
    return new Response(null, { status: 101, webSocket: client });
  }

  isOnline(ws) { return ws?.readyState === WebSocket.OPEN; }
  closeSocket(ws, code, reason) {
    if (ws) { try { ws.close(code, reason); } catch {} }
  }
  allocateId() {
    // Prevent stale messages from being routed to an earlier connection.
    for (let n = 0; n <= MAX_SESSIONS + 1; n++) {
      const id = this.nextId++ >>> 0;
      if (this.nextId > 0xffffffff) this.nextId = 1;
      if (id && !this.clients.has(id)) return id;
    }
    throw new Error('Session identifiers exhausted');
  }
  sendAgent(msg) {
    if (this.isOnline(this.agent)) {
      try { this.agent.send(JSON.stringify(msg)); } catch {}
    }
  }
  sendAgentBinary(id, payload) {
    if (!this.isOnline(this.agent)) return;
    const frame = new Uint8Array(4 + payload.length);
    new DataView(frame.buffer).setUint32(0, id, false);
    frame.set(payload, 4);
    try { this.agent.send(frame); } catch {}
  }

  async webSocketMessage(ws, data) {
    const meta = ws.deserializeAttachment();
    if (meta?.role === 'agent') {
      if (this.agent !== ws) return;
      this.agentMessage(data);
    } else if (meta?.role === 'client') {
      const entry = this.clients.get(meta.id);
      if (entry?.ws !== ws || !entry.ready || typeof data === 'string') return;
      if (!(data instanceof ArrayBuffer) || !data.byteLength || data.byteLength > MAX_FRAME - 4) {
        this.closeSocket(ws, 1009, 'Invalid frame');
        return;
      }
      this.sendAgentBinary(meta.id, new Uint8Array(data));
    }
  }

  agentMessage(data) {
    if (typeof data === 'string') {
      let msg;
      try { msg = JSON.parse(data); } catch { return; }
      const id = msg.id;
      if (!Number.isInteger(id)) return;
      const entry = this.clients.get(id);
      if (!entry) return;
      if (msg.type === 'ready') {
        entry.ready = true;
        entry.ws.serializeAttachment({ role: 'client', id, port: entry.port, ready: true });
        try { entry.ws.send(JSON.stringify({ type: 'ready' })); } catch {}
      } else if (msg.type === 'error' || msg.type === 'close') {
        if (msg.type === 'error') {
          try { entry.ws.send(JSON.stringify({ type: 'error', message: String(msg.message || 'Connection failed').slice(0, 200) })); } catch {}
        }
        this.clients.delete(id);
        this.closeSocket(entry.ws, 1011, 'Remote closed');
      }
      return;
    }
    if (!(data instanceof ArrayBuffer) || data.byteLength < 5 || data.byteLength > MAX_FRAME) return;
    const id = new DataView(data).getUint32(0, false);
    const entry = this.clients.get(id);
    if (entry?.ready && this.isOnline(entry.ws)) {
      try { entry.ws.send(data.slice(4)); } catch {}
    }
  }

  // Hibernation API dispatches close/error events to class handlers, not listeners.
  async webSocketClose(ws, code, reason, wasClean) {
    this.cleanup(ws);
    this.closeSocket(ws, code === 1005 || code === 1006 ? 1000 : code, String(reason || '').slice(0, 120));
  }
  async webSocketError(ws, error) {
    this.cleanup(ws);
    this.closeSocket(ws, 1011, 'WebSocket error');
  }
  cleanup(ws) {
    const meta = ws.deserializeAttachment();
    if (meta?.role === 'agent') {
      if (this.agent !== ws) return;
      this.agent = null;
      for (const [id, entry] of this.clients) {
        this.clients.delete(id);
        this.closeSocket(entry.ws, 1011, 'Agent disconnected');
      }
    } else if (meta?.role === 'client') {
      if (this.clients.get(meta.id)?.ws !== ws) return;
      this.clients.delete(meta.id);
      this.sendAgent({ type: 'close', id: meta.id });
    }
  }
}
