---
name: router
description: SimpleAPI package, platform, and transport memory routes.
edges:
  - target: patterns/add-platform-adapter.md
    condition: when adding a platform adapter
  - target: patterns/http-transport-change.md
    condition: when changing HTTP delivery
  - target: patterns/package-shared-artifact.md
    condition: when changing shared packaging
last_updated: 2026-09-20
---

# SimpleAPI memory routes

| Task | Read |
| --- | --- |
| Platform boundary or adapter | `context/architecture.md`, `patterns/add-platform-adapter.md` |
| Shared classifier or dependency | `context/decisions.md`, `patterns/package-shared-artifact.md` |
| HTTP transport and recovery | `context/http.md`, `patterns/http-transport-change.md` |
| Build/MEX environment | `context/stack.md`, `context/setup.md` |

Root `AGENTS.md` owns authority. Verify memory against current Java, tests, and formal docs.
