package learnsqlite.storage

import java.nio.ByteBuffer

/** A leaf cell's payload location: a local prefix plus an optional overflow chain. */
final private[storage] case class PayloadRef(
  totalLength: Int,
  local: Array[Byte],
  overflow: Option[PageId]
)

/**
 * Stores and reconstructs payload bytes that do not fit locally in a B+tree leaf cell.
 *
 * SQLite uses carefully calculated local payload fractions; see
 * [[https://www.sqlite.org/fileformat.html#cell_payload_overflow_pages Cell Payload Overflow Pages]].
 * This private format teaches the same ownership and chaining model with a fixed local prefix and
 * explicit per-page length.
 */
final private[storage] class OverflowPages(pager: Pager):
  import OverflowPages.*

  val localLimit: Int = math.min(64, pager.pageSize / 4)
  private val chunkCapacity = pager.pageSize - HeaderBytes

  /** Splits a payload into a local prefix and newly allocated overflow pages. */
  def store(payload: Array[Byte]): Either[StorageError, PayloadRef] =
    val bytes = payload.clone()
    val local = bytes.take(localLimit)
    val remaining = bytes.drop(local.length)
    if remaining.isEmpty then Right(PayloadRef(bytes.length, local, None))
    else
      val chunks = remaining.grouped(chunkCapacity).map(_.toArray).toVector
      allocateIds(chunks.size).flatMap: ids =>
        ids.indices.foldLeft(Right(()): Either[StorageError, Unit]):
          case (result, index) =>
            val next = ids.lift(index + 1)
            result.flatMap(_ => pager.write(ids(index), encode(chunks(index), next)))
        .map(_ => PayloadRef(bytes.length, local, ids.headOption))

  /** Reassembles and validates a payload, rejecting cycles and inconsistent lengths. */
  def load(reference: PayloadRef): Either[StorageError, Array[Byte]] =
    if reference.totalLength < 0 || reference.local.length > reference.totalLength then
      Left(StorageError("invalid local payload length"))
    else
      val remaining = reference.totalLength - reference.local.length
      (remaining, reference.overflow) match
        case (0, None)        => Right(reference.local.clone())
        case (0, Some(_))     => Left(StorageError("payload has an unnecessary overflow chain"))
        case (_, None)        => Left(StorageError("payload is missing its overflow chain"))
        case (_, Some(first)) => loadChain(first, remaining).map(reference.local.clone() ++ _)

  private def loadChain(first: PageId, expectedLength: Int): Either[StorageError, Array[Byte]] =
    val output = Array.newBuilder[Byte]
    val visited = scala.collection.mutable.Set.empty[Int]

    def loop(id: PageId, collected: Int): Either[StorageError, Array[Byte]] =
      if visited.contains(id.value) then Left(StorageError("overflow page cycle detected"))
      else if collected >= expectedLength then
        Left(StorageError("overflow chain is longer than payload"))
      else
        visited += id.value
        pager.read(id).flatMap(decode).flatMap: page =>
          val nextCollected = collected + page.bytes.length
          if nextCollected > expectedLength then
            Left(StorageError("overflow payload exceeds declared length"))
          else
            output ++= page.bytes
            (nextCollected == expectedLength, page.next) match
              case (true, None) => Right(output.result())
              case (true, Some(_)) =>
                Left(StorageError("overflow chain continues past payload end"))
              case (false, None) => Left(StorageError("overflow chain is shorter than payload"))
              case (false, Some(next)) => loop(next, nextCollected)
    loop(first, 0)

  private def allocateIds(count: Int): Either[StorageError, Vector[PageId]] =
    (0 until count).foldLeft(Right(Vector.empty): Either[StorageError, Vector[PageId]]):
      case (result, _) => result.flatMap(ids => pager.allocate().map(ids :+ _))

  private def encode(bytes: Array[Byte], next: Option[PageId]): Array[Byte] =
    val buffer = ByteBuffer.allocate(pager.pageSize)
    buffer.put(Kind).putInt(next.fold(-1)(_.value)).putInt(bytes.length).put(bytes)
    buffer.array()

  private def decode(bytes: Array[Byte]): Either[StorageError, OverflowPage] =
    try
      val buffer = ByteBuffer.wrap(bytes)
      if buffer.get() != Kind then Left(StorageError("expected an overflow page"))
      else
        val rawNext = buffer.getInt()
        val length = buffer.getInt()
        if length <= 0 || length > chunkCapacity then
          Left(StorageError("invalid overflow chunk length"))
        else
          val chunk = Array.ofDim[Byte](length)
          buffer.get(chunk)
          Right(OverflowPage(chunk, Option.when(rawNext >= 0)(PageId(rawNext))))
    catch case _: java.nio.BufferUnderflowException => Left(StorageError("truncated overflow page"))

private object OverflowPages:
  private val Kind: Byte = 15
  private val HeaderBytes = 9
  final private case class OverflowPage(bytes: Array[Byte], next: Option[PageId])
