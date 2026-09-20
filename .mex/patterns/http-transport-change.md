---
name: http-transport-change
description: Trace pending delivery through failure and restart.
triggers: [HTTP, queue, delivery]
last_updated: 2026-09-20
mex:
  id: mx_01M307A235GS0WVEWCR8838FXD
  type: pattern
  status: promoted
  revision: 2
  title: http-transport-change
---

# HTTP transport change

Map enqueue, persisted state, send, acknowledgement, restart recovery, and unreadable state before changing completion semantics. Keep TLS/enrollment and bounded execution checks in the same review. See `context/http.md` and current tests under `SimpleAPI/src/test/java/com/bencodez/simpleapi/servercomm/http/`.
