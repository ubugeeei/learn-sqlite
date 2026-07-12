package learnsqlite

import learnsqlite.engine.{Database, Result}
import learnsqlite.storage.FileBackend

import java.nio.file.Path

/** Minimal interactive shell for exploring the SQL engine. */
@main def learnSqlite(arguments: String*): Unit =
  arguments.toList match
    case "repl" :: path :: Nil =>
      FileBackend.open(Path.of(path)) match
        case Left(error) => Console.err.println(s"cannot open database: $error")
        case Right(backend) =>
          val database = Database(backend)
          try repl(database, s"file $path")
          finally database.close()
    case "memory" :: Nil => repl(Database(), "memory")
    case _ => Console.err.println("usage: scala-cli run . -- repl <database-file> | memory")

private def repl(database: Database, description: String): Unit =
  println(s"learn-sqlite 0.2 ($description); enter one statement per line, .quit to exit")
  var running = true
  while running do
    val line = scala.io.StdIn.readLine("lsql> ")
    if line == null || line.trim == ".quit" then running = false
    else if line.trim.nonEmpty then
      database.execute(line) match
        case Left(error)   => Console.err.println(s"error: ${error.message}")
        case Right(result) => printResult(result)

private def printResult(result: Result): Unit = result match
  case Result.Created(table) => println(s"created $table")
  case Result.Modified(rows) => println(s"$rows row(s) modified")
  case Result.Query(columns, rows) =>
    println(columns.mkString("|"))
    rows.foreach(row => println(row.map(_.render).mkString("|")))
