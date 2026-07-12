package learnsqlite.storage

import learnsqlite.core.{Row, Schema}
import learnsqlite.engine.{Backend, StoredTable}
import learnsqlite.sql.{ColumnDefinition, Identifier}

import java.nio.file.Path

/** File-backed SQL storage using catalog, record codec, B-tree, and pager. */
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
      allocated <- TableBTree.create(pager).left.map(_.message)
      (root, _) = allocated
      _ <- catalog.add(CatalogEntry(name, columns, root)).left.map(_.message)
      _ <- flush()
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
      tree.scan.left
        .map(_.message)
        .flatMap: existing =>
          val firstKey = existing.lastOption.fold(1L)(_._1 + 1)
          traverseUnit(rows.zipWithIndex): (row, index) =>
            RecordCodec
              .encode(row.values)
              .left
              .map(_.message)
              .flatMap(bytes =>
                tree.insert(firstKey + index, bytes).left.map(_.message)
              )
          .flatMap(_ => flush())

    def replace(rows: Vector[Row]): Either[String, Unit] =
      traverse(rows.zipWithIndex): (row, index) =>
        RecordCodec
          .encode(row.values)
          .left
          .map(_.message)
          .map(bytes => (index.toLong + 1) -> bytes)
      .flatMap(entries => tree.replace(entries).left.map(_.message))
        .flatMap(_ => flush())

  private def traverse[A, B](values: Vector[A])(
    f: A => Either[String, B]
  ): Either[String, Vector[B]] =
    values.foldLeft(Right(Vector.empty): Either[String, Vector[B]]):
      case (result, value) => result.flatMap(acc => f(value).map(acc :+ _))

  private def traverseUnit[A](
    values: Vector[A]
  )(f: A => Either[String, Unit]): Either[String, Unit] =
    values.foldLeft(Right(()): Either[String, Unit])((result, value) =>
      result.flatMap(_ => f(value))
    )

object FileBackend:
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
