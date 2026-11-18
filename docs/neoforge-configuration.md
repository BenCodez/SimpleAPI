# YAML configuration in a NeoForge consumer

Use `com.bencodez.simpleapi.core.config.YamlConfiguration` from the `shared`
classifier for direct file/resource loading, typed reads, sections and atomic
persistence over the existing neutral document and views.
No SimpleAPI loader adapter, second YAML parser, or NeoForge dependency is needed
for this configuration path. The mod supplies its configuration directory and
owns lifecycle scheduling. Existing Bukkit/proxy consumers keep their current
dependencies, configuration files and APIs.

## Dependency baseline

The current SimpleAPI POM selects Java 21, SimpleAPI `1.0.2-SNAPSHOT`, and
Configurate `4.2.0`. Use the version actually built/published for your integration;
this guide does not claim a new artifact has been released. It does not select a
Minecraft/NeoForge version or certify a mod runtime.

For a Gradle Kotlin DSL consumer, using the existing SimpleAPI Maven repository:

```kotlin
repositories {
    maven("https://nexus.bencodez.com/repository/maven-public/")
    mavenCentral()
}

dependencies {
    implementation("com.bencodez:simpleapi:1.0.2-SNAPSHOT:shared") {
        isTransitive = false
    }
    implementation("org.spongepowered:configurate-yaml:4.2.0")
}
```

Disable transitives **only on SimpleAPI**: a classifier uses the full project's
POM and otherwise brings platform dependencies. Keep Configurate's own transitives.
Do not place the full and shared SimpleAPI JARs together in the native runtime.
Hikari/JDBC are unnecessary for this YAML-only path; consumers using the shared SQL
API must add its dependencies separately, as described in [shared-libraries.md](shared-libraries.md).

`implementation` configures compilation/runtime resolution in Gradle. The mod's
build must also arrange delivery of SimpleAPI shared, Configurate and its runtime
dependencies using its chosen loader packaging mechanism. Dependency resolution
alone does not prove these libraries are embedded in the final mod JAR. Verify the
final packaged mod independently; SimpleAPI's headless tests do not launch NeoForge.

## Open, read, update and reload

Call this on the application's configuration I/O worker. `configDirectory` is a
trusted, application-owned `Path` supplied by the mod; no loader class is imported
by SimpleAPI. Do not use Bukkit `YMLFile`, `VelocityYMLFile` or native platform
configuration objects in this path.

```java
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import com.bencodez.simpleapi.core.config.YamlConfiguration;

final class NativeSettings {
    private final YamlConfiguration yaml;

    NativeSettings(Path configDirectory) throws IOException {
        Files.createDirectories(configDirectory); // Application-owned setup
        yaml = YamlConfiguration.load(configDirectory.resolve("settings.yml"),
                NativeSettings.class, "/native-settings.yml");
    }

    int pointsOnVote() { return yaml.getInt("PointsOnVote", 1); }

    List<String> commands(String siteKey) {
        var sites = yaml.getConfigurationSection("VoteSites");
        var site = sites == null ? null : sites.at(siteKey); // Literal key, including dots
        return site == null ? List.of() : site.getStringList("Rewards.Commands");
    }

    void changeCommands(String siteKey, List<String> commands) throws IOException {
        yaml.setAt(commands, "VoteSites", siteKey, "Rewards", "Commands");
    }

    void reload() throws IOException { yaml.reload(); }
}
```

Bundle `/native-settings.yml` in the consumer's resources, for example:

```yaml
PointsOnVote: 1
VoteSites:
  'some.site':
    Rewards:
      Commands: [say voted]
```

`load(path)` reads a missing document without creating it. The classpath-resource
overload initializes missing files only, preserving initial resource bytes after
bounded validation. Existing files, including intentionally empty files, are never
replaced or merged with resource defaults. An existing file does not require the
resource to exist. There is no resource default-tree overlay; use getter defaults
for absent options and explicit updates for intentional migrations.
Resource initialization atomically publishes a staged file only if absent, using
a same-directory hard link; unsupported filesystems fail without an unsafe fallback.

