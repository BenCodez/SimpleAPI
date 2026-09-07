# Shared SimpleAPI libraries

This follows the configuration binding foundation in PR #75. It adds buildable,
separately publishable, Bukkit-free artifacts and production configuration I/O.
It does not port VotingPlugin or AdvancedCore, and does not change their releases.

## Artifacts

| Artifact | Contents | Runtime dependencies |
| --- | --- | --- |
| `com.bencodez:simpleapi-core` | Existing annotation binder and annotation types, `ConfigView`, duration support, debug enum, SQL data values/columns; new document/editor/snapshot contracts | JDK 21 only |
| `com.bencodez:simpleapi-configurate` | `ConfigurateConfigView` and `YamlConfigDocument` | Core and Configurate YAML 4.2.0 |
| `com.bencodez:simpleapi-sql` | Existing JDBC/Hikari connection management, MySQL wrapper, abstract tables, queries and configuration model; new `MysqlConfigView` | Core and HikariCP 7.0.2 |
| `com.bencodez:simpleapi` | Existing full Bukkit/proxy distribution, including the shared classes | Existing dependency set, unchanged |

The new modules currently use version `1.0.2-SNAPSHOT`, aligned with SimpleAPI.
They are libraries, not independently installed server mods. SQL drivers are still
selected/provided by the consuming application; publishing the SQL module does not
silently add a JDBC driver or change any existing database behavior/schema.

### One maintained implementation, two packaging targets

All implementation source stays under `SimpleAPI/src/main/java`, including the
new APIs. The shared module POMs stage explicit allow-lists into their own
`target/generated-sources/shared` directories. This is generated build input,
not a maintained source fork. Their javac source paths never include the rest of
the Bukkit tree. Source JARs contain the staged implementation as well.

This layout deliberately preserves the old standalone build, source packages,
shading, and artifact coordinates while making the same implementation available
to native loaders. No existing class body, public signature, or legacy POM is
changed. Full distribution users can use the new APIs without adding new Maven
coordinates.

**Do not package both representations of the same classes.** A native application
uses the shared artifacts and never the full `simpleapi` artifact. A Bukkit
application retains the full distribution. Future AdvancedCore common-module
integration must choose one representation of each SimpleAPI class in the final
Bukkit JAR, keep versions aligned, and verify the final shaded artifact's linkage.
Existing AdvancedCore/VotingPlugin builds do not consume the new thin modules and
need no dependency changes in this PR.

The thin SQL artifact intentionally uses unrelocated HikariCP, whereas the full
SimpleAPI distribution already relocates HikariCP. Some existing SQL public
signatures expose Hikari types. Therefore excluding the thin dependencies in favor
of the full JAR is not sufficient by itself for common code compiled against those
signatures: the final Bukkit packaging must relocate the common callers and the
provided SQL implementations consistently. Prefer JDBC/JDK types at new shared
boundaries and add a final packaged Bukkit linkage test when making that consumer
change. This PR does not claim that future mixed packaging is already validated.

A native mod packager must include its actual runtime dependency graph using the
target loader's supported mechanism. No changes to existing full-JAR shading are
made here.

## Native configuration example

```java
Path directory = Path.of("config", "votingplugin");
Files.createDirectories(directory); // explicit application-owned setup
ConfigDocument document = YamlConfigDocument.open(directory.resolve("VoteSites.yml"));
ConfigSnapshot before = document.snapshot();
ConfigSnapshot after = document.update(before.revision(), edit -> {
    edit.set("PointsOnVote", 1);
    edit.set("Rewards.Commands", List.of("give player minecraft:diamond"));
    edit.setAt(Map.of("ServiceSite", "example.site"), "VoteSites", "example.site");
});
new AnnotationBinder().load(after.view(), options);
```

Shared section-annotated fields use `ConfigView`. Existing Bukkit callers keep
using `AnnotationHandler` and receive native `ConfigurationSection` fields as in
PR #75. The old API has no new overloads or altered coercion rules.

`ConfigurateConfigView` reads numeric and boolean scalars strictly, matching the
Bukkit getter rules rather than converting arbitrary strings to numbers/bools.
String/integer lists preserve the legacy element conversions and empty-list
annotation fallbacks. It supports a selectable path separator and literal key
segments through `at(String...)`. Numeric YAML keys remain addressable as strings;
ambiguous string representations are rejected by document validation.

