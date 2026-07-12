# Glossary

Use this page whenever a chapter introduces an unfamiliar word.

| Term | Plain-language meaning |
|---|---|
| AST | Typed data representing the structure of a parsed SQL sentence. |
| atomic | Visible as one complete operation: all effects happen, or none do. |
| backend | Interface between SQL semantics and a storage implementation. |
| BLOB | Binary large object; bytes whose meaning belongs to the application. |
| B-tree | Balanced ordered search structure designed for disk pages. |
| catalog | Metadata records describing tables, columns, and root pages. |
| cell | One key/payload entry inside a B-tree page. |
| codec | Encode and decode rules between values and bytes. |
| column | One named position in every row of a table. |
| commit | Point after which a transaction is considered successful. |
| constraint | Rule rows must satisfy, such as `NOT NULL`. |
| cursor | Movable position used to traverse table or index entries. |
| deserialization | Converting stored bytes back into logical values. |
| durable | Guaranteed to survive failures covered by the transaction protocol. |
| expression | SQL computing a value, such as `id + 1`. |
| interior page | B-tree page containing routing keys and child page ids. |
| key | Value identifying or ordering a stored entry. |
| leaf page | B-tree page containing actual key/payload entries. |
| lexer | Turns characters into keywords, names, literals, and punctuation tokens. |
| metadata | Data describing other data; schemas are metadata. |
| page | Fixed-size block used as the unit of database file I/O. |
| page id | Number identifying a page in one database file. |
| pager | Reads, writes, caches, and eventually journals pages. |
| parser | Checks token order against grammar and builds an AST. |
| payload | Record bytes stored beside a B-tree key. |
| persistent | Stored outside process memory so it survives restart. |
| record | Encoded values for one row or index entry. |
| rollback journal | Original page images used to undo an interrupted transaction. |
| root page | Stable entry page used to open one B-tree. |
| row | One ordered set of values matching a schema. |
| rowid | Integer key identifying and ordering a table row. |
| schema | Definitions of tables and columns, including constraints. |
| serialization | Converting logical values into bytes. |
| storage class | NULL, INTEGER, REAL, TEXT, or BLOB. |
| table | Named collection of rows sharing one schema. |
| token | Meaningful lexical unit such as `SELECT`, `42`, or `(`. |
| transaction | Group of operations with atomicity and durability rules. |
| varint | Integer representation using fewer bytes for small values. |
| WAL | Write-ahead log, an alternative transaction representation. |
