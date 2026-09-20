---
name: architecture
description: Single-project platform and shared-code boundary.
triggers: [architecture, platform, core]
last_updated: 2026-09-20
mex:
  id: mx_01M307A1MAWWHQYM503CMF57NN
  type: architecture
  status: promoted
  revision: 1
  title: architecture
---

# Platform boundary

This checkout is one Maven project, not a multi-module loader build. `core` contains platform-neutral configuration/SQL surfaces; Bukkit adapters live in sibling packages while existing public package names remain available through delegation. Future Forge/Fabric/NeoForge adapters are described as possibilities, not present implementations. Source: `docs/shared-libraries.md`, `docs/platform-neutral-configuration.md`, `SimpleAPI/src/main/java/com/bencodez/simpleapi/core/`, `SimpleAPI/pom.xml`.
