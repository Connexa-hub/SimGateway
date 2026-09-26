# SimGateway Development Status

## Stage 01 — Connection Crash and Socket Stability

### Status
Stage 01 implementation received from Claude Code and under verification.

### Changes
- Fixed UI updates from NSD callbacks by posting UI work through `runOnUiThread`.
- Added serialized socket I/O in `MainActivity`.
- Prevented multiple threads from reading the same `BufferedReader` concurrently.
- Added `sendAndAwaitResponse()` for serialized request/response operations.
- Updated gateway client call handling to use the serialized socket I/O path.
- Added active client socket tracking in `GatewayService`.
- Explicitly closes active client sockets when the gateway service is destroyed.
- Preserved NSD discovery and TCP port 8765 architecture.

### Protocol
Current protocol remains compatible with the existing gateway:
- Legacy `PING`
- Legacy `STATUS`
- Legacy `QUIT`
- JSON protocol version 1
- JSON `ping`
- JSON `status`
- JSON `call`

### Important limitation
Stage 01 does not yet implement the final connection manager, correlation-ID routing, SMS, incoming-call handling, call-state events, audio transport, pairing/security, or final UI.

### Verification required
1. Compile debug APK.
2. Install on Phone A and Phone B.
3. Test Provider mode.
4. Test Client discovery.
5. Test connection when Provider mode is active.
6. Confirm the previous client crash is resolved.
7. Test repeated connect/disconnect.
8. Test repeated call requests without concurrent socket-reader failures.

### Build policy
Debug APK must be built and tested before Stage 01 is considered complete.

### Git policy
Do not consider Stage 01 complete until the verified changes are committed and pushed to `main`.
