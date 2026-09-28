# SimpleAPI security threat model

This document defines the repository-specific threat model for security review and Codex Security scans. Read it with current source, tests, and AGENTS.md. SimpleAPI is a shared library: it often has no direct attacker-facing entry point, but its primitives sit underneath AdvancedCore and other plugins that do.

The goal is to identify unsafe shared primitives and trust-boundary violations without treating every generic API or compatibility bug as a vulnerability.

## Security objectives

SimpleAPI provides platform-neutral and Bukkit-oriented helpers for SQL, configuration/files, serialization, scheduling, messaging/communication, items/GUI support, reflection/compatibility, and common utilities.

Important properties are:

1. Lower-trust values supplied by consuming plugins must not become SQL syntax, filesystem paths, config structure, commands, or unsafe serialized structure unexpectedly.
2. SQL helpers must separate values from identifiers and preserve connection, TLS, and transaction semantics.
3. Generic file/config helpers must not claim containment or safety they do not actually enforce.
4. Server-communication helpers must distinguish confidentiality from authentication and must not silently trust source/origin.
5. Shared serializers and parsers must reject ambiguity when the result is used for authorization, routing, or persistent state.
6. Public APIs must have explicit thread, ownership, and lifecycle semantics so consumers do not accidentally create races or resource leaks.
7. Platform-neutral/shared artifacts must not load platform-specific classes unexpectedly.
8. Attacker-influenced queues, recursion, payloads, retries, caches, and diagnostics must remain bounded where the API promises safe handling.

## Trust boundaries

### Lower-trust input

SimpleAPI callers may pass:

- player names, UUID-like values, commands, chat/placeholder output, GUI values, vote-service data, proxy/plugin-message data, Redis/socket payloads, and database rows;
- table, column, or data keys derived by a downstream plugin from player or remote values;
- YAML keys/values and filenames selected indirectly by users;
- serialized map/list/string data read from persistent or remote stores;
- remote host, port, or payload values in server-communication wrappers.

Treat persisted data as tainted if a lower-trust source could have written it earlier.

### Trusted callers and operators

A malicious installed plugin already shares the JVM and can usually bypass SimpleAPI entirely. An operator intentionally selecting an arbitrary database, file, network endpoint, or config value is also privileged.

Security review should focus on benign consumers passing lower-trust data into a helper whose contract suggests safe handling, or on helpers directly used by exposed higher layers.

### Remote peers and on-path network observers

Choosing a host, broker, database, or socket endpoint is an operator decision, but that does **not** make the selected remote peer, DNS/network path, or other hosts/tenants able to observe or modify traffic trusted. Treat an on-path observer and a compromised/malicious remote service as lower-trust actors unless the selected transport provides and successfully verifies the required confidentiality and peer/message authentication.

Review the guarantee transport by transport:

- HTTP transport is the strong authenticated case: private-CA TLS, certificate/hostname/pin checks, enrolled peer identity, bounded protocol messages, and replay/delivery state should protect against passive and active network observers when those checks succeed.
- Redis supports optional TLS. The constructor defaults to `ssl=false`; username/password authentication over plaintext does not protect credentials or payloads from an on-path observer. When TLS is enabled, hostname identification must remain active.
- MQTT accepts caller-supplied broker URLs/options and the convenience username/password constructor does not itself require TLS. The security of credentials and payloads therefore depends on the selected MQTT scheme/options and broker configuration.
- Raw socket transport has no intrinsic peer identity. Legacy `EncryptionHandler` AES provides optional confidentiality but does not by itself provide authenticated encryption, sender identity, or replay protection.
- MySQL/MariaDB `UseSSL` may provide encryption, but the repository does not treat that flag alone as a promise of hostname/certificate identity verification. PostgreSQL `VERIFY_FULL` is the explicit verified-TLS mode for that driver.

Do not classify intentional plaintext/private-network compatibility as an authentication bypass by itself. Do report silent downgrade from an explicitly selected secure mode, credential disclosure where the API/configuration claims protection, peer-identity verification bypass, or code that treats an unauthenticated/plaintext channel as stronger than its documented guarantee.

### HTTP enrollment bootstrap

The copy/paste HTTP connection code is bootstrap trust material. Its embedded MAC is keyed by the token contained in the same code, so it detects corruption but does **not** authenticate wholesale replacement of the code. Initial endpoint and certificate trust therefore depends on the operator receiving the complete connection code through an authentic and confidential administrative channel.

Treat interception, replacement, relay, disclosure, replay-before-consumption, and expiry handling of the connection code as part of this out-of-band bootstrap boundary. Do not blame the HTTP transport for an attacker who already controls that trusted delivery channel, but do report cases where the implementation accepts an expired/replayed/wrong-server code, fails to bind the resulting certificate to the advertised identity, or leaks the code/token outside that channel.

