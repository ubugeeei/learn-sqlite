# 8. From Components to a Persistent SQL Database

The previous chapters built correct-looking components in isolation. That is useful for learning,
but not yet a database: the SQL executor stored rows in a `Vector`, while the B-tree stored opaque
bytes that no SQL statement could reach.

This chapter removes that lie. At the end, the following shell session survives process restart:

```sh
scala-cli run . -- repl ./people.db

lsql> CREATE TABLE people (id INTEGER PRIMARY KEY NOT NULL, name TEXT NOT NULL);
created people
lsql> INSERT INTO people VALUES (1, 'Ada'), (2, 'Grace');
2 row(s) modified
lsql> .quit

scala-cli run . -- repl ./people.db
lsql> SELECT name FROM people WHERE id >= 2;
name
Grace
```

The path through the system is now real:

```text
CREATE TABLE
  -> Parser
  -> Database
  -> FileBackend.create
  -> allocate table root
  -> encode CatalogEntry as a record
  -> insert catalog record into page-0 tree
  -> force pager

INSERT
  -> validate every row
  -> RecordCodec.encode
  -> TableBTree.insert
  -> Pager.write + force

reopen + SELECT
  -> validate file header
  -> open catalog root
  -> decode schema and table root
  -> scan table B-tree
  -> RecordCodec.decode
  -> Row.checked
  -> evaluate WHERE and projection
```

## 8.1 Give every table a stable root

The first B-tree implementation assumed page zero was always its root. That supports exactly one
tree. A database needs at least one catalog tree plus one tree for every table and index.

Introduce two operations:

```scala
def create(pager: Pager): Either[StorageError, (PageId, TableBTree)]
def at(pager: Pager, root: PageId): Either[StorageError, TableBTree]
```

`create` allocates and initializes a leaf. `at` validates that the referenced page is a leaf before
returning a handle. The root page id is stable metadata: the catalog can store it once, and later
opens can reconstruct the tree without scanning the file.

The test creates two roots with the same rowid and different values. Reopening each root must return
only its own value. This catches the common mistake of retaining an implicit global root.

## 8.2 Design the minimum catalog record

SQLite's [`sqlite_schema` table][schema-table] stores object type, name, owning table, root page, and
original SQL. Our private catalog needs enough information to reconstruct the typed `Schema` without
executing arbitrary DDL during open:

```scala
final case class CatalogEntry(
  name: Identifier,
  columns: Vector[ColumnDefinition],
  rootPage: PageId
)
```

The encoded record is deliberately boring:

```text
table-name | root-page | column-count |
  column-name | declared-type-or-NULL | primary-key | nullable | ...
```

Each field is an ordinary `Value`, so the record codec from chapter 7 does all byte-level work. A
catalog-specific decoder validates the shape:

- root page must fit a non-negative `Int`;
- column count must match exactly four fields per column;
- name and declared type must be text;
- flags must be integers;
- malformed records fail the whole catalog read.

Failing the entire open is important. Silently skipping a corrupt schema entry could expose some
tables and hide others, which looks like data loss.

## 8.3 Put the catalog at the first tree root

On a fresh file, `Catalog.open` calls `TableBTree.open`. That operation allocates page zero. On an
existing file, it validates and reopens page zero. User tables are allocated afterward, so they can
never collide with the catalog root.

Catalog rowids increase monotonically. Names are compared through `Identifier.normalized`, matching
the case-insensitive lookup behavior used by the SQL layer.

Current limitation: dropping tables is not implemented, so catalog rowids and roots are never
reused. The freelist milestone will reclaim both catalog entries and unreachable pages.

## 8.4 Introduce a storage boundary

Copying the SQL executor into a “durable version” would guarantee semantic drift. Instead, extract
only the operations the executor needs:

```scala
trait Backend extends AutoCloseable:
  def create(name: Identifier, columns: Vector[ColumnDefinition]): Either[String, Unit]
  def table(name: Identifier): Either[String, StoredTable]
  def flush(): Either[String, Unit]

trait StoredTable:
  def schema: Schema
  def rows: Either[String, Vector[Row]]
  def append(rows: Vector[Row]): Either[String, Unit]
  def replace(rows: Vector[Row]): Either[String, Unit]
```

