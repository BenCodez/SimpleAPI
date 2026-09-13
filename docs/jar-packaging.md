# Full JAR size and cryptography packaging

`SimpleAPI.jar` remains the full, HTTP-capable artifact. The `shared` and
`shared-sources` classifiers, dependency scopes, and public APIs are unchanged.
There are no additional modules or runtime downloads.

The HTTP transport uses Bouncy Castle for its private CA and certificates.
Do not remove those dependencies, switch them to `provided`, strip provider
mappings, or enable broad `minimizeJar` without packaged-runtime validation.
Providers load some implementation classes reflectively.

The full shaded artifact is not a multi-release JAR. Its shade filter omits
`META-INF/versions/**`, which that artifact cannot select, while retaining base
classes and resources. This is a conservative reduction of unreachable payload,
not removal of the crypto provider or the entire HTTP dependency cost.

Java only selects versioned classes when the final manifest declares
`Multi-Release: true`. If that contract changes, revisit this filter and the
packaged-runtime checks rather than silently enabling the attribute. Downstream
consumers that re-shade this artifact receive the base BC implementation.

## Verification

Run from the repository root with JDK 21 and Maven:

```shell
mvn -B -f SimpleAPI/pom.xml clean package
git diff --check
```

The package phase runs `FullArtifactTest` after shading, followed by the existing
shared-classpath tests. It verifies the non-multi-release manifest, absence of
all unreachable versioned payload and preservation of every base
`org/bouncycastle/` entry from the three resolved BC libraries. It prints the final JAR size and the
compressed upstream payload omitted. That payload counter is not an exact
before/after distribution size: shading/recompression and ZIP overhead differ.
To measure the exact reduction, compare clean baseline and candidate builds with
the same resolved dependencies and JDK.

A fresh JVM runs a JDK-only fixture using just the packaged JAR and the fixture's
single class file. No Maven dependencies or `target/classes` are on its classpath.
It verifies CA/server/client certificate creation, PKCS12 use, authenticated TLS,
rejection of absent/foreign client credentials, persisted identity reload, and
server/CA renewal. The subprocess and socket operations have bounded timeouts.
Reports are written under `SimpleAPI/target/full-artifact-reports/`.
