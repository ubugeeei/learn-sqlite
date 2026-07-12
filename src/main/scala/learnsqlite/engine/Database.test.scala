package learnsqlite.engine

import learnsqlite.core.Value

class DatabaseSuite extends munit.FunSuite:
  test("create, insert, filter, and project"):
    val database = Database()
    assert(
      database
        .execute(
          "CREATE TABLE people (id INTEGER PRIMARY KEY, name TEXT NOT NULL)"
        )
        .isRight
    )
    assertEquals(
      database.execute("INSERT INTO people VALUES (1, 'Ada'), (2, 'Grace')"),
      Right(Result.Modified(2))
    )
    assertEquals(
      database.execute(
        "SELECT name, id + 10 AS shifted FROM people WHERE id >= 2"
      ),
      Right(
        Result.Query(
          Vector("name", "shifted"),
          Vector(Vector(Value.Text("Grace"), Value.Integer(12)))
        )
      )
    )

  test("column lists fill omitted nullable columns with NULL"):
    val database = Database()
    val _ =
      database.execute("CREATE TABLE notes (id INTEGER NOT NULL, body TEXT)")
    assertEquals(
      database.execute("INSERT INTO notes (id) VALUES (1)"),
      Right(Result.Modified(1))
    )
    assertEquals(
      database.execute("SELECT * FROM notes"),
      Right(
        Result.Query(
          Vector("id", "body"),
          Vector(Vector(Value.Integer(1), Value.Null))
        )
      )
    )

  test("NOT NULL is checked before mutating the table"):
    val database = Database()
    val _ = database.execute("CREATE TABLE users (id INTEGER NOT NULL)")
    val result = database.execute("INSERT INTO users VALUES (NULL), (2)")
    assert(result.left.toOption.get.message.contains("may not be NULL"))
    assertEquals(
      database
        .execute("SELECT * FROM users")
        .toOption
        .get
        .asInstanceOf[Result.Query]
        .rows,
      Vector.empty
    )

  test("DELETE uses SQL predicates"):
    val database = Database()
    val _ = database.execute("CREATE TABLE events (id INTEGER)")
    val _ = database.execute("INSERT INTO events VALUES (1), (2), (3)")
    assertEquals(
      database.execute("DELETE FROM events WHERE id < 3"),
      Right(Result.Modified(2))
    )

  test("NULL predicate is unknown and therefore does not select"):
    val database = Database()
    val _ = database.execute("CREATE TABLE values_table (value INTEGER)")
    val _ = database.execute("INSERT INTO values_table VALUES (NULL), (1)")
    val query = database
      .execute("SELECT * FROM values_table WHERE value = NULL")
      .toOption
      .get
      .asInstanceOf[Result.Query]
    assertEquals(query.rows, Vector.empty)

  test("unknown tables and columns are domain errors"):
    val database = Database()
    assert(
      database
        .execute("SELECT * FROM missing")
        .left
        .toOption
        .get
        .message
        .contains("no such table")
    )
    val _ = database.execute("CREATE TABLE present (id INTEGER)")
    assert(
      database
        .execute("SELECT absent FROM present")
        .left
        .toOption
        .get
        .message
        .contains("no such column")
    )

  test("UPDATE evaluates assignments against the original row"):
    val database = Database()
    assert(database.execute("CREATE TABLE pairs (left_value INTEGER, right_value INTEGER)").isRight)
    assert(database.execute("INSERT INTO pairs VALUES (1, 2), (3, 4)").isRight)
    assertEquals(
      database.execute(
        "UPDATE pairs SET left_value = right_value, right_value = left_value WHERE left_value = 1"
      ),
      Right(Result.Modified(1))
    )
    assertEquals(
      database.execute("SELECT * FROM pairs"),
      Right(
        Result.Query(
          Vector("left_value", "right_value"),
          Vector(
            Vector(Value.Integer(2), Value.Integer(1)),
            Vector(Value.Integer(3), Value.Integer(4))
          )
        )
      )
    )

  private val invalidUpdates = Vector(
    "unknown target column" -> "UPDATE users SET missing = 1",
    "duplicate target column" -> "UPDATE users SET id = 1, id = 2",
    "unknown expression column" -> "UPDATE users SET id = missing",
    "NOT NULL violation" -> "UPDATE users SET id = NULL"
  )

  invalidUpdates.foreach: (scenario, sql) =>
    test(s"UPDATE is all-or-nothing for $scenario"):
      val database = Database()
      assert(database.execute("CREATE TABLE users (id INTEGER NOT NULL)").isRight)
      assert(database.execute("INSERT INTO users VALUES (1), (2)").isRight)
      assert(database.execute(sql).isLeft)
      assertEquals(
        database.execute("SELECT * FROM users"),
        Right(Result.Query(
          Vector("id"),
          Vector(Vector(Value.Integer(1)), Vector(Value.Integer(2)))
        ))
      )

  test("column affinity converts values before storage"):
    val database = Database()
    assert(database.execute(
      "CREATE TABLE affinity_values (as_text TEXT, as_numeric NUMERIC, as_integer INTEGER, as_real REAL, unchanged BLOB)"
    ).isRight)
    assert(database.execute(
      "INSERT INTO affinity_values VALUES (500, '500.0', '500.0', '500.0', '500.0')"
    ).isRight)
    assertEquals(
      database.execute("SELECT * FROM affinity_values"),
      Right(
        Result.Query(
          Vector("as_text", "as_numeric", "as_integer", "as_real", "unchanged"),
          Vector(Vector(
            Value.Text("500"),
            Value.Integer(500),
            Value.Integer(500),
            Value.Real(500.0),
            Value.Text("500.0")
          ))
        )
      )
    )

  private val primaryKeyViolations = Vector(
    "NULL key" -> "INSERT INTO keyed VALUES (NULL, 'missing')",
    "duplicate within one batch" -> "INSERT INTO keyed VALUES (2, 'two'), (2, 'again')",
    "duplicate existing row" -> "INSERT INTO keyed VALUES (1, 'again')",
    "duplicate after affinity" -> "INSERT INTO keyed VALUES ('1', 'text one')"
  )

  primaryKeyViolations.foreach: (scenario, sql) =>
    test(s"PRIMARY KEY rejects $scenario without changing the table"):
      val database = Database()
      assert(database.execute("CREATE TABLE keyed (id INTEGER PRIMARY KEY, label TEXT)").isRight)
      assert(database.execute("INSERT INTO keyed VALUES (1, 'one')").isRight)
      assert(database.execute(sql).isLeft)
      assertEquals(
        database.execute("SELECT * FROM keyed"),
        Right(Result.Query(
          Vector("id", "label"),
          Vector(Vector(Value.Integer(1), Value.Text("one")))
        ))
      )

  test("PRIMARY KEY rejects an UPDATE collision atomically"):
    val database = Database()
    assert(database.execute("CREATE TABLE keyed (id INTEGER PRIMARY KEY, label TEXT)").isRight)
    assert(database.execute("INSERT INTO keyed VALUES (1, 'one'), (2, 'two')").isRight)
    assert(database.execute("UPDATE keyed SET id = 1 WHERE id = 2").isLeft)
    assertEquals(
      database.execute("SELECT * FROM keyed"),
      Right(
        Result.Query(
          Vector("id", "label"),
          Vector(
            Vector(Value.Integer(1), Value.Text("one")),
            Vector(Value.Integer(2), Value.Text("two"))
          )
        )
      )
    )

  test("ORDER BY supports multiple directions, NULL, and LIMIT"):
    val database = Database()
    assert(
      database.execute("CREATE TABLE scores (name TEXT, score NUMERIC, priority INTEGER)").isRight
    )
    assert(database.execute(
      "INSERT INTO scores VALUES ('none', NULL, 9), ('Ada', 10, 2), ('Grace', 10.0, 1), ('Linus', 7, 3)"
    ).isRight)
    assertEquals(
      database.execute("SELECT name FROM scores ORDER BY score DESC, priority ASC LIMIT 3"),
      Right(
        Result.Query(
          Vector("name"),
          Vector(
            Vector(Value.Text("Grace")),
            Vector(Value.Text("Ada")),
            Vector(Value.Text("Linus"))
          )
        )
      )
    )

  test("ORDER BY rejects an unknown column even for an empty table"):
    val database = Database()
    assert(database.execute("CREATE TABLE empty_table (id INTEGER)").isRight)
    assert(database.execute("SELECT * FROM empty_table ORDER BY missing").isLeft)
