package learnsqlite.storage

import learnsqlite.core.{Row, Schema}
import learnsqlite.engine.{Backend, StoredTable}
import learnsqlite.sql.{ColumnDefinition, Identifier}

import java.nio.file.Path

/**
 * File-backed SQL storage using catalog, record codec, B-tree, and pager.
 *
 * One instance owns one pager and must be closed. Schemas live in the catalog tree rooted at page
 * zero; each user table owns a distinct stable root. Row payloads use SQLite's record format even
 * though the surrounding page format remains private.
 */
final class FileBackend private (pager: Pager, catalog: Catalog)
    extends Backend:
  def create(
    name: Identifier,
    columns: Vector[ColumnDefinition]
  ): Either[String, Unit] =
    for
      _ <- catalog
        .find(name)
        .left
        .map(_.message)
        .flatMap:
          case Some(_) => Left(s"table already exists: ${name.value}")
          case None    => Right(())
      _ <- Schema.from(columns)
      _ <- pager.transaction:
        for
          allocated <- TableBTree.create(pager)
          (root, _) = allocated
          _ <- catalog.add(CatalogEntry(name, columns, root))
        yield ()
      .left.map(_.message)
    yield ()

  def table(name: Identifier): Either[String, StoredTable] =
    catalog
      .find(name)
      .left
      .map(_.message)
      .flatMap:
        case None => Left(s"no such table: ${name.value}")
        case Some(entry) =>
          for
            schema <- Schema.from(entry.columns)
            tree <- TableBTree.at(pager, entry.rootPage).left.map(_.message)
          yield FileTable(schema, tree)

  def flush(): Either[String, Unit] =
    try
      pager.force()
      Right(())
    catch case error: java.io.IOException => Left(error.getMessage)

  override def close(): Unit = pager.close()

  final private class FileTable(val schema: Schema, tree: TableBTree)
      extends StoredTable:
    def rows: Either[String, Vector[Row]] =
      tree.scan.left
        .map(_.message)
        .flatMap: entries =>
          traverse(entries): (_, bytes) =>
            RecordCodec
              .decode(bytes)
              .left
              .map(_.message)
              .flatMap(values => Row.checked(schema, values))

    def append(rows: Vector[Row]): Either[String, Unit] =
      val encoded = traverse(rows)(row => RecordCodec.encode(row.values).left.map(_.message))
      encoded.flatMap: records =>
        tree.scan.left.map(_.message).flatMap: existing =>
          val firstKey = existing.lastOption.fold(1L)(_._1 + 1)
          pager.transaction:
            traverseStorage(records.zipWithIndex): (bytes, index) =>
              tree.insert(firstKey + index, bytes)
          .left.map(_.message)

    def replace(rows: Vector[Row]): Either[String, Unit] =
      traverse(rows.zipWithIndex): (row, index) =>
        RecordCodec
          .encode(row.values)
          .left
          .map(_.message)
          .map(bytes => (index.toLong + 1) -> bytes)
      .flatMap(entries => pager.transaction(tree.replace(entries)).left.map(_.message))

  private def traverse[A, B](values: Vector[A])(
    f: A => Either[String, B]
  ): Either[String, Vector[B]] =
    values.foldLeft(Right(Vector.empty): Either[String, Vector[B]]):
      case (result, value) => result.flatMap(acc => f(value).map(acc :+ _))

  private def traverseStorage[A](
    values: Vector[A]
  )(f: A => Either[StorageError, Unit]): Either[StorageError, Unit] =
    values.foldLeft(Right(()): Either[StorageError, Unit])((result, value) =>
      result.flatMap(_ => f(value))
    )

object FileBackend:
  /**
   * Opens an existing database or initializes an empty one at `path`.
   *
   * Opening validates the private header and catalog root. A failed open closes the underlying file
   * descriptor before returning the error.
   */
  def open(
    path: Path,
    pageSize: Int = Pager.DefaultPageSize
  ): Either[String, FileBackend] =
    Pager
      .open(path, pageSize)
      .left
      .map(_.message)
      .flatMap: pager =>
        Catalog.open(pager).left.map(_.message) match
          case Right(catalog) => Right(FileBackend(pager, catalog))
          case Left(error) =>
            pager.close()
            Left(error)
