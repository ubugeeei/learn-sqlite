package learnsqlite.core

import learnsqlite.sql.ColumnDefinition
import learnsqlite.sql.Identifier

final case class Column(index: Int, definition: ColumnDefinition)

/** Ordered table schema with case-insensitive name resolution. */
final class Schema private (
    val columns: Vector[Column],
    private val byName: Map[String, Column]
):
  def resolve(name: Identifier): Option[Column] = byName.get(name.normalized)
  def size: Int = columns.size

object Schema:
  def from(definitions: Vector[ColumnDefinition]): Either[String, Schema] =
    if definitions.isEmpty then Left("a table needs at least one column")
    else
      val duplicate = definitions.groupBy(_.name.normalized).collectFirst:
        case (name, values) if values.size > 1 => name
      duplicate match
        case Some(name) => Left(s"duplicate column: $name")
        case None =>
          val columns = definitions.zipWithIndex.map((definition, index) => Column(index, definition))
          val primaryKeys = columns.count(_.definition.primaryKey)
          if primaryKeys > 1 then Left("only one PRIMARY KEY is supported")
          else Right(Schema(columns, columns.map(column => column.definition.name.normalized -> column).toMap))

final case class Row private (values: Vector[Value]):
  def apply(column: Column): Value = values(column.index)

object Row:
  def checked(schema: Schema, values: Vector[Value]): Either[String, Row] =
    if values.size != schema.size then Left(s"expected ${schema.size} values, got ${values.size}")
    else
      schema.columns.collectFirst:
        case column if !column.definition.nullable && values(column.index) == Value.Null =>
          column.definition.name.value
      match
        case Some(name) => Left(s"column $name may not be NULL")
        case None       => Right(Row(values))

