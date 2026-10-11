# SQL initialization lifecycle

SimpleAPI SQL startup is fail-closed. `ConnectionManager.open()` retains the
original initialization exception without printing credentials, JDBC URLs, or
stack traces. `MySQL.connect()` exposes that cause through
`getLastConnectFailure()`. Legacy `connect(config)` callers retain their query
executor so a transient failure can recover through the existing connection-manager
retry path. Owners must call `disconnect()` when abandoning the SQL wrapper.

`AbstractSqlTable` connection and database-selection setup do not invoke
subclass logging callbacks. A failed pool or MySQL/MariaDB database-selection
(`USE`) query throws an `IllegalStateException` carrying the original cause;
table initialization and subsequent SQL queries are skipped, and the aborted table
constructor disconnects its owned pool and query executor. Deferred table
construction keeps its existing contract: callers may assign subclass state
and invoke `init()` after construction. The legacy non-deferred constructors
still initialize the table using subclass schema hooks; subclasses whose hooks
need instance fields should use the existing deferred constructor. Successful
external `MySQL.connect(config)` calls retain their debug diagnostics.

Disconnect attempts both connection-pool closure and worker shutdown. A pool-close
failure remains the primary exception; any independent worker-shutdown failure is
attached as suppressed evidence. Worker retirement is still attempted after failed
pool cleanup, including during aborted table construction. Normal shutdown remains
graceful and does not interrupt accepted worker tasks.