Getter and annotation defaults work. Bukkit's mutable configuration-default tree,
section `toString()` output, and Bukkit-serialized item/player objects are not
emulated. Parse-format-specific behavior is not claimed to be a lossless Bukkit
YAML migration. Port native object representations in the owning platform adapter.

## Document safety and ownership

All methods are synchronous: call file operations on an I/O worker, not a game or
entity thread. `open` and `reload` never create a file or parent directory. Missing
files produce an empty snapshot with revision `missing`; invalid/unreadable files
throw. A failed reload retains the last-good in-memory configuration.

An update takes an expected revision and edits a private copy. Callback/validation
failure discards that copy. The editor is limited to its callback thread and cannot
write after the callback. Input collections and returned snapshots are detached
from future document state. Edits accept ordinary scalar/list/string-keyed-map
values, not native server objects or cyclic graphs.

The writer checks the expected revision and current disk content, bounds serialized
bytes, writes a temporary sibling file, forces its contents, and atomically replaces
the target. There is no truncate-in-place or non-atomic replacement fallback. Target
symlinks and non-regular files are rejected. Existing POSIX permission bits are
retained; new POSIX files are owner-read/write only. Temporary files are cleaned up.

Default size limit: 1 MiB (explicitly configurable from 1 byte to 16 MiB). Parsed/
edited trees are limited to 64 levels and 100,000 nodes. UTF-8 decoding reports
malformed input instead of silently replacing bytes.

This is for administrator-owned local files. Parent directories must be trusted.
The caller must own cross-process writes: revision checks detect observed external
edits, but an unrelated process can race the final check/rename. This is not a
filesystem compare-and-swap, database transaction, or hostile YAML upload validator.
Parsing uses Configurate's YAML parser policies. Inline comments/formatting, file
ownership/non-POSIX ACLs and directory-entry power-loss durability are not promised.
Do not wire this directly to an untrusted network editor without the application's
path, authentication, YAML-complexity, revision and secret-masking controls.

## Building and publishing

Existing command and distribution remain available:

```sh
mvn -B -f SimpleAPI/pom.xml package
```

Build/test/install all artifacts, including the full legacy distribution:

```sh
mvn -B clean install
```

Build only shared modules and the parent (no server APIs needed):

```sh
mvn -B -pl simpleapi-core,simpleapi-configurate,simpleapi-sql -am clean install
```

A release operator can publish the new artifacts and their parent using existing
Nexus credentials. CI does not run this command:

```sh
mvn -B -Pdeploy-shared -pl simpleapi-core,simpleapi-configurate,simpleapi-sql -am deploy
```

Existing Jenkins/legacy publication remains untouched; it will not automatically
publish the new coordinates until its operator adds the shared publication step.
Keep all four artifacts' versions aligned when releasing.

## Verification

`shared-libraries.yml` builds the shared and full artifacts on JDK 21, runs the
existing headless/duration tests and new configuration tests, checks packaged JAR
class boundaries, and compiles a consumer against packaged JARs (not target/classes).
A separate probe compares actual Bukkit scalar/list and SQL configuration behavior.
Source staging is checked byte-for-byte against the one maintained implementation.

The same workflow installs the exact candidate in an isolated Maven repository,
builds pinned AdvancedCore and VotingPlugin fixtures, verifies their dependency
classpath paths and checks that installed candidate JAR hashes were not replaced.
It never merges, publishes releases, modifies production services, or submits a
dependency graph on a pull request. Update the fixture SHAs deliberately when testing newer
consumer source. SQL tests here cover configuration/linkage, not a live database
matrix. Live Minecraft/Folia/Forge/Fabric smoke tests remain application-level work.

## What remains for the platform port

SimpleAPI's reusable configuration/duration/data/SQL layer is now packaged for
consumption without Bukkit. AdvancedCore still needs its own shared runtime and
platform-specific execution boundaries. The existing Bukkit/Folia scheduler,
player, messaging, item, inventory and mixed `ArrayUtils` APIs remain untouched;
their game operations belong behind adapters during that extraction. Do not replace
entity-aware scheduling with a generic global-thread executor.

The separate HTTP transport work from PR #73 is not copied or reworked here. It
merged into main while this change was being validated; the PR merge-ref build
tests compatibility with it. Packaging that transport as a thin native dependency
is a separate follow-up, not part of these configuration/data/SQL artifacts.
