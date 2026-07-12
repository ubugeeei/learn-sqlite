package learnsqlite.storage

import learnsqlite.sql.{ColumnDefinition, Identifier}
import learnsqlite.core.Value

/** Persistent schema entry stored in the catalog B-tree. */
final case class CatalogEntry(
  name: Identifier,
  columns: Vector[ColumnDefinition],
  rootPage: PageId
)

/**
 * The database catalog maps table names to schemas and B-tree roots.
 *
 * SQLite uses `sqlite_schema`; its documented columns and lifecycle are at
 * [[https://www.sqlite.org/schematab.html The Schema Table]]. This private format stores equivalent
 * minimum metadata as ordinary record payloads.
 */
final class Catalog private (tree: TableBTree):
  import Catalog.*
  def tables: Either[StorageError, Vector[CatalogEntry]] =
    tree.scan.flatMap(entries =>
      traverse(entries)(_._2).map(_.sortBy(_.name.normalized))
    )

  def find(name: Identifier): Either[StorageError, Option[CatalogEntry]] =
    tables.map(_.find(_.name.normalized == name.normalized))

  def add(entry: CatalogEntry): Either[StorageError, Unit] =
    find(entry.name).flatMap:
      case Some(_) =>
        Left(StorageError(s"table already exists: ${entry.name.value}"))
      case None =>
        tree.scan.flatMap: current =>
          val nextKey = current.lastOption.fold(1L)(_._1 + 1)
          encode(entry).flatMap(tree.insert(nextKey, _))

  private def traverse(entries: Vector[(Long, Array[Byte])])(
    bytes: ((Long, Array[Byte])) => Array[Byte]
  ): Either[StorageError, Vector[CatalogEntry]] =
    entries.foldLeft(
      Right(Vector.empty): Either[StorageError, Vector[CatalogEntry]]
    ):
      case (result, entry) =>
        result.flatMap(values => decode(bytes(entry)).map(values :+ _))

object Catalog:
  def open(pager: Pager): Either[StorageError, Catalog] =
    TableBTree.open(pager).map(Catalog(_))

  private def encode(entry: CatalogEntry): Either[StorageError, Array[Byte]] =
    val header = Vector[Value](
      Value.Text(entry.name.value),
      Value.Integer(entry.rootPage.value),
      Value.Integer(entry.columns.size)
    )
    val columns = entry.columns.flatMap: column =>
      Vector[Value](
        Value.Text(column.name.value),
        column.declaredType.fold[Value](Value.Null)(Value.Text(_)),
        Value.Integer(if column.primaryKey then 1 else 0),
        Value.Integer(if column.nullable then 1 else 0)
      )
    RecordCodec.encode(header ++ columns)

  private def decode(bytes: Array[Byte]): Either[StorageError, CatalogEntry] =
    RecordCodec
      .decode(bytes)
      .flatMap:
        case Vector(
              Value.Text(name),
              Value.Integer(root),
              Value.Integer(count),
              tail*
            ) =>
          if root < 0 || root > Int.MaxValue then
            Left(StorageError("catalog root page is invalid"))
          else if count < 0 || count > Int.MaxValue || tail.size != count * 4
          then Left(StorageError("catalog column count does not match payload"))
          else
            tail
              .grouped(4)
              .toVector
              .foldLeft(
                Right(Vector.empty): Either[StorageError, Vector[
                  ColumnDefinition
                ]]
              ):
                case (
                      result,
                      Vector(
                        Value.Text(columnName),
                        declared,
                        Value.Integer(primary),
                        Value.Integer(nullable)
                      )
                    ) =>
                  val declaredType = declared match
                    case Value.Null       => Right(None)
                    case Value.Text(name) => Right(Some(name))
                    case _ =>
                      Left(StorageError("catalog declared type is invalid"))
                  result.flatMap(columns =>
                    declaredType.map(value =>
                      columns :+ ColumnDefinition(
                        Identifier(columnName),
                        value,
                        primary != 0,
                        nullable != 0
                      )
                    )
                  )
                case (_, _) =>
                  Left(StorageError("catalog column record is invalid"))
              .map(columns =>
                CatalogEntry(Identifier(name), columns, PageId(root.toInt))
              )
        case _ => Left(StorageError("catalog record has an invalid shape"))
