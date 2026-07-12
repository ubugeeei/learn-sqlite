# Roadmap and implementation milestones

SQLite is not merely a SQL parser backed by a map. It is a compiler, virtual
machine, transactional page cache, two B-tree variants, file format, locking
protocol, planner, and compatibility project. Building all of that in one leap
produces code that is hard to understand and nearly impossible to verify.

We instead repeat small developments. Every milestone has a demo, a durability
claim, and an explicit list of lies that later milestones will remove.

## M0: one in-memory table

Goal: understand the lifecycle of a statement.

- Hand-build a schema and rows.
- Evaluate a column reference and equality expression.
- Filter and project rows.

Lie: there is no parser, catalog, or disk.

## M1: a small SQL frontend — implemented

Goal: turn text into typed data with useful errors.

- Positioned lexer with comments, quoted names, and escaped strings.
- Recursive-descent parser with precedence.
- Closed Scala 3 enums for statements and expressions.
- `CREATE TABLE`, `INSERT`, `SELECT`, and `DELETE`.

Lie: the grammar is intentionally a small subset of SQLite SQL.

## M2: relational execution — implemented

Goal: execute ASTs without coupling semantics to persistence.

- Five storage classes.
- Ordered schemas and case-insensitive lookup.
- `NOT NULL`, arithmetic, comparisons, and three-valued predicates.
- Statement-level validation before mutation.

Lie: rows disappear when the process exits.

## M3: pages and ordered leaves — implemented

Goal: make durable I/O and ordered storage tangible.

- Private magic header so files cannot be mistaken for SQLite databases.
- Power-of-two fixed pages and whole-page writes.
- Ordered leaf cells, splitting, scans, lookup, and reopen.

Lie: leaves form a linked B+tree level; there are no interior nodes yet.

## M4: SQLite records — implemented

Goal: represent logical rows as compact, specified bytes.

- Exact 1–9 byte SQLite varints.
- Serial types 0–9 and 12+.
- Minimal-width signed integers, UTF-8 text, and blobs.
- Strict corruption checks.

Lie: these records live inside our educational leaf page layout, not yet an
exact SQLite B-tree page.

## M5: persistent catalog — implemented

Goal: reopen a SQL database, discover tables, and execute queries.

- Reserve page 0 for catalog records.
- Store original `CREATE TABLE` text and a root page id.
- Decode catalog rows and rebuild schemas on open.
- Connect row records to the SQL executor behind a storage trait.

Demo: create and populate a database, exit the process, reopen, query it, and persist deletion.

## M6: recursive B-tree — implemented foundation

Goal: keep lookup logarithmic as the file grows.

- Interior table pages, recursive descent, and split propagation.
- Stable-root growth without changing the catalog page id.
- Multi-level reopen tests under adversarial insertion orders.
- Remaining: overflow chains, deletion rebalancing, and freelist reuse.

## M7: rollback transactions — implemented foundation

Goal: survive a crash at every point in commit.

- Before-images, forced private journal, and atomic commit marker.
- Hot-journal detection, checksum validation, and recovery.
- Original file-length restoration after allocation.
- Remaining: dirty-page cache and exhaustive filesystem fault injection.

## M8: SQLite-compatible pages

Goal: read a constrained database produced by `sqlite3`.

- 100-byte database header.
- Page-1 B-tree offset.
- SQLite cell pointer arrays and payload fractions.
- Freelist trunk and leaf pages.

## M9: query virtual machine and planner

Goal: separate compilation from execution and exploit indexes.

- Register-based bytecode.
- Cursor operations over tables and indexes.
- Costed table scan versus index scan.
- `EXPLAIN` output for learning and debugging.

## Definition of done for every milestone

- All public types and non-obvious invariants have Scaladoc with specification
  links.
- No production Scala file exceeds roughly 350 lines.
- Happy paths, boundaries, invalid input, and reopen behavior are tested.
- The chapter explains what changed, why, and what remains deliberately false.
- The commit is conventional and small enough to review independently.
