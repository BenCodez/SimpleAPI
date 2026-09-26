# PostgreSQL TLS configuration

Set `DbType: POSTGRESQL` and choose `PostgreSqlTlsMode` in the SQL configuration section. The same key works with the Bukkit, Bungee, Velocity, and platform-neutral configuration adapters.

| `PostgreSqlTlsMode` | PostgreSQL JDBC setting | Behavior |
| --- | --- | --- |
| omitted or `LEGACY` | Existing `UseSSL` behavior | `UseSSL: true` sets `sslmode=require`, which encrypts without checking server identity. `UseSSL: false` leaves `sslmode` unset, so the driver's default applies. |
| `VERIFY_FULL` | `sslmode=verify-full`, `gssEncMode=disable` | Encrypts and verifies the server certificate and hostname. |
| `REQUIRE` | `sslmode=require`, `gssEncMode=disable` | Explicit TLS encryption-only compatibility mode; no server identity verification. |
| `DISABLE` | `sslmode=disable` | Explicitly disables PostgreSQL TLS. |

To migrate an existing PostgreSQL connection to identity verification:

```yaml
DbType: POSTGRESQL
Host: db.example.com
PostgreSqlTlsMode: VERIFY_FULL
Line: sslrootcert=/path/to/ca.pem
```

Use a hostname covered by the server certificate and provide a trusted CA certificate as needed. `VERIFY_FULL` fails the connection if verification fails. An explicit mode takes precedence over `UseSSL`. Existing configurations retain their current behavior until a mode is selected. See the [pgJDBC connection parameters](https://jdbc.postgresql.org/documentation/use/#connection-parameters) for certificate and hostname requirements.

When a mode other than `LEGACY` is selected, `Line` cannot set `sslmode`, `ssl`, `sslfactory`, or `sslhostnameverifier`, including differently cased or encoded parameter names. `REQUIRE` and `VERIFY_FULL` also reject `gssEncMode` in `Line` and disable GSS encryption negotiation, so the selected TLS policy is used. Other options such as `sslrootcert` remain available. `LEGACY` keeps existing `Line` handling for compatibility.

`PostgreSqlTlsMode` applies only to PostgreSQL. MySQL and MariaDB connections continue to use their existing `UseSSL` behavior; setting a non-legacy PostgreSQL mode for those drivers is a configuration error. Their `UseSSL` flag alone does not promise hostname verification.