### Local OS principals and persisted private state

Other local OS accounts/processes that do **not** already run as the Minecraft server account are a distinct lower-trust boundary for **all persisted cryptographic secrets and credentials**, not only the HTTP transport. Same-UID malicious plugin code is outside meaningful filesystem isolation, but unrelated local principals should not be able to read CA/server/client private keys, credential passwords, enrollment state, active/staged credential generations, socket/shared-transport AES keys, or other reusable secrets that protect message confidentiality or authentication.

Review owner-only permissions, no-follow/symlink checks, atomic publication, durability, staged-generation cleanup, rotation/revocation, and failure behavior. A regression that broadens private-file or private-directory permissions, publishes sensitive data before permissions are enforced, leaves superseded private generations readable indefinitely, or silently continues when owner-only permissions cannot be proven crosses this boundary.

Current HTTP credential paths use `PrivateFilePermissions` and related no-follow checks. The legacy `com.bencodez.simpleapi.encryption.EncryptionHandler.save` path is a separate review target: it writes its AES key with ordinary `FileWriter` semantics and does not currently establish the same owner-only guarantee. Do not let the stronger HTTP storage controls imply that this or other non-HTTP secret stores are equally protected.

## Current controls to preserve

Current master already includes controls that older findings may predate:

- SQL value paths commonly use PreparedStatement;
- AbstractSqlTable provides identifier quoting and driver-aware SQL helpers;
- PostgreSQL has explicit TLS modes, including VERIFY_FULL for certificate and hostname verification;
- HTTP TLS identity, client credential, enrollment-state, and durable-delivery paths use `PrivateFilePermissions` plus no-follow/owner-only checks where private state is persisted; this control is path-specific and does not cover every persisted secret (for example the legacy `EncryptionHandler` AES key path);
- neutral/shared packaging has explicit platform-isolation expectations;
- concurrency and lifecycle contracts are documented in AGENTS.md.

Look for bypasses, sibling paths that do not use the same control, and configuration interactions that silently weaken an explicitly selected hardened mode.

## SQL and database security

SQL is one of the highest-value shared surfaces.

### Values, identifiers and raw SQL

Bind lower-trust values with prepared statements.

Treat table names, prefixes, column names, type/default expressions, ORDER BY fragments, database names, and DDL as syntax requiring strict construction, quoting, or allowlisting.

Quoting is not an allowlist for every SQL grammar position. Type/default expressions or raw fragments may need a much narrower parser.

Search for:

- one database/driver path concatenating a value while others bind;
- unsafe dynamic identifiers in CREATE, ALTER, UPDATE, or DELETE;
- MySQL, MariaDB, PostgreSQL, or SQLite differences that turn a safe helper into raw syntax on one backend;
- async schema migration racing normal reads/writes;
- failed migration leaving a field/type in a permissive or misleading default state;
- connection errors converting an integrity or authorization read into a success/default;
- connection leaks or retry loops under attacker-triggerable query load;
- transaction helpers committing partial multi-step state.

Generic raw-query APIs are trusted-power APIs. Do not report their existence alone.

### PostgreSQL TLS

SimpleAPI has explicit PostgreSQL TLS modes. VERIFY_FULL is the hardened mode for certificate and hostname verification, while legacy or REQUIRE-style behavior exists for compatibility.

Do not repeatedly report the existence of encryption-only compatibility behavior as a new vulnerability. Instead test:

- whether an explicit VERIFY_FULL selection can be downgraded by legacy UseSSL handling;
- whether appended JDBC parameters can override security-sensitive parameters after the library sets them;
- whether parameter ordering changes the final effective security mode;
- whether hostname/certificate verification is actually active in the final JDBC URL;
- whether credentials leak to logs on failed connection;
- whether migration from legacy settings changes security unexpectedly without operator intent.

A bypass of explicitly selected VERIFY_FULL is security-relevant. Choosing a weaker compatibility mode deliberately is not the same thing.

## Files, YAML and configuration

SimpleAPI contains generic file/config primitives used by higher-level plugins. A helper accepting a File from a trusted caller is not automatically responsible for sandboxing the filesystem.

Security findings require either a helper that promises containment or safe-name behavior and can be bypassed, or a realistic downstream path feeding lower-trust names/paths into it.

Review:

- relative and absolute path handling;
- parent traversal, separator variants, Windows drive/UNC paths, and Unicode normalization;
- symlink following and check-then-use races;
- atomic replace and backup semantics;
- YAML keys containing dots or separators altering unintended hierarchy;
- copying/default-merge behavior that unexpectedly overwrites secrets or permissions;
- configuration recursion and cycle handling;
- serialization of platform-native objects into supposedly neutral formats;
- logs/errors exposing full paths or sensitive configuration.

