# 6. Putting the pieces together

At this point there are two deliberately separate vertical slices:

```text
SQL text -> Lexer -> Parser -> AST -> Database -> Values and Rows
file     -> Pager -> ordered TableBTree leaves -> byte payloads
```

This boundary is useful while learning. The relational slice can be tested
without I/O, and storage corruption can be tested without parsing SQL. The next
milestone connects them through a record codec and a catalog. Do not make the
SQL executor call `FileChannel` directly: that destroys both testability and
the pager's ownership of durability.

## Implement the next milestone yourself

### Step 1: encode a record

Read SQLite's [record format][record]. Implement unsigned varints first and test
every boundary (127/128, 16,383/16,384, and `Long.MaxValue`). Then map each
`Value` to a serial type and payload. A decoder must reject truncated input and
impossible lengths; database bytes are untrusted input even on a local machine.

```scala
trait RecordCodec:
  def encode(values: Vector[Value]): Either[StorageError, Array[Byte]]
  def decode(bytes: Array[Byte]): Either[StorageError, Vector[Value]]
```

### Step 2: create the catalog

SQLite stores its schema in the `sqlite_schema` table. Read the
[schema-table specification][schema]. Reserve the root B-tree for catalog rows
containing table name, original `CREATE TABLE` SQL, and the table root page.
On open, scan the catalog, parse the saved SQL, and reconstruct each `Schema`.

### Step 3: choose row ids

For tables without an `INTEGER PRIMARY KEY`, assign one more than the largest
row id. For the aliasing rules and edge cases, follow [ROWID tables][rowid]. A
real implementation must handle overflow and random positive candidates.

### Step 4: make commits atomic

The existing `Pager.force()` provides durability, not atomicity. Add a rollback
journal before wiring writes into `Database`. The commit sequence is:

1. copy every page that may change into the journal;
2. force the journal and its directory entry;
3. write and force database pages;
4. delete the journal.

Recovery restores a valid journal before reading the catalog. Inject failures
after every step in tests. SQLite's [atomic commit document][atomic] explains
why ordering and flush barriers matter.

## Testing strategy

Use three complementary layers:

- Example tests pin SQL behavior and diagnostics.
- Property tests generate insertion orders, values, and page splits.
- Differential tests run supported statements against the `sqlite3` CLI and
  compare logical results, explicitly excluding documented incompatibilities.

Never compare only happy-path output. Reopen after every storage scenario,
mutate returned byte arrays to detect aliasing, and corrupt each length/count
field to ensure decoding fails safely.

[record]: https://www.sqlite.org/fileformat.html#record_format
[schema]: https://www.sqlite.org/schematab.html
[rowid]: https://www.sqlite.org/rowidtable.html
[atomic]: https://www.sqlite.org/atomiccommit.html

