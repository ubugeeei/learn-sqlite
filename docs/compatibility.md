# Scope and compatibility

SQLite is an unusually complete system: SQL compiler, virtual machine, B-tree,
pager, journaling, locking, query planner, and decades of compatibility work.
This repository builds those ideas incrementally and does not claim full SQLite
compatibility.

The canonical references are the [SQL language documentation][lang] and
[database file format][format]. SQLite's public-domain source is the ultimate
reference when prose is ambiguous.

| Area | Initial milestone | Future work |
|---|---|---|
| SQL | `CREATE TABLE`, `INSERT`, `SELECT`, `UPDATE`, `DELETE` | joins, aggregates, DDL changes |
| Types | five storage classes and storage-time affinity | comparison affinity and strict tables |
| Storage | fixed-size pages, ordered table B-tree | byte-compatible SQLite pages |
| Transactions | before-image rollback for mutating backend calls | SQL transactions, WAL, locking |
| API | Scala API and REPL | JDBC driver |

Semantic differences are tested and documented rather than hidden. This makes
the project useful as an implementation guide without pretending to be a safe
replacement for production SQLite.

[lang]: https://www.sqlite.org/lang.html
[format]: https://www.sqlite.org/fileformat.html
