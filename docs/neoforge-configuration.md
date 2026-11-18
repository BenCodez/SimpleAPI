# YAML configuration in a NeoForge consumer

Use SimpleAPI's existing platform-neutral YAML API from the `shared` classifier.
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
import com.bencodez.simpleapi.core.config.YamlConfigDocument;
import com.bencodez.simpleapi.file.config.ConfigSnapshot;

static YamlConfigDocument openSettings(Path configDirectory) throws IOException {
    Files.createDirectories(configDirectory); // Application-owned setup
    return YamlConfigDocument.open(configDirectory.resolve("settings.yml"));
}

static ConfigSnapshot initialize(YamlConfigDocument document) throws IOException {
    ConfigSnapshot before = document.snapshot();
    // Seed defaults only for a missing file; do not replace an existing config.
    if (!before.revision().equals("missing")) return before;
    return document.update(before.revision(), edit -> {
        edit.set("Points", 1);
        edit.set("Rewards.Commands", List.of("say voted"));
    });
}
```

Use `snapshot.view().getInt("Points", 1)` for a numeric read with a fallback.
Numeric/boolean scalar getters are strict: a quoted `'12'` is not a number.
Existing list conversions remain supported. Missing values use getter/annotation
defaults; an explicit empty list remains distinct from a missing value when read
through a structured view. Reads are case-sensitive by default.

For structured or case-insensitive access, wrap the existing snapshot view:

```java
import com.bencodez.simpleapi.core.config.CaseInsensitiveConfigView;
import com.bencodez.simpleapi.core.config.ConfigurateConfigView;
import com.bencodez.simpleapi.core.config.ConfigurateStructuredConfigView;

var structured = new ConfigurateStructuredConfigView(
        (ConfigurateConfigView) snapshot.view()); // Snapshot from core YamlConfigDocument
var insensitive = new CaseInsensitiveConfigView(structured);
int points = insensitive.getInt("points", 1);
var literalSite = structured.at("some.site"); // One literal key, not a dotted path
```

Case-insensitive wrapping is opt-in; enumeration retains original spelling and the
first enumerated key wins case collisions. Numeric YAML keys remain addressable
by string paths. Use `edit.setAt(value, "some.site", "Commands")` for literal key
segments containing dots. Structured exports are detached, bounded plain-data trees;
native platform objects are rejected. Existing annotation types and
`core.config.AnnotationBinder` can bind ordinary option classes from these views.

All open/reload/update operations are synchronous. A missing file opens empty
without creating it. Malformed initial YAML throws `IOException`; do not silently
replace it with defaults. On reload, call `document.reload()` and validate the
returned snapshot before publishing application settings. A reload exception
retains the last valid document snapshot and must abort runtime reconfiguration.
Application validation/publication remains the consumer's responsibility.

Updates require the snapshot revision, retain unknown keys, and use a bounded
temporary sibling followed by atomic replacement. Failed updates retain the active
snapshot. External edits, stale revisions, serialization limits and filesystem
failures are reported instead of truncating the original file. Reload after an
external edit before retrying. Old snapshots remain detached across updates.

Defaults are 1 MiB input/output (configurable up to 16 MiB), 64 levels and 100,000
nodes. The parent directory must exist and be trusted; the application owns
cross-process writer exclusion. Inline comment/format preservation, Bukkit default
overlays and native serialized items are not promised. See the full persistence
contract in [shared-libraries.md](shared-libraries.md).

## Verification and remaining integration

`NativeYamlTest` exercises this contract directly. The normal Maven `package`
phase also runs `NativeYamlFixture` against the actual shared JAR with only neutral
runtime libraries, explicitly rejecting Bukkit, proxy, Minecraft and mod-loader
classes. Existing tests cover legacy facades and Bukkit configuration parity.

This consumption path supplies YAML configuration only. Mod entrypoints, event
registration, scheduler ownership, players, permissions, commands, rewards and
final dependency embedding belong to the native integration and require separate
design/tests. No native server acceptance is implied by shared class-loading tests.
