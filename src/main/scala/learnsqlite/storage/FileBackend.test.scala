package learnsqlite.storage

import learnsqlite.core.Value
import learnsqlite.engine.{Database, Result}

import java.nio.file.{Files, Path}

class FileBackendSuite extends munit.FunSuite:
  test("SQL schema and rows survive close and reopen"):
    val path = temporaryDatabase("durable-sql")
    withDatabase(path): database =>
      assertEquals(
        database.execute(
          "CREATE TABLE users (id INTEGER PRIMARY KEY NOT NULL, name TEXT NOT NULL)"
        ),
        Right(Result.Created("users"))
      )
      assertEquals(
        database.execute("INSERT INTO users VALUES (1, 'Ada'), (2, 'Grace')"),
        Right(Result.Modified(2))
      )

    withDatabase(path): database =>
      assertEquals(
        database.execute("SELECT name FROM users WHERE id >= 2"),
        Right(Result.Query(Vector("name"), Vector(Vector(Value.Text("Grace")))))
      )

  test("multiple table roots remain isolated after reopen"):
    val path = temporaryDatabase("multiple-tables")
    withDatabase(path): database =>
      val statements = Vector(
        "CREATE TABLE users (value TEXT)",
        "CREATE TABLE posts (value TEXT)",
        "INSERT INTO users VALUES ('user-row')",
        "INSERT INTO posts VALUES ('post-row')"
      )
      statements.foreach(sql => assert(database.execute(sql).isRight, sql))

    withDatabase(path): database =>
      val expectations = Vector(
        "SELECT * FROM users" -> Value.Text("user-row"),
        "SELECT * FROM posts" -> Value.Text("post-row")
      )
      expectations.foreach: (sql, expected) =>
        val result = database.execute(sql).toOption.get.asInstanceOf[Result.Query]
        assertEquals(result.rows, Vector(Vector(expected)), sql)

  test("DELETE is durable"):
    val path = temporaryDatabase("durable-delete")
    withDatabase(path): database =>
      assert(database.execute("CREATE TABLE events (id INTEGER, label TEXT)").isRight)
      assert(
        database.execute("INSERT INTO events VALUES (1, 'old'), (2, 'keep'), (3, 'new')").isRight
      )
      assertEquals(database.execute("DELETE FROM events WHERE id != 2"), Right(Result.Modified(2)))

    withDatabase(path): database =>
      assertEquals(
        database.execute("SELECT * FROM events"),
        Right(Result.Query(
          Vector("id", "label"),
          Vector(Vector(Value.Integer(2), Value.Text("keep")))
        ))
      )

  test("failed row validation does not append a prefix"):
    val path = temporaryDatabase("atomic-validation")
    withDatabase(path): database =>
      assert(database.execute("CREATE TABLE users (id INTEGER NOT NULL)").isRight)
      assert(database.execute("INSERT INTO users VALUES (1), (NULL), (3)").isLeft)
      assertEquals(
        database.execute("SELECT * FROM users"),
        Right(Result.Query(Vector("id"), Vector.empty))
      )

  test("UPDATE survives reopen and failed UPDATE preserves every row"):
    val path = temporaryDatabase("durable-update")
    withDatabase(path): database =>
      assert(database.execute(
        "CREATE TABLE inventory (id INTEGER NOT NULL, quantity INTEGER NOT NULL)"
      ).isRight)
      assert(database.execute("INSERT INTO inventory VALUES (1, 10), (2, 20)").isRight)
      assertEquals(
        database.execute("UPDATE inventory SET quantity = quantity + 5 WHERE id = 2"),
        Right(Result.Modified(1))
      )
      assert(database.execute("UPDATE inventory SET quantity = NULL").isLeft)

    withDatabase(path): database =>
      assertEquals(
        database.execute("SELECT * FROM inventory"),
        Right(
          Result.Query(
            Vector("id", "quantity"),
            Vector(
              Vector(Value.Integer(1), Value.Integer(10)),
              Vector(Value.Integer(2), Value.Integer(25))
            )
          )
        )
      )

  test("affinity-derived storage classes survive record encoding and reopen"):
    val path = temporaryDatabase("durable-affinity")
    withDatabase(path): database =>
      assert(database.execute(
        "CREATE TABLE measurements (label TEXT, amount NUMERIC, ratio REAL)"
      ).isRight)
      assert(database.execute("INSERT INTO measurements VALUES (42, '3.0e+5', '2.5')").isRight)

    withDatabase(path): database =>
      assertEquals(
        database.execute("SELECT * FROM measurements"),
        Right(
          Result.Query(
            Vector("label", "amount", "ratio"),
            Vector(Vector(Value.Text("42"), Value.Integer(300_000), Value.Real(2.5)))
          )
        )
      )

  test("PRIMARY KEY violations never change durable rows"):
    val path = temporaryDatabase("durable-primary-key")
    withDatabase(path): database =>
      assert(database.execute("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT)").isRight)
      assert(database.execute("INSERT INTO users VALUES (1, 'Ada'), (2, 'Grace')").isRight)
      assert(database.execute("INSERT INTO users VALUES (3, 'Linus'), (1, 'duplicate')").isLeft)
      assert(database.execute("UPDATE users SET id = 1 WHERE id = 2").isLeft)

    withDatabase(path): database =>
      assertEquals(
        database.execute("SELECT * FROM users"),
        Right(
          Result.Query(
            Vector("id", "name"),
            Vector(
              Vector(Value.Integer(1), Value.Text("Ada")),
              Vector(Value.Integer(2), Value.Text("Grace"))
            )
          )
        )
      )

  test("ORDER BY and LIMIT operate on rows loaded after reopen"):
    val path = temporaryDatabase("durable-ordering")
    withDatabase(path): database =>
      assert(
        database.execute("CREATE TABLE queue (id INTEGER PRIMARY KEY, priority INTEGER)").isRight
      )
      assert(database.execute("INSERT INTO queue VALUES (1, 2), (2, 3), (3, 3), (4, 1)").isRight)

    withDatabase(path): database =>
      assertEquals(
        database.execute("SELECT id FROM queue ORDER BY priority DESC, id DESC LIMIT 2"),
        Right(Result.Query(
          Vector("id"),
          Vector(Vector(Value.Integer(3)), Vector(Value.Integer(2)))
        ))
      )

  test("large TEXT records survive overflow pages and reopen"):
    val path = temporaryDatabase("durable-overflow")
    val largeText = Vector.tabulate(6_000)(index => ('a' + index % 26).toChar).mkString
    withDatabase(path): database =>
      assert(database.execute("CREATE TABLE documents (id INTEGER PRIMARY KEY, body TEXT)").isRight)
      assert(database.execute(s"INSERT INTO documents VALUES (1, '$largeText')").isRight)

    withDatabase(path): database =>
      assertEquals(
        database.execute("SELECT body FROM documents WHERE id = 1"),
        Right(Result.Query(Vector("body"), Vector(Vector(Value.Text(largeText)))))
      )

  private def temporaryDatabase(prefix: String): Path =
    Files.createTempDirectory(prefix).resolve("app.db")

  private def withDatabase[A](path: Path)(use: Database => A): A =
    val backend = FileBackend.open(path, 512).fold(error => fail(error), identity)
    val database = Database(backend)
    try use(database)
    finally database.close()
