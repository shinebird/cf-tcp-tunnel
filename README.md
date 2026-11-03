# Cloudflare TCP Tunnel — C++20 / Boost.Beast / Asio coroutine version

This replaces **both** `tunnel.py agent` and `tunnel.py client`. It speaks the **same** WebSocket protocol as the previously deployed Cloudflare Hibernation Worker; no Worker changes are necessary.

## Requirements

- C++20 compiler: MSVC 2022 (VS 17.x), clang-cl, or recent GCC/Clang.
- Boost >= 1.83 with **Boost.JSON binary library** installed (`Boost::json`).
- OpenSSL development libraries (TLS for `wss://` connections).
- CMake >= 3.21.

`boost::asio::awaitable<void>` is used for coroutine scheduling in this implementation. It uses Boost.Beast for WebSocket and Boost.JSON for control messages. **Boost.Cobalt is not used** in this version. Cobalt could replace the coroutine scheduling layer, but mixing it with Asio awaitable increases complexity and does not improve Worker protocol compatibility. One `io_context` thread is used for the initial version, with no strand required.

## Windows / MSVC build

Example using a vcpkg installation with Boost and OpenSSL:

```powershell
vcpkg install boost-json boost-beast boost-asio openssl:x64-windows
cmake -S . -B build -A x64 -DCMAKE_TOOLCHAIN_FILE="C:/vcpkg/scripts/buildsystems/vcpkg.cmake"
cmake --build build --config Release
```

With an existing Boost/OpenSSL installation, instead configure `BOOST_ROOT`, `OPENSSL_ROOT_DIR`, or `CMAKE_PREFIX_PATH` as needed.

Output: `build/Release/cf-tunnel.exe` (Visual Studio multi-config generator).

OpenSSL must have a usable trust store. The client **verifies the server certificate and host name**. If your custom OpenSSL lacks system CA certificates, configure `SSL_CERT_FILE` to an appropriate CA bundle. For temporary troubleshooting only, `--insecure-skip-verify` disables both CA-chain and hostname verification; this removes protection against active man-in-the-middle attacks and exposes tunnel tokens to impersonated endpoints.

## Override the physical Worker endpoint (`--connect-host`)

`--connect-host` sets **only** the TCP destination used for DNS resolution / connection. The **logical** Worker hostname from `--url` is unchanged for TLS SNI, server-certificate hostname verification, and HTTP/WebSocket `Host` routing. The path and port also come from `--url`. This is similar to curl's `--connect-to` / `--resolve` behavior and does **not** change the Cloudflare Worker route.

Use it on **Agent and/or Client** independently (without it the original DNS name is used):

```powershell
# Connect to a selected Cloudflare IPv4 address
.\cf-tunnel.exe agent `
  --url wss://tcp-tunnel.ayachi-nene.uk `
  --connect-host 203.0.113.10 `
  --token YOUR_AGENT_TOKEN `
  --ports 3389 `
  --target-host 127.0.0.1

# Connect via a hostname/CNAME that resolves to the desired edge address
.\cf-tunnel.exe client `
  --url wss://tcp-tunnel.ayachi-nene.uk `
  --connect-host edge.example.net `
  --token YOUR_CLIENT_TOKEN `
  --listen-port 13389 `
  --remote-port 3389
```

`203.0.113.10` is a **documentation-only** placeholder, not a working Cloudflare IP. Supply a working address yourself. IPv6 literals are supported without `[]`, e.g. `--connect-host 2001:db8::1` (also placeholder). Do **not** include a port or URL scheme in `--connect-host`. For a non-default port, specify it in `--url`, such as `wss://hostname:8443` (only if the edge actually supports it).

A CNAME target still uses ordinary DNS resolution, so it **does not guarantee a fixed IP**. Not every IP (including arbitrary Cloudflare address ranges) serves your account's Worker route; the IP must be reachable on the appropriate Cloudflare service. If the selected IP is unreachable, requests will fail even though SNI and Host remain correct. Keep TLS verification **enabled** when possible; `--insecure-skip-verify` is unrelated to pinning the TCP endpoint and adds significant security risk.

## Agent: run on the machine with access to the internal service

```powershell
.\build\Release\cf-tunnel.exe agent `
  --url wss://tcp-tunnel.example.com `
  --token YOUR_AGENT_TOKEN `
  --ports 3389 `
  --target-host 127.0.0.1 `
  --insecure-skip-verify
```

## Client: run on the remote machine

```powershell
.\build\Release\cf-tunnel.exe client `
  --url wss://tcp-tunnel.example.com `
  --token YOUR_CLIENT_TOKEN `
  --listen-host 127.0.0.1 `
  --listen-port 13389 `
  --remote-port 3389 `
  --insecure-skip-verify
```

Then run Windows Remote Desktop: `mstsc /v:127.0.0.1:13389`.

For multiple ports, keep one Agent instance (`--ports 22,3389,8080`); run a separate Client process per local port mapping.

## Protocol (matches the existing Worker)

- `GET /agent` with `Authorization: Bearer <AGENT_TOKEN>` establishes the persistent Agent WSS connection.
- `GET /connect?port=<remote_port>` with Client token establishes an individual WSS stream.
- Agent receives text `{"type":"open","id":N,"port":3389}`, sends `{"type":"ready","id":N}` after TCP connection; it can receive/send `close` / `error` control messages.
- Agent binary WS messages: 4-byte **big-endian** session ID followed by 1..65536 bytes of raw TCP payload.
- Client receives `{"type":"ready"}` then sends/receives raw binary WebSocket messages without the session-ID prefix.
- The Worker multiplexes all sessions over one Agent WebSocket.

## Current limitations and notes

1. **TCP only**; not UDP, QUIC, or transparent direct public TCP access.
2. **Half-close is not preserved**: EOF in either direction closes the pair, consistent with the Python prototype.
3. Each outbound Agent queue and each TCP-target write queue is bounded to 4 MiB. On overflow, the connection/session closes instead of allocating memory without limit. This is **not** true end-to-end flow control: heavy sustained throughput may still overwhelm Worker buffers. For production, add a window/ACK-based protocol on both ends **and change the Worker**.
4. Agent reconnects automatically with exponential backoff; active TCP sessions cannot be resumed after disconnect.
5. TLS verifies hostname and CA chain by default. `--insecure-skip-verify` is opt-in, insecure, and should be used only while diagnosing certificate configuration. Tokens must be treated as secrets, not checked into Git.
6. Only a single event-loop thread is used; don't run multiple Agent instances against the same Worker, since a new one replaces the existing Agent.
7. The current C++ code has been **syntax-checked with GCC 14 / Boost 1.83**; a Windows/MSVC build and full live Cloudflare/RDP end-to-end test have **not** been run in this environment. Verify the built executable locally before relying on it remotely.
8. To force the TCP connection destination, add `--connect-host <IP-or-DNS-name>`. This leaves TLS SNI and the HTTP Host header unchanged.
9. If using AdGuard on the Agent computer, exclude **`cf-tunnel.exe`** from AdGuard's routing/filtering instead of the old `python.exe`, or the RDP-triggered protection restart may disconnect the new tunnel as well.

## Files

- `src/main.cpp` — both Agent and Client using asynchronous C++20 coroutines.
- `CMakeLists.txt` — build definition.
