# Full JAR size and cryptography packaging

`SimpleAPI.jar` is the self-contained, HTTP-capable artifact. The `shared` and
`shared-sources` classifiers, public APIs, dependency scopes, relocations, and
normal publication behavior remain unchanged. No external libraries are fetched
at runtime.

The HTTP identity implementation uses the JDK's EC/PKCS#12/TLS APIs; it does
**not** require Bouncy Castle. Keep the packaged certificate issuance, mutual
TLS, identity persistence, and renewal tests before changing TLS packaging.

## Compact non-modular shaded artifact

The full shaded JAR is not a Java multi-release JAR or a JPMS module. It filters
`META-INF/versions/**` and root `module-info.class`, which cannot supply valid
alternate implementations or a usable module descriptor in this distribution.

The shading step also excludes `org/checkerframework/**` and
`org/jspecify/**` from the full JAR. These are annotation-only support
packages, not implemented features, providers, or runtime entry points. Keep
all API classes, Hikari/FoliaLib relocations, service descriptors, application
resources, license notices, and other runtime metadata.

Shaded dependency `META-INF/maven/**/pom.xml` and `pom.properties` entries
are excluded to avoid embedding duplicate build descriptions. This does *not*
remove non-POM version or service resources needed at runtime.

Do not turn on global `minimizeJar`; bytecode reachability analysis can omit
reflection- and service-loaded APIs, Redis compatibility types, and platform
adapters. Treat any further reduction that removes code as a separately tested
compatibility change. Consumers needing external annotation classes in their
own source should declare those annotations explicitly.

## Validation

Run from the repository root with JDK 21 and Maven:

```shell
mvn -B -f SimpleAPI/pom.xml clean package
git diff --check
```

The package phase runs `FullArtifactTest` after shading, then the existing
`SharedArtifactTest`. They validate the actual full and shared JARs, retained
legacy Jedis constructor linkage, the exclusion boundaries, and neutral
shared-classpath linkage.

A separate JVM uses only the packaged full JAR and one test fixture class to
exercise JDK-only CA/server/client certificate issuance, PKCS#12, mutual TLS,
rejection of missing/foreign credentials, reload, and renewal. Required service
resources remain packaged. Reports go under `SimpleAPI/target/`.

Measure improvements by comparing clean builds with the same resolved
dependencies and JDK. A smaller JAR and a passing CI suite do not substitute
for testing external consumers that have not been included in CI.
