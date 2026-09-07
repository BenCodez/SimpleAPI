# Platform-neutral configuration foundation

This is the first compatibility-preserving step toward reusing SimpleAPI's
configuration annotations outside Bukkit. It is not a Forge/Fabric implementation
or a separately published `simpleapi-core` artifact yet. The existing Maven
coordinates, shaded JAR, build command, configuration files and dependencies are
unchanged. Do not put the entire current SimpleAPI JAR on a mod server and assume
that its other classes are platform-neutral.

## Existing Bukkit callers

Keep using the existing API without changes:

```java
new AnnotationHandler().load(configurationSection, target);
```

`AnnotationHandler` delegates to `AnnotationBinder` through `BukkitConfigView`.
The adapter forwards reads to the original section rather than serializing,
copying or reparsing it. `@ConfigDataConfigurationSection` continues to assign the
original Bukkit section, including when the annotated field is typed as `Object`.
Section identity, edits through that section, Bukkit defaults and path options
are preserved. There is no new `load` overload on `AnnotationHandler`, so existing
`load(null, target)` call sites are still unambiguous.

## New shared callers

A platform supplies the small `ConfigView` read contract and uses:

```java
new AnnotationBinder().load(view, target);
```

The no-argument binder assigns `ConfigView` instances to section-annotated fields;
shared models should declare those fields as `ConfigView`. The optional section
projection constructor exists for compatibility adapters, not for leaking native
server objects into new shared models. It is called only for present sections.

The binder uses the existing annotation types and `ParsedDuration`. Its execution
path has no Bukkit, proxy, mod-loader or Minecraft dependency. The headless test
loads it with only project classes and the JDK and verifies that Bukkit cannot be
loaded. An in-memory test view is included; production Configurate/YAML adapters
and physical module extraction are separate follow-ups.

## Behavior deliberately preserved

This change moves the existing binding body rather than redesigning its rules:

- Only declared fields are processed, in the existing annotation-check order.
- Existing field/annotation defaults, alternate paths and boolean inversion apply.
- Empty lists fall back to alternate paths and then the initialized list object.
- Missing sections become null; missing key sets become empty.
- Per-field exceptions are printed and isolated as before.
- The historical zero-default `ConfigDataLong` reflection behavior is retained:
  a long field initializer is not recovered through `Field.getInt`. Changing it
  would alter existing configurations and is intentionally outside this PR.

`ConfigView` is read-only access, not a promise that its backing data is immutable
or safe to read from arbitrary threads. Each platform must preserve its existing
configuration ownership and thread rules.

## Verification

Run the existing full build:

```sh
mvn -B -f SimpleAPI/pom.xml package
```

Focused regression tests:

```sh
mvn -B -f SimpleAPI/pom.xml \
  -Dtest=AnnotationHandlerCompatibilityTest,AnnotationBinderHeadlessTest test
```

The compatibility tests exercise the real Bukkit configuration API. The headless
fixture deliberately does not depend on JUnit or Bukkit within its isolated
class loader. Before merging, also build AdvancedCore and VotingPlugin against
the locally installed candidate SimpleAPI artifact; no consumer changes should
be required.
