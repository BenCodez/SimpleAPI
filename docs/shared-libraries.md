# One SimpleAPI project, shared and platform packages

SimpleAPI has one Maven project: `SimpleAPI/pom.xml`. There is no root aggregator,
parent project, child module or generated-source staging. Use the existing project
in your IDE and the existing build command. No additional workflow is required.

## Source layout

```text
SimpleAPI/
  pom.xml
  src/main/java/com/bencodez/simpleapi/
    core/config/   # Annotation binding, Configurate reads, YAML documents
    core/sql/      # Shared database configuration
    bukkit/config/ # Native Bukkit configuration/annotation adapters
    file/...       # Compatibility APIs and legacy facades
    sql/...        # Existing public data/JDBC APIs
    ...            # Existing APIs remain in their published packages
  src/test/java/   # All former module tests live in the ordinary test tree
```

New platform-neutral implementations belong in `core`; Bukkit integration belongs
in `bukkit`. Future implemented Forge/Fabric/proxy adapters can use sibling
packages. Empty loader projects or pretend loader implementations are not added.

This is an incremental package migration, not a breaking rename of the public API.
Existing annotation types, `ConfigView`, document/editor/snapshot contracts,
`ParsedDuration`, SQL/value types and unrelated utilities retain their original
fully-qualified names. The legacy annotation binder, YAML/Configurate adapters,
MySQL configuration adapter and Bukkit configuration entry points delegate to the
new implementation packages. Section identity and covariant legacy return types
are preserved. Each migrated algorithm has one maintained implementation.

## Outputs from one POM

```sh
# From the repository root; unchanged for existing consumers and CI
mvn -B -f SimpleAPI/pom.xml clean package
```

The build produces:

| Local file | Maven artifact | Contents |
| --- | --- | --- |
| `SimpleAPI/target/SimpleAPI.jar` | `com.bencodez:simpleapi:<version>` | Existing full Bukkit/proxy distribution, with unchanged dependency scopes and relocations |
| `SimpleAPI/target/SimpleAPI-shared.jar` | `com.bencodez:simpleapi:<version>:shared` | Platform-neutral classes and required legacy neutral APIs, unshaded |
| `SimpleAPI/target/SimpleAPI-shared-sources.jar` | Classifier `shared-sources` | Matching maintained source files |

The attached JAR executions select compiled classes directly. They do not copy
sources to a second project or reimplement the library. Core code must not reference
Bukkit, proxy, Minecraft or mod-loader APIs. The shared class allow-list intentionally
excludes the legacy Bukkit-facing `AnnotationHandler`, `BukkitConfigView`, SQLite
plugin wrappers, player/item/GUI APIs and full platform entry points.

The former `simpleapi-parent`, `simpleapi-core`, `simpleapi-configurate` and
`simpleapi-sql` coordinates are no longer built. A consumer using those experimental
module coordinates must switch to the shared classifier and its explicit dependencies.
Existing full `simpleapi` consumers do not need to change their coordinates/imports.
Previously published artifacts, if any, are not deleted from a repository by this change.

## Native dependency configuration

A classifier shares its project's POM; it does **not** have a smaller independent
transitive dependency graph. Native consumers must exclude the full distribution's
transitives, then explicitly declare the shared dependencies they use. For Maven:

```xml
<dependency>
  <groupId>com.bencodez</groupId>
  <artifactId>simpleapi</artifactId>
  <version>${simpleapi.version}</version>
  <classifier>shared</classifier>
  <exclusions>
    <exclusion><groupId>*</groupId><artifactId>*</artifactId></exclusion>
  </exclusions>
</dependency>
<dependency>
  <groupId>org.spongepowered</groupId>
  <artifactId>configurate-yaml</artifactId>
  <version>${configurate.version}</version>
</dependency>
<dependency>
  <groupId>com.zaxxer</groupId>
  <artifactId>HikariCP</artifactId>
  <version>${hikari.version}</version>
</dependency>
```

Set these version properties to the versions selected by the SimpleAPI POM being
consumed. Configurate/Hikari supply their own runtime dependencies. JDBC drivers
remain the application's responsibility. Consumers using only the binder/value
APIs need neither Configurate nor Hikari; the full shared surface/linkage check uses
both. A Gradle consumer can disable transitives on the classified dependency and
add the same required runtime libraries explicitly.

