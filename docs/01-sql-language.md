# 1. SQL as a language

SQL text should become typed data before it touches storage. We follow the same
broad pipeline described by SQLite's [architecture document][architecture]:
tokenize, parse, plan, then execute.

The first implementation milestone introduces a total lexer (every character
is either a token or a located error), an immutable AST, and a recursive-descent
parser. Scala 3 enums model closed vocabularies; opaque types prevent accidental
mixing of identifiers and arbitrary strings; `Either` keeps syntax failures in
the ordinary control flow.

```scala
enum Statement:
  case CreateTable(name: Identifier, columns: Vector[ColumnDefinition])
  case Insert(table: Identifier, columns: Vector[Identifier], rows: Vector[Vector[Expr]])
  case Select(projection: Vector[SelectItem], from: Identifier, where: Option[Expr])
```

Keep source positions on tokens. A parser error that says `expected ')' at
line 3, column 9` is useful; one that says only `invalid SQL` is not.

[architecture]: https://www.sqlite.org/arch.html

