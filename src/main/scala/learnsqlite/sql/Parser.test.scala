package learnsqlite.sql

class ParserSuite extends munit.FunSuite:
  test("lexer preserves positions and SQL string escaping"):
    val tokens = Lexer.tokenize("-- note\nSELECT 'it''s';").toOption.get
    assertEquals(tokens.head.position, SourcePosition(8, 2, 1))
    assert(tokens.exists(_.kind == TokenKind.Text("it's")))

  test("parse CREATE TABLE constraints"):
    val statement = Parser.parse(
      "CREATE TABLE IF NOT EXISTS users (id INTEGER PRIMARY KEY, name TEXT NOT NULL)"
    )
    assertEquals(
      statement,
      Right(
        Statement.CreateTable(
          Identifier("users"),
          Vector(
            ColumnDefinition(
              Identifier("id"),
              Some("INTEGER"),
              primaryKey = true,
              nullable = true
            ),
            ColumnDefinition(
              Identifier("name"),
              Some("TEXT"),
              primaryKey = false,
              nullable = false
            )
          ),
          ifNotExists = true
        )
      )
    )

  test("parse multi-row INSERT"):
    val statement =
      Parser.parse("INSERT INTO t (id, name) VALUES (1, 'Ada'), (2, 'Grace');")
    val insert = statement.toOption.get.asInstanceOf[Statement.Insert]
    assertEquals(insert.rows.size, 2)
    assertEquals(insert.columns, Vector(Identifier("id"), Identifier("name")))

  test("operator precedence puts multiplication below addition"):
    val select = Parser
      .parse("SELECT 1 + 2 * 3 AS answer FROM numbers")
      .toOption
      .get
      .asInstanceOf[Statement.Select]
    val expression =
      select.projection.head.asInstanceOf[SelectItem.Expression].expression
    assertEquals(
      expression,
      Expr.Binary(
        Expr.Value(Literal.Integer(1)),
        BinaryOperator.Add,
        Expr.Binary(
          Expr.Value(Literal.Integer(2)),
          BinaryOperator.Multiply,
          Expr.Value(Literal.Integer(3))
        )
      )
    )

  test("parse DELETE predicate"):
    val delete =
      Parser.parse("DELETE FROM users WHERE id >= 10 AND name != 'root'")
    assert(delete.toOption.get.asInstanceOf[Statement.Delete].where.nonEmpty)

  test("parse UPDATE assignments and predicate"):
    val update = Parser
      .parse("UPDATE accounts SET balance = balance + 10, name = 'updated' WHERE id = 1")
      .toOption
      .get
      .asInstanceOf[Statement.Update]
    assertEquals(update.table, Identifier("accounts"))
    assertEquals(update.assignments.map(_._1), Vector(Identifier("balance"), Identifier("name")))
    assert(update.where.nonEmpty)

  test("parse SELECT ordering directions and LIMIT"):
    val select = Parser
      .parse("SELECT id FROM events WHERE id > 0 ORDER BY priority DESC, id ASC LIMIT 10")
      .toOption
      .get
      .asInstanceOf[Statement.Select]
    assertEquals(
      select.orderBy,
      Vector(
        OrderingTerm(Expr.Column(Identifier("priority")), SortDirection.Descending),
        OrderingTerm(Expr.Column(Identifier("id")), SortDirection.Ascending)
      )
    )
    assertEquals(select.limit, Some(10))

  private val invalidStatements = Vector(
    ("unterminated text", "SELECT 'oops FROM t", "unterminated", SourcePosition(7, 1, 8)),
    ("trailing token", "SELECT * FROM t nonsense", "end of input", SourcePosition(16, 1, 17)),
    ("missing projection", "SELECT FROM users", "expected FROM", SourcePosition(12, 1, 13)),
    ("missing table name", "DELETE FROM", "identifier", SourcePosition(11, 1, 12)),
    ("unclosed columns", "CREATE TABLE t (id INTEGER", "')'", SourcePosition(26, 1, 27))
  )

  invalidStatements.foreach: (scenario, sql, expectedMessage, expectedPosition) =>
    test(s"reject $scenario with a positioned diagnostic"):
      val error = Parser.parse(sql).left.toOption.get
      assert(error.message.contains(expectedMessage), error.toString)
      assertEquals(error.position, expectedPosition)
