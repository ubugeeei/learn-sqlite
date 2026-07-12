# Architecture: from SQL text to durable bytes

Before implementing a database, learn to name its boundaries. Most accidental
complexity comes from letting one layer know details owned by another.

## Frontend

The lexer owns characters, comments, literals, and source locations. The parser
owns grammar and precedence. Neither knows whether a table exists. This mirrors
the tokenizer/parser stages in SQLite's [architecture overview][arch].

The AST is intentionally immutable. It can be printed, tested, transformed into
a plan, or executed directly by the early interpreter.

## Semantic and relational core

`Schema` resolves names to stable column positions. `Row` preserves that order.
`Evaluator` owns NULL propagation, numeric operations, comparisons, and truth.
The executor owns statement-level effects such as adding rows to a table.

This separation catches an important bug: selecting an unknown column from an
empty table must fail during validation. If name resolution happened only while
visiting rows, an empty table would incorrectly hide the error.

## Serialization

`Value` is logical; `RecordCodec` is physical. A BLOB is not “an array inside
the engine”—it is an immutable logical value that happens to serialize to raw
bytes. Defensive copies prevent a caller from mutating committed state through
an alias.

The record codec does not allocate pages. It accepts and returns byte arrays, so
all integer widths and malformed records can be tested without filesystem I/O.

## B-tree

The B-tree owns key ordering, cell placement, splitting, and traversal. It does
not understand SQL column names. Table records are opaque payloads keyed by
rowid. Index records will later use encoded index keys with a different page
kind.

Our current tree implements a linked leaf level. This is a pedagogical B+tree,
not a claim of SQLite page compatibility. It makes split invariants observable
before adding recursive interior pages.

## Pager

The pager owns page identity and whole-page I/O. Higher layers must never seek a
`FileChannel` directly. Later, the same boundary will own caching, dirty state,
journaling, locking, and recovery without forcing the B-tree to change.

## Dependency rule

Dependencies point down toward bytes:

```text
CLI -> Database -> SQL/Core -> storage abstraction
                                  |
                                  v
                             RecordCodec
                                  |
                                  v
                              TableBTree
                                  |
                                  v
                                Pager
```

Storage packages must not import the parser. Catalog reconstruction is an
application-layer orchestration concern: decode a catalog record, then ask the
SQL frontend to parse its saved DDL.

## Errors are data

Syntax errors contain source positions. Execution errors name the missing table
or violated constraint. Storage errors reject corrupt sizes and page kinds.
Expected failures use `Either`; JVM exceptions are caught only at boundaries
where they can be translated without losing meaning.

## Colocation

Production source and its executable specification share the same package path:

```text
src/main/scala/learnsqlite/storage/RecordCodec.scala
src/main/scala/learnsqlite/storage/RecordCodec.test.scala
docs/07-record-format.md
```

Scala CLI recognizes the `.test.scala` suffix and compiles those files in a
separate test scope. We therefore get physical colocation without putting MUnit
or test classes in the production artifact. Code and tests answer “how”; the
chapter answers “why,” “in what order,” and “what should I try next.” See the
[Scala CLI test-source rules][scala-cli-test] for the underlying convention.

[arch]: https://www.sqlite.org/arch.html
[scala-cli-test]: https://scala-cli.virtuslab.org/docs/commands/test/#test-sources
