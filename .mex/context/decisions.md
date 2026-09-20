---
name: decisions
description: Shared artifact and consumer compatibility rationale.
triggers: [decision, shared, dependency, classifier]
last_updated: 2026-09-20
mex:
  id: mx_01M307GHE55290N81KD9W51AAG
  type: decision
  status: promoted
  revision: 1
  title: decisions
---

# Shared artifact choices

The POM packages a full shaded runtime JAR and an allow-listed `shared` classifier plus sources. The classifier excludes platform references, but uses the same POM dependency declarations under normal Maven scope rules; a native consumer must exclude unwanted transitives and explicitly declare neutral dependencies it uses. Do not claim the classifier is an independently slim published module. Source: `docs/shared-libraries.md`, `SimpleAPI/pom.xml`, `SimpleAPI/src/test/java/com/bencodez/simpleapi/tests/shared/SharedArtifactTest.java`.

Package migration is incremental: legacy neutral APIs and Bukkit-facing entry points keep published names and delegate into new implementation packages. Changing public names or section identity can break existing AdvancedCore/VotingPlugin consumers. Source: `docs/shared-libraries.md`, `docs/platform-neutral-configuration.md`.