Do not classify "a trusted plugin can ask a generic file helper to write any file it chooses" as arbitrary-file-write without a lower-trust path or documented containment promise.

## Serialization and parser ambiguity

Utility encodings are often reused beyond their original purpose.

Search map/list/string codecs and delimiter-based formats for:

- ambiguous delimiters;
- trailing empty fields lost during splitting;
- escaping that is not reversible;
- duplicate keys changing authorization or routing meaning;
- unbounded recursion or nesting;
- parse/serialize mismatch;
- attacker-controlled data turning into additional fields or entries.

Severity depends on the consumer. A broken round-trip with no security-sensitive caller is a correctness bug. Escalate when the parsed result controls permissions, routing, commands, SQL, or persistent ownership.

## Server communication and message origin

SimpleAPI contains reusable communication primitives used by higher-level plugins.

Do not assume an operator-selected endpoint is trustworthy merely because it is configured, and do not assume encryption means authentication. Include both malicious remote peers and on-path network observers in the analysis, then determine the exact confidentiality, peer-identity, message-integrity, and replay guarantee of each helper.

Search for:

- unauthenticated messages exposed through an API that consumers reasonably treat as trusted;
- sender/origin identifiers supplied only by the payload without channel binding;
- replay or duplicate delivery;
- plaintext credential or payload exposure to on-path observers where a secure mode was expected;
- confidentiality without integrity/authentication;
- key reuse across protocol domains;
- unbounded payload/message queues;
- reconnect or reload creating duplicate listeners;
- callbacks after close/disable;
- malformed payloads crossing directly into command, config, or deserialization helpers.

If authentication is intentionally the consuming plugin's responsibility, document that boundary rather than inventing a missing SimpleAPI contract.

## Concurrency, scheduling and lifecycle

SimpleAPI is reused on Bukkit/Paper/Folia, proxies, and neutral/native contexts.

Review public APIs for explicit callback context and ownership. Search for:

- blocking SQL/network/file work on platform event or region threads;
- Bukkit/world/entity access from arbitrary async workers;
- executor rejection silently dropping accepted work;
- reload/shutdown leaking workers, sockets, timers, or callbacks;
- old-runtime callbacks mutating replacement state;
- unbounded task submission;
- cancellation/interruption swallowed and later reported as success;
- synchronized callbacks invoking external consumer code while internal locks remain held.

A thread-policy difference is security-relevant only when there is a credible reachable integrity or availability effect.

## Shared artifact and classloading boundary

The shared or neutral artifact must remain free of unintended Bukkit, BungeeCord, Velocity, Minecraft, Forge, Fabric, NeoForge, or other loader-specific linkage as documented by the repository.

This is primarily compatibility and runtime isolation, not attacker security. Escalate only when classloading, reflection, or service descriptors create unintended privileged code execution or expose platform-only capabilities to lower-trust data.

Otherwise classify accidental platform linkage as compatibility or packaging.

## Reflection and dynamic loading

Reflection and class-name utilities are powerful shared primitives.

Search for lower-trust input reaching:

- Class.forName or equivalent;
- constructors or method names;
- provider/implementation loading;
- enum/value reflection used to select privileged behavior;
- arbitrary method dispatch based on serialized config.

A trusted caller choosing a class or reflection target is not RCE by itself. The security boundary is lower-trust control over the target or arguments.

## Resource exhaustion

Because SimpleAPI sits under other plugins, persisted/database data can be attacker-influenced even if a helper has no socket.

Prioritize:

- unbounded SQL result materialization;
- connection-pool starvation;
- queue/cache cardinality controlled by arbitrary keys;
- recursive config/object conversion;
- huge strings or serialized collections;
- reconnect/retry loops;
- per-item or per-row task creation;
- log amplification.

Require a realistic caller path and concrete resource effect.

## Secrets, private files, logging and errors

Database passwords, tokens, private keys, reusable encryption keys, Authorization-like values, enrollment codes, and JDBC URLs containing credentials must not appear in routine logs or exceptions returned to lower-trust callers.

For HTTP transport state, review `HttpTlsIdentity`, `HttpClientCredentialStore`, `HttpEnrollmentAuthority`, durable delivery state, and `PrivateFilePermissions` together. Private files/directories should remain owner-only; symlinks and unsafe file types must be rejected where promised; temporary/staged generations must receive safe permissions before sensitive bytes are written; activation/rotation must not briefly expose weaker permissions; cleanup must not accidentally delete or retain the wrong active generation; and restart/recovery must re-validate permissions rather than trusting prior creation.

Apply the same local-principal reasoning to non-HTTP cryptographic material. In particular, the AES key persisted by `EncryptionHandler` is reusable secret material even though that legacy helper does not provide the HTTP stack's owner-only persistence guarantees. Review creation-time permissions, existing-file revalidation, symlink/file-type handling, key rotation, cleanup, and whether disclosure would let another local principal decrypt or forge protected traffic.