`load(path, inputStream)` also supports missing-file defaults and always closes the
supplied stream, including on failure. Read/close failures precede initialization.
Plain load and stream load accept an optional final byte-limit argument.

Use `yaml.getInt("PointsOnVote", 1)` for a numeric read with a fallback.
Numeric/boolean scalar getters are strict: a quoted `'12'` is not a number.
Existing list conversions remain supported. Missing values use getter/annotation
defaults; an explicit empty list remains distinct from a missing value when read
through a structured view. Reads are case-sensitive by default.

The facade implements `StructuredConfigView` directly. No Configurate nodes or
casts are needed. String/boolean/int/long/double reads also have one-argument
conveniences using null/false/zero defaults. For structured or case-insensitive access:

```java
yaml.setIgnoreCase(true);
int points = yaml.getInt("pointsonvote", 1);
var literalSite = yaml.at("votesites", "some.site"); // Literal segments
var stable = yaml.snapshot(); // Detached revision and view
```

Case-insensitive wrapping is opt-in; enumeration retains original spelling and the
first enumerated key wins case collisions. Numeric YAML keys remain addressable
by string paths. The casing option affects reads only; edits always use exact keys.
Use `yaml.setAt(value, "some.site", "Commands")` for literal key
segments containing dots. Structured exports are detached, bounded plain-data trees;
native platform objects are rejected. Existing annotation types and
`core.config.AnnotationBinder` can bind ordinary option classes from these views.

`getData()`, `snapshot()` and sections are detached read views. Retained views do
not change after reload/update or a casing-policy change; reacquire them when
needed. Direct facade getters follow the latest successful operation.

All load/reload/update operations are synchronous. A missing file opens empty
without creating it. Malformed initial YAML throws `IOException`; do not silently
replace it with defaults. On reload, call `yaml.reload()` and validate the
returned snapshot before publishing application settings. A reload exception
retains the last valid document snapshot and must abort runtime reconfiguration.
Application validation/publication remains the consumer's responsibility.

`set`, `setAt`, `update` and `save` immediately persist; there is no mutable pending
tree. Use `update(edit -> ...)` for a multi-key transaction, or
`update(snapshot.revision(), edit -> ...)` to reject a stale application edit.
A null value removes the selected key. `save()` explicitly rewrites current data
or creates an empty missing file. Use `yaml.reload()` for external changes.

Updates check the snapshot revision, retain unknown keys, and use a bounded
temporary sibling followed by atomic replacement. Failed updates retain the active
snapshot. External edits, stale revisions, serialization limits and filesystem
failures are reported instead of truncating the original file. Reload after an
external edit before retrying. Old snapshots remain detached across updates.
Document instances from the same defining classloader coordinate final revision
checks and publication by canonical path, so stale same-process writers cannot
both succeed and silently lose one edit. Cross-process writer exclusion remains
the application's responsibility.

Defaults are 1 MiB input/output (configurable up to 16 MiB), 64 levels and 100,000
nodes. The parent directory must exist and be trusted; the application owns
cross-process writer exclusion. Inline comment/format preservation, Bukkit default
overlays and native serialized items are not promised. See the full persistence
contract in [shared-libraries.md](shared-libraries.md).

## Verification and remaining integration

`YamlConfigurationTest` exercises the production facade, resource read/close errors,
empty existing files, bounded writes, casing, initialization races and real
unwritable-parent rollback/retry. The existing `NativeYamlTest` document regressions
are retained. The normal Maven `package` phase also runs `NativeYamlApiFixture`
and `NativeYamlFixture` against the actual shared JAR with only neutral
runtime libraries, explicitly rejecting Bukkit, proxy, Minecraft and mod-loader
classes. Existing tests cover legacy facades and Bukkit configuration parity.

This consumption path supplies YAML configuration only. Mod entrypoints, event
registration, scheduler ownership, players, permissions, commands, rewards and
final dependency embedding belong to the native integration and require separate
design/tests. No native server acceptance is implied by shared class-loading tests.