The early `MemoryBackend` remains valuable: parser and semantic tests run without filesystem noise.
`FileBackend` composes catalog, codec, tree, and pager. `Database` is unchanged above that boundary,
so NULL behavior, constraints, and projections are identical for both backends.

This is an intentionally row-oriented interface. A later query-planning milestone will replace
materialized `Vector[Row]` reads with cursors and operators so large tables can stream.

## 8.5 Open a stored table

`FileBackend.table` performs four validation steps:

1. case-insensitively find the catalog entry;
2. reconstruct and validate `Schema`;
3. open the stored root page as a B-tree;
4. return a `StoredTable` handle that owns neither parser nor evaluator logic.

Reading scans rowid/payload cells, decodes every record, then calls `Row.checked`. Rechecking the
schema on read may seem redundant, but disk bytes are not trusted. A corrupt NULL in a NOT NULL
column should be reported at the boundary rather than flowing into query evaluation.

## 8.6 Make multi-row validation all-or-nothing

Consider:

```sql
CREATE TABLE users (id INTEGER NOT NULL);
INSERT INTO users VALUES (1), (NULL), (3);
```

The executor prepares and validates every row before calling `StoredTable.append`. Therefore the
invalid second row prevents the backend from seeing any part of the batch. An integration test
reopens the database and verifies that the table remains empty.

This gives statement atomicity for semantic failures. It does **not** yet give crash atomicity: an
I/O failure after one B-tree page write can still leave a partial batch. That requires the rollback
journal from the transaction part of the book.

## 8.7 Durable deletion without a freelist

The current table tree has ordered insert and scan, but no cell-level rebalance. `DELETE` therefore
uses `replace`:

1. evaluate the predicate over all rows;
2. retain rows whose predicate is not true;
3. clear the stable root leaf;
4. reinsert retained rows with new sequential rowids;
5. force the pager.

The stable root means catalog metadata does not change. Old continuation pages become unreachable.
This is logically correct and restart-safe but leaks file space. It is an explicit stepping stone,
not a production deletion algorithm. The freelist and B-tree rebalancing chapters will remove it.

## 8.8 Resource ownership

`FileBackend.open` either returns a fully validated backend or closes the file descriptor before
returning an error. `Database.close` closes its backend. The CLI uses `try/finally` around the REPL.

This ownership rule prevents subtle test failures on Windows and descriptor exhaustion in servers:

```scala
val backend = FileBackend.open(path).fold(error => fail(error), identity)
val database = Database(backend)
try use(database)
finally database.close()
```

## 8.9 Declarative integration tests

The end-to-end suite states behavior in terms of SQL and reopen boundaries, not implementation
methods:

- schema and rows survive close/reopen;
- two table roots stay isolated;
- delete remains visible after another reopen;
- a semantically invalid batch appends no prefix.

Table-driven expectations make adding cases cheap:

```scala
val expectations = Vector(
  "SELECT * FROM users" -> Value.Text("user-row"),
  "SELECT * FROM posts" -> Value.Text("post-row")
)

expectations.foreach: (sql, expected) =>
  val result = database.execute(sql)
  // assert the complete result shape
```

Run the focused suite:

```sh
scala-cli test . --test-only learnsqlite.storage.FileBackendSuite
```

## 8.10 What is practical now, and what is not

The database now provides genuine restart persistence, multiple tables, schema reconstruction,
record serialization, filtering, projection, insertion, and deletion through one CLI.

It is still not suitable for production data:

- no rollback journal or crash recovery;
- no file locking or concurrent writers;
- no interior B-tree pages, so lookup remains linear across leaves;
- deleted overflow leaves are leaked;
- no maximum payload guard before page encoding;
- no indexes, planner, joins, aggregation, or update;
- private pages are not byte-compatible with SQLite database files.

The next meaningful milestone is not more SQL syntax. It is a transactional pager: cached pages,
before-images, journal sync ordering, fault injection, and hot-journal recovery. Until that exists,
the file format should be treated as rebuildable experimental data.

[schema-table]: https://www.sqlite.org/schematab.html

