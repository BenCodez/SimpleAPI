# Maintainer and AI-agent guide

SimpleAPI is a shared library consumed by AdvancedCore and other plugins. The repository now uses one Maven project and one source tree with platform-neutral and platform-specific packages; do not recreate the removed experimental submodule build.

## Build and verification

Requirements: JDK 21+ and Maven. The Maven project is in `SimpleAPI/`.

```shell
mvn -B -f SimpleAPI/pom.xml test
mvn -B -f SimpleAPI/pom.xml package
```

Confirm current CI and POM settings before relying on these commands. Verify that the package invocation produced both the legacy/full artifact and every configured classifier, especially the `shared` JAR. Run `git diff --check` and confirm tests actually ran.

## Packaging boundaries

- `com.bencodez.simpleapi.core` contains platform-independent implementations.
- Platform adapters belong under packages such as `com.bencodez.simpleapi.bukkit`; future Forge/Fabric/other adapters must remain isolated from core.
- The full `simpleapi` artifact preserves existing consumers and public package names.
- The `shared` classifier JAR contains only the explicitly selected neutral API. Because a classifier shares the project's ordinary POM, native consumers must exclude its transitives and explicitly declare the neutral dependencies they use, as documented in `docs/shared-libraries.md`.
- Compatibility facades in older package names are intentional. Do not remove, relocate, or narrow them without an explicit migration and downstream verification.
- Previously removed experimental coordinates such as `simpleapi-parent`, `simpleapi-core`, `simpleapi-configurate`, and `simpleapi-sql` are not current build modules.

Core and the shared artifact must not link Bukkit, BungeeCord, Velocity, Minecraft, Forge, Fabric, NeoForge, or other loader-specific classes. Test the packaged JAR, not only source imports: signatures, annotations, superclass references, static initializers, service descriptors, and reflective loading can leak platform dependencies.

## API and configuration compatibility

Treat public signatures, constructors, overloads, generic types, return values, exceptions, callback threading, configuration shapes, and serialized data as compatibility surfaces.

- Preserve legacy YAML key lookup, casing, literal-key behavior, defaults, numeric values, empty/missing distinctions, and copy isolation.
- Keep structured/plain configuration views bounded and cycle-safe.
- Shared configuration paths must reject or adapt native platform objects according to their documented contract; Bukkit adapters may preserve native `ConfigurationSection` behavior where promised.
- Annotation binding must preserve inherited fields, supported value classification, nested traversal, and detached/rootless-section safety.
- Database abstractions must keep connection ownership, transaction boundaries, timeouts, null/closed-connection handling, and shutdown behavior explicit.
- Do not introduce hidden user extraction or Bukkit-only behavior into neutral APIs merely for a storage mode that is scheduled for removal.

## Concurrency, lifecycle, and resources

- Avoid blocking I/O on Bukkit, region, proxy, networking, or event threads.
- Define callback execution context and preserve it across adapters.
- Bound queues, caches, payloads, recursion, retries, and diagnostic output.
- Handle cancellation, interruption, executor rejection, partial initialization, reload, and shutdown without leaked work.
- Optional integrations must not cause class-loading failures when absent.

## Change and PR workflow

Keep changes focused and avoid unrelated formatting. Before any commit, push, PR update, review reply, or other remote change:

1. run relevant focused tests;
2. run the full Maven package build;
3. inspect the newly produced full and shared artifacts;
4. run `git diff --check`;
5. inspect the complete base-to-HEAD diff and affected AdvancedCore/downstream contracts.

For substantive changes, obtain a fresh source-read-only review. The implementation agent verifies and fixes accepted findings, reruns all required checks, and obtains a new review of the updated snapshot. Do not reuse an earlier clean verdict after changes, and do not merge without explicit authorization.

## MEX project memory

For substantive tasks where architecture, compatibility, or prior failures matter, read relevant MEX context and then verify against current Java/tests and formal docs. Use code/tests first, this guide and formal docs second, reviewed MEX third, and historical Relays last; correct stale memory. Skip MEX for trivial edits. MEX 0.8.2 does not index Java here. Use `$mex-inbox` for durable findings and `$mex-relay` for substantial unfinished handoffs.

<!-- mex-agent:skills:start -->
## MEX agent skills
- At the start of every session, read `.mex/AGENTS.md` and `.mex/ROUTER.md` before project work; follow `ROUTER.md` to load only the relevant context.
- Read `mex logging --json` at session start and before optional logging. Its checkout-local advisory mode is `significant` (quiet default: material decisions, risks, blockers, or durable discoveries), `checkpoints` (batch useful notes at task/session boundaries), or `manual` (no unsolicited notes). Skip routine tool calls, edits, repeated status, and empty summaries. Honor explicit user log requests in every mode; never suppress mandatory workflow Activity or recovery audit records. Report a policy read failure instead of guessing or changing the preference.
- When earlier work may inform the task, retrieve bounded relevant notes with `mex timeline --query "subject phrase" --file src/example.ts --limit 10 --json`, using the known subject or exact recorded file path, or both. Treat matches as historical evidence, not accepted current knowledge; verify conclusions before reuse or explicit promotion with their source retained.
- Use `$mex-inbox` for explicit contributions to project knowledge and `$mex-relay` for durable team handoffs. Invoke them automatically when intent clearly matches; ordinary GROW upkeep remains available without Inbox.
- When MEX context materially helps your work, mention MEX and the relevant finding naturally in your explanation. Tie the mention to what it helped you understand, decide, or verify. Avoid fixed phrases, standalone acknowledgements, repeated mentions, or narrating routine context loading. This replaces older MEX instructions requiring a fixed acknowledgement or context-loading narration.
- Do not claim an author, date, or historical event unless the retrieved data actually provides it.
- After a MEX write, say exactly what changed and its sharing boundary: a local draft is checkout-only and nothing is shared; a canonical artifact is written to the working tree and requires commit/push to share.
- Skill activation is not approval for canonical actions.
<!-- mex-agent:skills:end -->
