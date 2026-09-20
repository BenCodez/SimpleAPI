---
name: http
description: HTTP transport lifecycle and pending-delivery boundaries.
triggers: [HTTP, pending, delivery, TLS]
last_updated: 2026-09-20
mex:
  id: mx_01M307GHE519QFPZ260EC82CG8
  type: constraint
  status: promoted
  revision: 1
  title: http
---

# HTTP transport boundary

The HTTP transport uses pinned TLS and enrollment; certificate binding authenticates peers, while the envelopes are strictly parsed but not independently signed. Proxy-to-backend outgoing messages are durably queued, and both sides have durable inbound replay fences. Backend-to-proxy outgoing messages remain in memory with a bounded shutdown flush. A network response, queue write, and application acknowledgement are different events. A valid nonempty queue directory reports pending work; invalid or inaccessible queue structure throws `IOException`, which callers must handle conservatively. A restart-visible `RUNNING` callback is neither replayed nor acknowledged because its side effects are uncertain. Source: `SimpleAPI/src/main/java/com/bencodez/simpleapi/servercomm/http/`, `SimpleAPI/src/test/java/com/bencodez/simpleapi/servercomm/http/`.

Transport work must keep size bounds, shutdown behavior, and authentication checks together. Inspect current tests for the exact guarantee instead of inferring exactly-once behavior from a queue name.
