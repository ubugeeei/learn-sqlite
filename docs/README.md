# Building a SQLite-like Database in Scala 3

This book develops a durable relational database in small, testable layers.
Each chapter links the relevant SQLite specification, explains the domain
model, and points to colocated production code and executable tests.

## Reading order

1. [Scope and compatibility](compatibility.md)
2. [SQL as a language](01-sql-language.md)
3. [Rows, schemas, and expressions](02-relational-core.md)
4. [Pages and durable storage](03-pager.md)
5. [B-trees](04-btree.md)
6. [Transactions and recovery](05-transactions.md)

The chapters are intended to be followed in order. Every code sample is either
copied from the implementation or small enough to paste into a Scala REPL.

