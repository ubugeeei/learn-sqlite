# The chibi-sqlite Book

Build a SQLite-like relational database in Scala 3, one small development at a
time. This is not a tour of a finished codebase. Each chapter begins with a
problem, constructs the smallest useful implementation, tests its boundaries,
and leaves the system in a runnable state.

The teaching style is inspired by [The chibivue Book][chibivue]: start with a
minimal vertical slice so the whole system is visible, then revisit every layer
with progressively more faithful machinery.

## What you will build

```text
SQL source
   │
   ▼
Lexer ──► Parser ──► typed AST ──► Executor
                                      │
                                      ▼
                              Schema / Rows / Values
                                      │
                                      ▼
                               Record serialization
                                      │
                                      ▼
                         Table B-tree / Catalog / Indexes
                                      │
                                      ▼
                         Pager / Journal / WAL / VFS
```

The repository currently implements the solid boxes down through record
serialization, plus an educational fixed-page B+tree. Later parts deliberately
remain visible in the table of contents so readers understand where each
milestone is headed.

## How to read this book

For each chapter:

1. Read the SQLite specification links before the implementation section.
2. Run the focused test command shown in the chapter.
3. Reproduce the small implementation without copying it.
4. Compare with the repository source linked by the chapter.
5. Try the exercises and corruption cases.

Use `scala-cli test .` after every change. The project enables warnings for
unused values, suspicious non-Unit statements, deprecations, and unchecked
operations. Treat those warnings as design feedback.

## Part 0 — Orientation

- [Start here: database foundations](00-database-foundations.md)
- [Glossary](glossary.md)
- [Coverage audit: implemented, partial, and missing](coverage.md)
- [Scope, non-goals, and compatibility](compatibility.md)
- [Roadmap and implementation milestones](roadmap.md)
- [Architecture: from SQL text to durable bytes](architecture.md)

## Part 1 — A Minimal Relational Database

- [1. SQL is a language: tokens, syntax, and ASTs](01-sql-language.md)
- [2. Rows, schemas, storage classes, and expressions](02-relational-core.md)
- [6. Connecting the first vertical slices](06-putting-it-together.md)

At the end of this part you can create a table, insert rows, filter and project
them, and delete them in the in-memory REPL. The point is breadth: see the whole
query lifecycle before specializing any layer.

## Part 2 — Durable Bytes

- [3. The pager and fixed-size I/O](03-pager.md)
- [4. Ordered table B+tree leaves](04-btree.md)
- [7. Varints and SQLite record serialization](07-record-format.md)

This part switches from relational meaning to bytes. Every decoder treats the
file as untrusted input. Tests cover reopen, boundary widths, truncation,
reserved values, invalid UTF-8, and defensive copying.

## Part 3 — A Real Storage Engine (in progress)

- [8. Persistent catalog and file-backed SQL](08-persistent-catalog.md)
- [9. Implementing UPDATE end to end](09-update.md)
- Database header and page-1 special handling
- Free pages and the freelist trunk
- Interior B-tree pages and recursive splitting
- Overflow pages for large records
- `sqlite_schema` and persistent table roots
- Rowid allocation and `INTEGER PRIMARY KEY`

## Part 4 — Transactions (planned)

- [5. Atomic commit and recovery](05-transactions.md)
- Pager cache and dirty-page tracking
- Rollback journal and hot-journal recovery
- Lock states and busy handling
- Write-ahead logging and checkpoints

## Part 5 — Query Compilation (planned)

- Name resolution and type affinity
- Logical plans and physical operators
- A small bytecode virtual machine
- Index scans and cost estimation
- Joins, ordering, grouping, and aggregates

## Part 6 — Compatibility and Hardening (planned)

- Differential tests against `sqlite3`
- Fuzzing parsers and file decoders
- Crash-injection tests
- File-format compatibility fixtures
- JDBC and a stable public API

## Source map

| Domain | Production code | Executable specification |
|---|---|---|
| SQL syntax | `sql/Ast.scala`, `Lexer.scala`, `Parser.scala` | `sql/Parser.test.scala` |
| Relational core | `core/Value.scala`, `Schema.scala`, `Evaluator.scala` | `engine/Database.test.scala` |
| Execution | `engine/Database.scala` | `engine/Database.test.scala` |
| Pages | `storage/Pager.scala` | `storage/Pager.test.scala` |
| B+tree leaves | `storage/TableBTree.scala` | `storage/TableBTree.test.scala` |
| Varints | `storage/Varint.scala` | `storage/Varint.test.scala` |
| Records | `storage/RecordCodec.scala` | `storage/RecordCodec.test.scala` |

[chibivue]: https://book.chibivue.land/00-introduction/010-about.html
