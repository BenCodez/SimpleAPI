---
name: package-shared-artifact
description: Keep the allow-listed shared JAR neutral.
triggers: [shared, classifier, dependency]
last_updated: 2026-09-20
mex:
  id: mx_01M307A2437FSPJWNJ39ZNWPD4
  type: pattern
  status: promoted
  revision: 2
  title: package-shared-artifact
---

# Shared artifact change

Inspect POM class allow-list, package-time JAR test, and consumer dependency exclusions together. A classifier does not shrink POM transitives; adding a neutral class can still add linkage or jar-size costs. See `context/decisions.md`, `docs/shared-libraries.md`, `SimpleAPI/src/test/java/com/bencodez/simpleapi/tests/shared/SharedArtifactTest.java`.
