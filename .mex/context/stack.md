---
name: stack
description: Current build and platform boundary.
triggers: [Java, Maven, Bukkit, proxy]
last_updated: 2026-09-20
mex:
  id: mx_01M307GHE6NDAPVZKKZ8ECR1JP
  type: guide
  status: promoted
  revision: 1
  title: stack
---

# Current shape

`SimpleAPI/pom.xml` targets Java 21 and builds one Maven project. The full artifact has Bukkit/Spigot, BungeeCord, and Velocity integrations; there are no Forge/Fabric/NeoForge loader modules in this checkout. CI runs the nested POM package goal. The package phase tests the actual shared and full JARs. Source: `SimpleAPI/pom.xml`, `.github/workflows/maven.yml`, `docs/shared-libraries.md`.
