package learnsqlite.engine

import learnsqlite.core.{Row, Schema}
import learnsqlite.sql.{ColumnDefinition, Identifier}

/**
 * Persistence boundary used by the SQL executor.
 *
 * Backends own table discovery and row durability; they do not parse SQL or evaluate expressions.
 * This keeps identical SQL semantics for memory and file-backed databases.
 */
trait Backend extends AutoCloseable:
  def create(
    name: Identifier,
    columns: Vector[ColumnDefinition]
  ): Either[String, Unit]
  def table(name: Identifier): Either[String, StoredTable]
  def flush(): Either[String, Unit]
  override def close(): Unit

trait StoredTable:
  def schema: Schema
  def rows: Either[String, Vector[Row]]
  def append(rows: Vector[Row]): Either[String, Unit]
  def replace(rows: Vector[Row]): Either[String, Unit]

final private[engine] class MemoryBackend extends Backend:
  final private class MemoryTable(val schema: Schema) extends StoredTable:
    private var contents = Vector.empty[Row]
    def rows = Right(contents)
    def append(rows: Vector[Row]) =
      contents ++= rows
      Right(())
    def replace(rows: Vector[Row]) =
      contents = rows
      Right(())

  private var tables = Map.empty[String, MemoryTable]

  def create(name: Identifier, columns: Vector[ColumnDefinition]) =
    if tables.contains(name.normalized) then
      Left(s"table already exists: ${name.value}")
    else
      Schema
        .from(columns)
        .map(schema => tables += name.normalized -> MemoryTable(schema))

  def table(name: Identifier) =
    tables.get(name.normalized).toRight(s"no such table: ${name.value}")
  def flush() = Right(())
  def close(): Unit = ()
