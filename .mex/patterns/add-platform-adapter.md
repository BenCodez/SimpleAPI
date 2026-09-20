---
name: add-platform-adapter
description: Preserve neutral core and published packages.
triggers: [platform, adapter, Forge, Fabric]
last_updated: 2026-09-20
mex:
  id: mx_01M307A2284M0TD8J9FM0HT67D
  type: pattern
  status: promoted
  revision: 3
  title: add-platform-adapter
---

# Adding a platform adapter

Use a sibling adapter package when an actual loader implementation exists; do not add an empty module or put loader imports into `core` or the shared classifier. Preserve legacy public package behavior. See `docs/shared-libraries.md` and `SimpleAPI/src/test/java/com/bencodez/simpleapi/tests/shared/SharedArtifactTest.java`.
