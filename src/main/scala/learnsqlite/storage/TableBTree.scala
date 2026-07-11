package learnsqlite.storage

import java.nio.ByteBuffer

/** A durable ordered table B+tree leaf chain keyed by 64-bit row id.
  *
  * This milestone implements leaf splitting and ordered scans. Interior pages
  * are the next scaling step; the on-disk distinction follows SQLite's
  * [[https://www.sqlite.org/fileformat.html#b_tree_pages B-tree page kinds]].
  */
final class TableBTree private (pager: Pager, root: PageId):
  import TableBTree.*

  def get(key: Long): Either[StorageError, Option[Array[Byte]]] =
    foldLeaves[Option[Array[Byte]]](None):
      case (found @ Some(_), _) => Right(found)
      case (None, leaf)         =>
        Right(leaf.entries.find(_._1 == key).map(_._2.clone()))

  def scan: Either[StorageError, Vector[(Long, Array[Byte])]] =
    foldLeaves(Vector.empty)((values, leaf) =>
      Right(values ++ leaf.entries.map((key, bytes) => key -> bytes.clone()))
    )

  def insert(key: Long, value: Array[Byte]): Either[StorageError, Unit] =
    locate(root, key).flatMap: (id, leaf) =>
      if leaf.entries.exists(_._1 == key) then
        Left(StorageError(s"duplicate key: $key"))
      else
        val updated = leaf.copy(entries =
          (leaf.entries :+ (key -> value.clone())).sortBy(_._1)
        )
        if encodedSize(updated) <= pager.pageSize then write(id, updated)
        else split(id, updated)

  private def split(id: PageId, leaf: Leaf): Either[StorageError, Unit] =
    val middle = leaf.entries.size / 2
    for
      newId <- pager.allocate()
      _ <- write(id, Leaf(leaf.entries.take(middle), Some(newId)))
      _ <- write(newId, Leaf(leaf.entries.drop(middle), leaf.next))
    yield pager.force()

  private def locate(
      id: PageId,
      key: Long
  ): Either[StorageError, (PageId, Leaf)] =
    read(id).flatMap: leaf =>
      if leaf.entries.lastOption.forall(_._1 >= key) || leaf.next.isEmpty then
        Right(id -> leaf)
      else locate(leaf.next.get, key)

  private def foldLeaves[A](initial: A)(
      f: (A, Leaf) => Either[StorageError, A]
  ): Either[StorageError, A] =
    def loop(id: PageId, accumulated: A): Either[StorageError, A] =
      read(id).flatMap(leaf =>
        f(accumulated, leaf).flatMap(value =>
          leaf.next.fold(Right(value))(loop(_, value))
        )
      )
    loop(root, initial)

  private def read(id: PageId): Either[StorageError, Leaf] =
    pager.read(id).flatMap(decode)
  private def write(id: PageId, leaf: Leaf): Either[StorageError, Unit] =
    pager.write(id, encode(leaf, pager.pageSize))

object TableBTree:
  private final case class Leaf(
      entries: Vector[(Long, Array[Byte])],
      next: Option[PageId]
  )
  private val LeafKind: Byte = 13
  private val HeaderBytes = 9

  def open(pager: Pager): Either[StorageError, TableBTree] =
    if pager.pageCount == 0 then
      pager
        .allocate()
        .flatMap: root =>
          pager
            .write(root, encode(Leaf(Vector.empty, None), pager.pageSize))
            .map(_ => TableBTree(pager, root))
    else
      val root = PageId(0)
      pager.read(root).flatMap(decode).map(_ => TableBTree(pager, root))

  private def encodedSize(leaf: Leaf): Int =
    HeaderBytes + leaf.entries.map((_, value) => 12 + value.length).sum

  private def encode(leaf: Leaf, pageSize: Int): Array[Byte] =
    val buffer = ByteBuffer.allocate(pageSize)
    buffer.put(LeafKind)
    buffer.putInt(leaf.next.fold(-1)(_.value))
    buffer.putInt(leaf.entries.size)
    leaf.entries.foreach: (key, value) =>
      buffer.putLong(key)
      buffer.putInt(value.length)
      buffer.put(value)
    buffer.array()

  private def decode(bytes: Array[Byte]): Either[StorageError, Leaf] =
    try
      val buffer = ByteBuffer.wrap(bytes)
      if buffer.get() != LeafKind then
        Left(StorageError("expected a table leaf page"))
      else
        val rawNext = buffer.getInt()
        val count = buffer.getInt()
        if count < 0 then Left(StorageError("negative cell count"))
        else
          val entries = Vector.newBuilder[(Long, Array[Byte])]
          var previous: Option[Long] = None
          (0 until count).foreach: _ =>
            val key = buffer.getLong()
            val length = buffer.getInt()
            if length < 0 || length > buffer.remaining() then
              throw StorageError("invalid cell length")
            if previous.exists(_ >= key) then
              throw StorageError("leaf keys are not strictly ordered")
            val value = Array.ofDim[Byte](length); buffer.get(value)
            entries += key -> value; previous = Some(key)
          Right(
            Leaf(entries.result(), Option.when(rawNext >= 0)(PageId(rawNext)))
          )
    catch
      case error: StorageError                  => Left(error)
      case _: java.nio.BufferUnderflowException =>
        Left(StorageError("truncated B-tree page"))
