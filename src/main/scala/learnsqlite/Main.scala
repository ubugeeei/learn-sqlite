package learnsqlite

import learnsqlite.engine.{Database, Result}

/** Minimal interactive shell for exploring the SQL engine. */
@main def learnSqlite(arguments: String*): Unit =
  if arguments.headOption.contains("repl") then repl()
  else Console.err.println("usage: scala-cli run . -- repl")

private def repl(): Unit =
  val database = Database()
  println(
    "learn-sqlite 0.1 (in-memory SQL milestone); end statements with ';', .quit to exit"
  )
  var running = true
  while running do
    val line = scala.io.StdIn.readLine("lsql> ")
    if line == null || line.trim == ".quit" then running = false
    else if line.trim.nonEmpty then
      database.execute(line) match
        case Left(error)   => Console.err.println(s"error: ${error.message}")
        case Right(result) => printResult(result)

private def printResult(result: Result): Unit = result match
  case Result.Created(table)       => println(s"created $table")
  case Result.Modified(rows)       => println(s"$rows row(s) modified")
  case Result.Query(columns, rows) =>
    println(columns.mkString("|"))
    rows.foreach(row => println(row.map(_.render).mkString("|")))
