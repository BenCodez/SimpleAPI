# Structured reward configuration

This is an additive configuration boundary for the AdvancedCore extraction. It
uses the existing single SimpleAPI project; no modules or workflows are added.
Existing `ConfigView`, annotation binding, file persistence, Bukkit wrappers and
SQL APIs are unchanged.

## Choose the adapter

- Bukkit: `bukkit.config.BukkitStructuredConfigView(ConfigurationSection)`.
- Configurate: `core.config.ConfigurateStructuredConfigView(ConfigurationNode)`.
- Existing Configurate document view: construct the structured adapter from the
  existing `core.config.ConfigurateConfigView` instance. The read view shares the
  same backing node; it does not create a second parser or mutable document.
- For legacy reward-key matching, wrap either adapter in
  `core.config.CaseInsensitiveConfigView`.

The new `core.config.StructuredConfigView` extends the existing `ConfigView`, so
existing third-party ConfigView implementations acquire no new abstract methods.
The existing binder accepts the new views without a new overload.

## Reward shape and values

`kind(path)` distinguishes missing values, strings, numbers, booleans, lists,
native sections, raw maps, and other objects. In particular, an explicitly empty
list remains LIST rather than becoming indistinguishable from a missing reward.
A raw Bukkit map is MAP, not SECTION: do not accidentally change the old
`isConfigurationSection` branch when migrating reward execution.

`value(path)` and `snapshotValues()` return detached, recursively unmodifiable
plain-data trees. Native objects such as ItemStack are rejected with a location
rather than silently dropped, serialized, or changed into text. The platform's
item adapter must handle such values explicitly. Copying is bounded to 64 levels
and 100,000 visited nodes, rejects cycles and ambiguous stringified map keys, and
preserves ordering, scalar types, null list entries and original key casing.

`kindAt`, `valueAt`, and `at` accept literal segments. For example:

```java
StructuredConfigView rewards = new CaseInsensitiveConfigView(
    new BukkitStructuredConfigView(configuration));
Object commands = rewards.valueAt("VoteSites", "example.site", "Commands");
Map<String, Object> detached = rewards.snapshotValues();
```

A path string uses the backing API's separator. Literal segments never change the
root's path separator. With a non-default separator, pass that same separator to
the case-insensitive wrapper. It resolves one native section at a time.

## Compatibility boundaries

Ordinary typed getters delegate to the existing adapters. The new wrapper follows
the old CaseInsensitiveSection's key-enumeration order: the first matching key
wins even when a later key matches the requested spelling exactly. It retains
original key casing and does not mutate the original section.

Defaults follow the backing getter/key-enumeration rules. A snapshot exports
what that section enumerates; it does not materialize unenumerated Bukkit default
keys. Case-insensitive lookup follows the same enumeration limitation as the old
wrapper. This is deliberate and covered by comparison tests. Configurate does
not gain a Bukkit mutable-default-tree implementation.

These are local configuration reads, not a hostile-YAML upload interface. Keep
configuration thread ownership in the caller. The existing document validation,
revision and persistence rules are not changed.

## Validation

Run the unchanged build:

```sh
mvn -B -f SimpleAPI/pom.xml clean package
```

The new structured-view and plain-copy tests cover reward shapes, empty/missing
values, legacy case collisions, literal keys, defaults, native object rejection,
copy isolation, cycles, bounds, numeric keys and annotation binding. The existing
package-phase shared-artifact test also scans and links the new core classes from
the shared classifier without Bukkit. No new publication or CI pipeline is needed.
