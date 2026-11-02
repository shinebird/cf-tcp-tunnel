# Cloudflare Workers TCP Reverse Tunnel (PoC)

Proof-of-concept for **arbitrary TCP byte streams**, not UDP. Requires Python 3.10+ and Node.js/Wrangler on deployment machine. Supports one online agent, multiple allowed TCP ports and multiple concurrent sessions.

## Deploy

```powershell
cd cf-tcp-tunnel
npx wrangler login
npx wrangler secret put AGENT_TOKEN
npx wrangler secret put CLIENT_TOKEN
```

Set the non-secret allowlist in `wrangler.jsonc`, alongside `main`:

```json
"vars": { "ALLOWED_PORTS": "22,3389,8080" }
```

Then deploy:

```powershell
npx wrangler deploy
```

**Important:** On first deployment, add the variables to the Wrangler configuration before deploying. Secrets set via Wrangler are stored server-side. Create independent strong random strings with `python -c "import secrets; print(secrets.token_urlsafe(32))"`.

## Install client

```powershell
py -m pip install -r requirements.txt
```

## Example: RDP 3389

On LAN Windows host:

```powershell
py tunnel.py agent --url wss://YOUR-WORKER.workers.dev --token YOUR_AGENT_TOKEN --ports 3389
```

On remote Windows host:

```powershell
py tunnel.py client --url wss://YOUR-WORKER.workers.dev --token YOUR_CLIENT_TOKEN --listen-port 13389 --remote-port 3389
```

Connect RDP to `127.0.0.1:13389`.

To reach another LAN computer rather than the agent's own localhost: use `--target-host 192.168.1.123` on the agent. This PoC intentionally restricts one target host per agent and uses port allowlists at both Worker and agent.

## Notes / limitations

- Protocols carried: TCP streams only. Neither raw public TCP ingress nor UDP is exposed.
- No half-close support: closing one direction ends the whole session. Protocols requiring TCP half-close need an extended version.
- Plain WebSocket messages are used to relay binary payload; backpressure is not end-to-end. High-throughput transfers require flow control, bounded send buffers, quota controls and load testing.
- In-memory session table; worker/Durable Object restart disconnects sessions. Agent reconnects automatically. Remote client TCP sessions must reconnect.
- Not a public SOCKS5/HTTP proxy. Do not publish tokens, expose a local listener on `0.0.0.0`, or allow untrusted internal ports.
- Authentication is bearer-token based. For production, add per-user credentials, replay/abuse limits, telemetry, token rotation and network-level access controls.
- If direct native TCP ingress or UDP is essential, use a VPS/FRP, VPN, Cloudflare Spectrum (supported plans), or Cloudflare Tunnel/WARP workflows instead.

## WebSocket Hibernation edition (2026-10-29)

`TunnelHub` now uses `this.ctx.acceptWebSocket(server)` and the Durable Object
`webSocketMessage`, `webSocketClose`, `webSocketError` handlers. Each socket
persists its role and session metadata with `serializeAttachment()`. On a
hibernation wake, the constructor rebuilds the agent and client tables using
`ctx.getWebSockets()` and `deserializeAttachment()`. The Python wire protocol
and command lines are unchanged.

**Updating an existing deployment:** replace `src/index.js` in your GitHub
repository, then redeploy the *same Worker and DO class* (`TunnelHub`). Keep
`wrangler.jsonc` and the existing `HUB` binding. Do not create a second DO
class or remove your Worker secrets. Existing connections should be allowed to
reconnect when deploying; do not assume live sessions survive a deployment.

Hibernation allows an **idle** Durable Object to suspend without closing
WebSockets. Continuous traffic, keep-alive frames, alarms or frequent events
will wake it and can still incur charges. This does not add backpressure,
TCP half-close, UDP support, or guaranteed reconnection of individual sessions.
For production, implement bounded queues/flow control and reconnect handling.
