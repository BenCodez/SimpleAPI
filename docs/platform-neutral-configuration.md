# Platform-neutral configuration binding

The shared binder implementation lives in `com.bencodez.simpleapi.core.config`.
Bukkit adapters live in `com.bencodez.simpleapi.bukkit.config`. Existing callers can
continue using `com.bencodez.simpleapi.file.annotation.AnnotationHandler` and
`AnnotationBinder`; those classes delegate without changing their public signatures.
There is still no ambiguous ConfigView overload on the Bukkit handler.

The existing annotation types and neutral configuration contracts keep their
original package names. Bukkit section fields receive their original native
`ConfigurationSection` objects, including when typed as Object. Shared callers
use `ConfigView` fields. Defaults, alternate paths, list fallbacks, declared-field
traversal, annotation ordering and per-field exception isolation are unchanged.
The historical zero-default long reflection behavior is deliberately preserved.

Build and test the one project with:

```sh
mvn -B -f SimpleAPI/pom.xml clean package
```

The same POM attaches a platform-neutral `shared` JAR; there are no child modules.
See `shared-libraries.md` for dependency exclusions, package compatibility,
configuration persistence guarantees, and packaged headless verification.
