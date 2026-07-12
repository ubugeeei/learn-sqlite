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

  private def temporaryDatabase(prefix: String): Path =
    Files.createTempDirectory(prefix).resolve("app.db")

  private def withDatabase[A](path: Path)(use: Database => A): A =
    val backend = FileBackend.open(path, 512).fold(error => fail(error), identity)
    val database = Database(backend)
    try use(database)
    finally database.close()
