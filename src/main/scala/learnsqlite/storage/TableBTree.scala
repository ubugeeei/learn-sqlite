package learnsqlite.storage

import java.nio.ByteBuffer

/**
 * Durable table B+tree keyed by signed 64-bit row ids.
 *
 * Leaf pages contain row payloads and a next-leaf link. Interior pages contain separator keys and
 * child page ids. Insertion recursively propagates splits toward a stable root page, following the
 * table B-tree structure described by
 * [[https://www.sqlite.org/fileformat.html#b_tree_pages B-tree Pages]]. The byte layout remains a
 * smaller private teaching format rather than SQLite's exact cell-pointer layout.
 */
final class TableBTree private (pager: Pager, root: PageId):
  import TableBTree.*

  /** Finds one payload by descending interior separators. */
  def get(key: Long): Either[StorageError, Option[Array[Byte]]] =
    find(root, key).map(_.flatMap(_.entries.find(_._1 == key).map(_._2.clone())))

  /** Scans all leaf cells in ascending key order. */
  def scan: Either[StorageError, Vector[(Long, Array[Byte])]] =
    leftmostLeaf(root).flatMap: first =>
      def loop(
        id: PageId,
        rows: Vector[(Long, Array[Byte])]
      ): Either[StorageError, Vector[(Long, Array[Byte])]] =
        read(id).flatMap:
          case leaf: Leaf =>
            val appended = rows ++ leaf.entries.map((key, bytes) => key -> bytes.clone())
            leaf.next.fold(Right(appended))(loop(_, appended))
          case _: Interior => Left(StorageError("leaf chain points to an interior page"))
      loop(first, Vector.empty)

  /** Inserts a unique key and propagates any split to the stable root. */
  def insert(key: Long, value: Array[Byte]): Either[StorageError, Unit] =
    if LeafHeaderBytes + 12 + value.length > pager.pageSize then
      Left(StorageError("payload is too large for a leaf page; overflow pages are not implemented"))
    else
      insertInto(root, key, value.clone()).flatMap:
        case None        => Right(())
        case Some(split) => growRoot(split)

  /**
   * Replaces all entries while preserving the root page id.
   *
   * Existing descendant pages become unreachable and await the freelist milestone. This operation
   * is used by the current simple DELETE implementation.
   */
  def replace(entries: Vector[(Long, Array[Byte])]): Either[StorageError, Unit] =
    val duplicate = entries.map(_._1).groupBy(identity).exists(_._2.size > 1)
    if duplicate then Left(StorageError("replacement contains duplicate keys"))
    else
      write(root, Leaf(Vector.empty, None)).flatMap: _ =>
        entries.sortBy(_._1).foldLeft(Right(()): Either[StorageError, Unit]):
          case (result, (key, value)) => result.flatMap(_ => insert(key, value))

  private def find(id: PageId, key: Long): Either[StorageError, Option[Leaf]] =
    read(id).flatMap:
      case leaf: Leaf         => Right(Some(leaf))
      case interior: Interior => find(interior.childFor(key), key)

  private def insertInto(
    id: PageId,
    key: Long,
    value: Array[Byte]
  ): Either[StorageError, Option[Split]] =
    read(id).flatMap:
      case leaf: Leaf         => insertLeaf(id, leaf, key, value)
      case interior: Interior => insertInterior(id, interior, key, value)

  private def insertLeaf(
    id: PageId,
    leaf: Leaf,
    key: Long,
    value: Array[Byte]
  ): Either[StorageError, Option[Split]] =
    if leaf.entries.exists(_._1 == key) then Left(StorageError(s"duplicate key: $key"))
    else
      val entries = (leaf.entries :+ (key -> value)).sortBy(_._1)
      val updated = leaf.copy(entries = entries)
      if encodedSize(updated) <= pager.pageSize then write(id, updated).map(_ => None)
      else
        val middle = entries.size / 2
        val leftEntries = entries.take(middle)
        val rightEntries = entries.drop(middle)
        for
          rightId <- pager.allocate()
          _ <- write(id, Leaf(leftEntries, Some(rightId)))
          _ <- write(rightId, Leaf(rightEntries, leaf.next))
        yield Some(Split(rightEntries.head._1, rightId))

  private def insertInterior(
    id: PageId,
    interior: Interior,
    key: Long,
    value: Array[Byte]
  ): Either[StorageError, Option[Split]] =
    val childIndex = interior.childIndex(key)
    insertInto(interior.children(childIndex), key, value).flatMap:
      case None => Right(None)
      case Some(childSplit) =>
        val expanded = interior.insert(childIndex, childSplit)
        if encodedSize(expanded) <= pager.pageSize then write(id, expanded).map(_ => None)
        else splitInterior(id, expanded)

  private def splitInterior(id: PageId, node: Interior): Either[StorageError, Option[Split]] =
    val middle = node.keys.size / 2
    val promoted = node.keys(middle)
    val left = Interior(node.keys.take(middle), node.children.take(middle + 1))
    val right = Interior(node.keys.drop(middle + 1), node.children.drop(middle + 1))
    for
      rightId <- pager.allocate()
      _ <- write(id, left)
      _ <- write(rightId, right)
    yield Some(Split(promoted, rightId))

  private def growRoot(split: Split): Either[StorageError, Unit] =
    for
      rootBytes <- pager.read(root)
      leftId <- pager.allocate()
      _ <- pager.write(leftId, rootBytes)
      _ <- write(root, Interior(Vector(split.separator), Vector(leftId, split.right)))
    yield ()

  private def leftmostLeaf(id: PageId): Either[StorageError, PageId] =
    read(id).flatMap:
      case _: Leaf            => Right(id)
      case interior: Interior => leftmostLeaf(interior.children.head)

  private def read(id: PageId): Either[StorageError, Node] = pager.read(id).flatMap(decode)
  private def write(id: PageId, node: Node): Either[StorageError, Unit] =
    encode(node, pager.pageSize).flatMap(pager.write(id, _))

