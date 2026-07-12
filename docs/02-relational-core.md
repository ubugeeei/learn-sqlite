# 2. Rows, schemas, and expressions

The relational layer separates logical values from their byte encoding. SQLite
defines five storage classes: NULL, INTEGER, REAL, TEXT, and BLOB. Read
[Datatypes In SQLite][types] before implementing coercion.

Represent those classes as an enum and make expression evaluation return a
domain error rather than throwing. A `Row` is ordered according to a `Schema`;
name lookup belongs to the schema, not to a global map. This preserves duplicate
values and gives storage a deterministic column order.

Column declarations also derive SQLite's preferred storage conversion. Chapter
[10. SQLite Type Affinity](10-type-affinity.md) implements the ordered declaration rules and applies
them at the shared row-validation boundary.

[types]: https://www.sqlite.org/datatype3.html
