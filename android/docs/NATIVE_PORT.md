# 1.1.0 Native migration audit

## Baseline
`cf-tcp-tunnel-multi-agent-reconnect-fix.zip` → `cpp/src/main.cpp`.
SHA-256: `efa0e3055cc9766280a141501df558e00b729396aab81f635bd45db99131a512`.

`native/upstream/pc-main.cpp` is byte-identical to this supplied source.
`pc_core.inc` is derived by replacing `log`, removing standalone main, adding counters and weak lifecycle registries. See `native/pc-core-adaptation.patch`. `tests/check_native_port.py` reverses the hooks and compares all original pre-main text, not merely protocol examples.

## Why this is not another rewrite
The original Options/parse/parse_url, connect_wss, Queue, with_id/read_id, Agent sender/receiver/heartbeat, Session reader/writer/run, Proxy run/from_local/from_worker, and listener/agent loops are reused. Hooks never carry traffic over JNI, never queue or change framing, and run on the same IO thread.

The JNI bridge creates non-pointer generation handles and invokes nativeRun synchronously from a Java-created worker thread. No JNIEnv is reused across threads. Only `stop` (`io_context::stop`, thread-safe) and atomic counter polling cross thread boundaries. During stop, runtime closes tracked sessions on the IO thread; the context is destroyed before SSL context. Old Agent-session ownership cycles are broken by the original Agent::stop cleanup. The UI generation guard prevents a stopped generation from stopping a later service.

## Deliberately unchanged behavior
- No Cobalt introduction; existing C++20 Asio awaitables.
- No Worker modification or new protocol.
- No RDP parsing/modification in forwarding path.
- No new EOF/half-close semantics.
- Original reconnect-fix keepalive; this is not the optional hibernation optimization baseline.
- No Java fallback or use of Java TLS to forward bytes.

## Necessary platform changes and uncertainty
Native output is Android ELF shared code, not Windows EXE/DLL. Host test dependencies are not the Android build dependencies. Android trust anchors are exported from the default TrustManager and inserted in OpenSSL; this is not a reproduction of every Android TLS policy. Foreground-service and OS network limits remain. Raw Windows .lib files cannot be linked into ARM Android .so files.

## What the previous log proved
The ready handshake and a 19-byte exchange succeeded. The log does not establish what the RDP app consumed, prove complete TLS/CredSSP exchange, or uniquely identify a Java bug. The port reduces a major implementation variable; it is not a claim of a proven fix.