object TableBTree:
  sealed private trait Node
  final private case class Leaf(entries: Vector[(Long, Array[Byte])], next: Option[PageId])
      extends Node
  final private case class Interior(keys: Vector[Long], children: Vector[PageId]) extends Node:
    require(children.size == keys.size + 1, "an interior node has one more child than keys")

    def childIndex(key: Long): Int = keys.indexWhere(key < _) match
      case -1    => keys.size
      case index => index

    def childFor(key: Long): PageId = children(childIndex(key))

    def insert(leftChildIndex: Int, split: Split): Interior =
      Interior(
        keys.patch(leftChildIndex, Vector(split.separator), 0),
        children.patch(leftChildIndex + 1, Vector(split.right), 0)
      )

  final private case class Split(separator: Long, right: PageId)
  private val InteriorKind: Byte = 5
  private val LeafKind: Byte = 13
  private val LeafHeaderBytes = 9
  private val InteriorHeaderBytes = 9

  /** Opens the page-zero tree, creating it for a fresh pager. */
  def open(pager: Pager): Either[StorageError, TableBTree] =
    if pager.pageCount == 0 then create(pager).map(_._2)
    else at(pager, PageId(0))

  /** Allocates an empty tree and returns its stable root page. */
  def create(pager: Pager): Either[StorageError, (PageId, TableBTree)] =
    pager.allocate().flatMap: root =>
      val tree = TableBTree(pager, root)
      tree.write(root, Leaf(Vector.empty, None)).map(_ => root -> tree)

  /** Opens and validates a tree rooted at a catalog-provided page id. */
  def at(pager: Pager, root: PageId): Either[StorageError, TableBTree] =
    pager.read(root).flatMap(decode).map(_ => TableBTree(pager, root))

  private def encodedSize(node: Node): Int = node match
    case Leaf(entries, _)  => LeafHeaderBytes + entries.map((_, value) => 12 + value.length).sum
    case Interior(keys, _) => InteriorHeaderBytes + keys.size * 12

  private def encode(node: Node, pageSize: Int): Either[StorageError, Array[Byte]] =
    if encodedSize(node) > pageSize then Left(StorageError("B-tree node exceeds one page"))
    else
      val buffer = ByteBuffer.allocate(pageSize)
      node match
        case Leaf(entries, next) =>
          buffer.put(LeafKind).putInt(next.fold(-1)(_.value)).putInt(entries.size)
          entries.foreach: (key, value) =>
            buffer.putLong(key).putInt(value.length).put(value)
        case Interior(keys, children) =>
          buffer.put(InteriorKind).putInt(keys.size).putInt(children.head.value)
          keys.zip(children.tail).foreach: (key, child) =>
            buffer.putLong(key).putInt(child.value)
      Right(buffer.array())

  private def decode(bytes: Array[Byte]): Either[StorageError, Node] =
    try
      val buffer = ByteBuffer.wrap(bytes)
      buffer.get() match
        case LeafKind     => decodeLeaf(buffer)
        case InteriorKind => decodeInterior(buffer)
        case kind         => Left(StorageError(s"unknown B-tree page kind: $kind"))
    catch
      case error: StorageError                  => Left(error)
      case _: java.nio.BufferUnderflowException => Left(StorageError("truncated B-tree page"))

  private def decodeLeaf(buffer: ByteBuffer): Either[StorageError, Leaf] =
    val rawNext = buffer.getInt()
    val count = buffer.getInt()
    if count < 0 then Left(StorageError("negative leaf cell count"))
    else
      val entries = Vector.newBuilder[(Long, Array[Byte])]
      var previous: Option[Long] = None
      (0 until count).foreach: _ =>
        val key = buffer.getLong()
        val length = buffer.getInt()
        if length < 0 || length > buffer.remaining() then
          throw StorageError("invalid leaf payload length")
        if previous.exists(_ >= key) then throw StorageError("leaf keys are not strictly ordered")
        val value = Array.ofDim[Byte](length)
        buffer.get(value)
        entries += key -> value
        previous = Some(key)
      Right(Leaf(entries.result(), Option.when(rawNext >= 0)(PageId(rawNext))))

  private def decodeInterior(buffer: ByteBuffer): Either[StorageError, Interior] =
    val count = buffer.getInt()
    if count <= 0 then Left(StorageError("interior page must have at least one separator"))
    else
      val children = Vector.newBuilder[PageId]
      val keys = Vector.newBuilder[Long]
      children += PageId(buffer.getInt())
      var previous: Option[Long] = None
      (0 until count).foreach: _ =>
        val key = buffer.getLong()
        if previous.exists(_ >= key) then
          throw StorageError("interior keys are not strictly ordered")
        keys += key
        children += PageId(buffer.getInt())
        previous = Some(key)
      Right(Interior(keys.result(), children.result()))