Do not include both the full and shared SimpleAPI representations in the same
native runtime. The existing full JAR still relocates HikariCP; the thin shared JAR
does not. Future mixed AdvancedCore common/Bukkit packaging must align relocations
of callers and implementations and test the final JAR. Exclusions alone do not
prove the mixed SQL signatures compatible. Prefer JDBC/JDK types at new boundaries.

## Configuration use and guarantees

New code may import `com.bencodez.simpleapi.core.config.AnnotationBinder`,
`ConfigurateConfigView`, and `YamlConfigDocument`. Their existing public contract
and annotation types remain in the legacy neutral packages. Existing Bukkit code
can continue using `com.bencodez.simpleapi.file.annotation.AnnotationHandler` or
use the new `com.bencodez.simpleapi.bukkit.config.AnnotationHandler` entry point.

```java
Path directory = Path.of("config", "votingplugin");
Files.createDirectories(directory); // Explicit application-owned setup
ConfigDocument document = YamlConfigDocument.open(directory.resolve("VoteSites.yml"));
ConfigSnapshot before = document.snapshot();
ConfigSnapshot after = document.update(before.revision(), edit -> {
    edit.set("PointsOnVote", 1);
    edit.set("Rewards.Commands", List.of("give player minecraft:diamond"));
});
new AnnotationBinder().load(after.view(), options);
```

All file methods are synchronous; run I/O away from game/entity threads. Open and
reload do not create files/parents. Missing files produce an empty snapshot;
invalid files throw and failed reloads retain the last good in-memory state.
Updates edit a detached copy, check revisions, bound serialized bytes, write and
force a temporary sibling, then use atomic replacement with no truncate fallback.
Target symlinks are rejected; POSIX mode bits are retained and new files default to
owner read/write. Editors are callback/thread-scoped and snapshots are detached.

Default byte limit is 1 MiB, configurable up to 16 MiB. Tree limits are 64 levels
and 100,000 nodes. These existing semantics are unchanged by package consolidation.
This API is for trusted administrator-owned local files, not hostile YAML uploads.
The parent directory and cross-process writer ownership belong to the application.
Observed external edits are rejected, but the final revision check/rename is not a
cross-process compare-and-swap. Inline formatting/comments, non-POSIX ACLs, file
ownership and directory-entry power-loss durability are not guaranteed.

Getter/annotation defaults, strict scalar reads and legacy list conversions are
supported. Bukkit default-tree overlays and native serialized Bukkit items are not
emulated. Literal-key access remains available on Configurate views. This packaging
change does not add the broader reward configuration or SQLite extraction work.

## Validation

All previous module tests have moved into `SimpleAPI/src/test/java`; existing
headless/duration tests are reused instead of duplicated. Tests that need an absent
Bukkit classpath explicitly create an isolated classloader rather than assuming
that Bukkit is absent from the ordinary single-project test runtime.

The normal package command runs the full unit suite, including real Bukkit/core
configuration parity and legacy package compatibility. After packaging/shading,
`SharedArtifactTest` checks the actual shared/source/full JARs, rejects platform
references in shared class files, links selected classes using only the shared JAR
and native dependencies, and runs the configuration/binder/SQL smoke fixture there.
Runtime dependency locations are taken from Maven's resolved classpath, not pinned
version paths. Reports are under `target/surefire-reports` and
`target/shared-artifact-reports`. Normal `-DskipTests` behavior is unchanged.

There is no extra GitHub workflow, cross-repository checkout, pinned downstream
commit, production access or remote publication in these tests. AdvancedCore and
VotingPlugin compatibility builds remain deliberate checks for API/release work.
No live-server or live-database coverage is implied by the headless tests.

## Install and publication

```sh
mvn -B -f SimpleAPI/pom.xml clean install
```

Installs the full, shared and shared-source artifacts under the same project
version. Existing deployment profiles/configuration remain unchanged; attached
artifacts are available to the existing publishing lifecycle. This change does not
invoke deployment or claim the new classifier is already on Nexus. There is no
separate parent or module publication step to maintain.
