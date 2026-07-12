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
  /** Creates an empty table after validating its schema. */
  def create(
    name: Identifier,
    columns: Vector[ColumnDefinition]
  ): Either[String, Unit]

  /** Resolves a table case-insensitively or returns a user-facing error. */
  def table(name: Identifier): Either[String, StoredTable]

  /** Establishes the backend's durability barrier for preceding writes. */
  def flush(): Either[String, Unit]

  /** Releases files or other resources owned by this backend. */
  override def close(): Unit

/**
 * Row-oriented operations required by the SQL interpreter.
 *
 * Implementations must not expose mutable aliases. Input rows have already passed schema
 * validation, but the backend remains responsible for all-or-error storage behavior.
 */
trait StoredTable:
  /** Ordered schema used to interpret every returned row. */
  def schema: Schema

  /** Materializes rows in rowid order. */
  def rows: Either[String, Vector[Row]]

  /** Adds a validated batch without changing existing row order. */
  def append(rows: Vector[Row]): Either[String, Unit]

  /** Replaces the complete logical contents of the table. */
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
