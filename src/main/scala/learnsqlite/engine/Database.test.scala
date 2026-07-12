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
