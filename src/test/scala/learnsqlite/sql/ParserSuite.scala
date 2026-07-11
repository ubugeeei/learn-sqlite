package learnsqlite.sql

class ParserSuite extends munit.FunSuite:
  test("lexer preserves positions and SQL string escaping"):
    val tokens = Lexer.tokenize("-- note\nSELECT 'it''s';").toOption.get
    assertEquals(tokens.head.position, SourcePosition(8, 2, 1))
    assert(tokens.exists(_.kind == TokenKind.Text("it's")))

  test("parse CREATE TABLE constraints"):
    val statement = Parser.parse("CREATE TABLE IF NOT EXISTS users (id INTEGER PRIMARY KEY, name TEXT NOT NULL)")
    assertEquals(
      statement,
      Right(Statement.CreateTable(
        Identifier("users"),
        Vector(
          ColumnDefinition(Identifier("id"), Some("INTEGER"), primaryKey = true, nullable = true),
          ColumnDefinition(Identifier("name"), Some("TEXT"), primaryKey = false, nullable = false)
        ),
        ifNotExists = true
      ))
    )

  test("parse multi-row INSERT"):
    val statement = Parser.parse("INSERT INTO t (id, name) VALUES (1, 'Ada'), (2, 'Grace');")
    val insert = statement.toOption.get.asInstanceOf[Statement.Insert]
    assertEquals(insert.rows.size, 2)
    assertEquals(insert.columns, Vector(Identifier("id"), Identifier("name")))

  test("operator precedence puts multiplication below addition"):
    val select = Parser.parse("SELECT 1 + 2 * 3 AS answer FROM numbers").toOption.get
      .asInstanceOf[Statement.Select]
    val expression = select.projection.head.asInstanceOf[SelectItem.Expression].expression
    assertEquals(
      expression,
      Expr.Binary(
        Expr.Value(Literal.Integer(1)),
        BinaryOperator.Add,
        Expr.Binary(Expr.Value(Literal.Integer(2)), BinaryOperator.Multiply, Expr.Value(Literal.Integer(3)))
      )
    )

  test("parse DELETE predicate"):
    val delete = Parser.parse("DELETE FROM users WHERE id >= 10 AND name != 'root'")
    assert(delete.toOption.get.asInstanceOf[Statement.Delete].where.nonEmpty)

  test("report location for an unterminated literal"):
    val error = Parser.parse("SELECT 'oops FROM t").left.toOption.get
    assertEquals(error.position, SourcePosition(7, 1, 8))

  test("reject trailing tokens"):
    val error = Parser.parse("SELECT * FROM t nonsense").left.toOption.get
    assert(error.message.contains("end of input"))