Connection diagnostics may include host/database identifiers where operationally useful, but avoid full credential-bearing URLs and raw sensitive configuration.

## Supply chain, dependency provenance and compatibility

Supply-chain review covers both CI privileges and the provenance of build inputs that become part of published artifacts.

CI findings matter when untrusted PR-controlled code receives write-capable repository credentials, can poison trusted caches or artifacts, or can modify releases.

Also review Maven repository and dependency trust because SimpleAPI resolves artifacts from multiple configured repositories and shades compile-scope dependencies into distributed JARs. Relevant targets include repository compromise, dependency substitution/confusion, mutable or replaced `SNAPSHOT` artifacts, vulnerable or malicious transitives, mismatched checksums/signatures where provenance controls exist, and release builds resolving bytes different from those previously reviewed or tested.

Do not automatically classify every external repository or intentional development `SNAPSHOT` as a vulnerability. Severity should depend on whether a lower-trust or compromised upstream can alter the bytes used in a trusted build/release, whether those bytes are shaded or executed at runtime, and whether reproducibility/provenance controls would detect the substitution.

SimpleAPI strongly values drop-in and API compatibility. Do not classify public signature changes, classifier/package regressions, config defaults, or platform leakage as security unless they cross a real trust boundary.

## High-value attack stories

1. Pass attacker-controlled strings through every SQL value API and dynamic identifier API across SQLite, MySQL/MariaDB, and PostgreSQL.
2. Configure PostgreSQL VERIFY_FULL, then try parameter ordering and legacy-setting combinations that downgrade the final JDBC URL.
3. Feed traversal, separator, symlink, and normalization variants through helpers that claim safe child-path or contained-file behavior.
4. Round-trip strings containing every delimiter, escape, and trailing-empty case through shared serializers, then use the result in a security-sensitive consumer.
5. Reload or close a communication helper while messages and reconnects are in flight and verify single-listener ownership.
6. Saturate SQL pools or async queues through a bounded lower-trust caller and verify failure is bounded and fail-safe.
7. Trigger config/object conversion on cyclic, deeply nested, or very large data.
8. Pass lower-trust class, method, or provider names through reflection and dynamic-loading helpers.
9. Shut down while file, SQL, or network tasks are accepted and verify no stale callback mutates replacement state.
10. Package the neutral/shared artifact and inspect signatures, annotations, static initializers, and service descriptors for unexpected platform linkage.
11. Replace, relay, replay, or disclose an HTTP connection code before enrollment and verify the implementation relies only on the explicitly trusted out-of-band delivery channel, code expiry, single-use semantics, identity binding, and pinned certificate data.
12. Create/load every persisted cryptographic secret under permissive umask and mixed local-account conditions; verify paths that promise private storage actually enforce it, and identify legacy paths such as `EncryptionHandler` that do not.
13. Rebuild from the same source while varying Maven repository availability, mutable snapshot contents, and transitive resolution; verify reviewed/released artifacts cannot silently substitute different shaded runtime bytes without detection.
14. Place an active network observer or malicious endpoint between each non-HTTP transport and its configured peer; verify plaintext/optional-TLS modes are classified according to their real guarantees, secure modes cannot silently downgrade, credentials are protected when promised, and unauthenticated encryption is never mistaken for peer identity or message authenticity.

## Scan calibration and severity

Critical: ordinary-player or remote input reaches arbitrary JVM code execution, arbitrary host/plugin file write, or SQL syntax capable of modifying unrelated data through a SimpleAPI helper contract.

High: realistic lower-trust SQL injection; authentication/origin bypass in a transport that promises authenticated messages; TLS/peer-identity verification bypass despite an explicitly selected verified mode; exposure of reusable network credentials or cryptographic keys to an on-path observer when the configured mode promises their protection; exposure of reusable cryptographic keys or HTTP client/server credentials to an unrelated local principal where private storage is expected; repeatable cross-user/state corruption or resource exhaustion affecting the server.

Medium: parser/serialization ambiguity with a security-sensitive consumer; bounded but practical database or queue DoS; meaningful credential disclosure to limited readers; lifecycle races causing occasional duplicate or lost privileged operations.

Low: defense-in-depth hardening, minor log/path disclosure, compatibility-only TLS legacy behavior, generic dependency-hygiene concerns without a concrete artifact-substitution path, or generic API misuse requiring a fully malicious installed plugin.

Usually not security by itself: API/ABI/classifier regressions, malformed trusted config, a trusted caller choosing arbitrary files, SQL, or endpoints, delimiter bugs with no security-sensitive consumer, or accidental platform linkage without a trust-boundary effect.

For every finding identify the consuming path, lower-trust source, helper contract, final security-sensitive sink, and whether current master still exposes the issue.
