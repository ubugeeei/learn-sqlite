package learnsqlite.storage

import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.{Files, Path}

opaque type PageId = Int
object PageId:
  def apply(value: Int): PageId =
    require(value >= 0, "page id must be non-negative")
    value
  extension (id: PageId) def value: Int = id

final case class StorageError(message: String) extends Exception(message)

/**
 * Fixed-size page I/O for the private LSQL format.
 *
 * The design mirrors SQLite's pager boundary, described by
 * [[https://www.sqlite.org/arch.html The Architecture Of SQLite]], while the bytes intentionally
 * use a distinct magic header to prevent format confusion.
 */
final class Pager private (
  val path: Path,
  val pageSize: Int,
  private val file: RandomAccessFile,
  private val channel: FileChannel
) extends AutoCloseable:
  import Pager.HeaderSize
  private var transactionJournal: Option[RollbackJournal] = None
  private val freePages = scala.collection.mutable.TreeSet.empty[Int]
  private var freePagesLoaded = false

  def pageCount: Int = ((channel.size() - HeaderSize) / pageSize).toInt

  /** Counts pages currently marked reusable. */
  def freePageCount: Either[StorageError, Int] = loadFreePages().map(_ => freePages.size)

  def allocate(): Either[StorageError, PageId] =
    loadFreePages().flatMap: _ =>
      freePages.headOption match
        case Some(index) =>
          val id = PageId(index)
          write(id, Array.fill(pageSize)(0.toByte)).map: _ =>
            freePages -= index
            id
        case None =>
          val id = PageId(pageCount)
          write(id, Array.fill(pageSize)(0.toByte)).map(_ => id)

  /** Marks a page reusable through an ordinary journaled page write. */
  def release(id: PageId): Either[StorageError, Unit] =
    if id.value >= pageCount then Left(StorageError(s"page ${id.value} is out of bounds"))
    else
      read(id).flatMap: bytes =>
        if bytes.headOption.contains(Pager.FreePageKind) then
          Left(StorageError(s"page ${id.value} is already free"))
        else
          val marker = Array.fill(pageSize)(0.toByte)
          marker(0) = Pager.FreePageKind
          write(id, marker).flatMap: _ =>
            loadFreePages().map(_ => freePages += id.value).map(_ => ())

  def read(id: PageId): Either[StorageError, Array[Byte]] =
    if id.value >= pageCount then
      Left(StorageError(s"page ${id.value} is out of bounds"))
    else
      val bytes = ByteBuffer.allocate(pageSize)
      var position = fileOffset(id)
      while bytes.hasRemaining do
        val count = channel.read(bytes, position)
        if count < 0 then
          return Left(StorageError(s"short read for page ${id.value}"))
        position += count
      Right(bytes.array())

  def write(id: PageId, bytes: Array[Byte]): Either[StorageError, Unit] =
    if bytes.length != pageSize then
      Left(StorageError(s"page must contain exactly $pageSize bytes"))
    else if id.value > pageCount then
      Left(StorageError("pages must be allocated sequentially"))
    else
      val capture = transactionJournal match
        case Some(journal) if id.value < pageCount => read(id).flatMap(journal.capture(id, _))
        case _                                     => Right(())
      capture.flatMap(_ => rawWrite(id, bytes))

  /**
   * Executes page changes as one rollback-journal transaction.
   *
   * A returned `Left` restores original pages immediately. A process crash leaves a hot journal,
   * which [[Pager.open]] restores before serving reads.
   */
  def transaction[A](operation: => Either[StorageError, A]): Either[StorageError, A] =
    if transactionJournal.nonEmpty then
      Left(StorageError("nested pager transactions are not supported"))
    else
      RollbackJournal.create(path, pageSize, pageCount).flatMap: journal =>
        transactionJournal = Some(journal)
        val result =
          try operation
          catch
            case error: Exception => Left(StorageError(s"transaction failed: ${error.getMessage}"))
        result match
          case Right(value) =>
            try
              force()
              transactionJournal = None
              journal.remove().map(_ => value)
            catch
              case error: java.io.IOException =>
                val _ = rollback(journal)
                Left(StorageError(s"failed to commit transaction: ${error.getMessage}"))
          case Left(error) =>
            rollback(journal) match
              case Right(_)            => Left(error)
              case Left(rollbackError) => Left(rollbackError)

  def force(): Unit = channel.force(true)
  override def close(): Unit =
    channel.close()
    file.close()
  private def fileOffset(id: PageId): Long =
    HeaderSize.toLong + id.value.toLong * pageSize

  private def rawWrite(id: PageId, bytes: Array[Byte]): Either[StorageError, Unit] =
    try
      val buffer = ByteBuffer.wrap(bytes.clone())
      var position = fileOffset(id)
      while buffer.hasRemaining do position += channel.write(buffer, position)
      Right(())
    catch
      case error: java.io.IOException =>
        Left(StorageError(s"page write failed: ${error.getMessage}"))

  private def loadFreePages(): Either[StorageError, Unit] =
    if freePagesLoaded then Right(())
    else
      freePages.clear()
      (0 until pageCount).foldLeft(Right(()): Either[StorageError, Unit]):
        case (result, index) =>
          result.flatMap: _ =>
            read(PageId(index)).map: bytes =>
              if bytes.headOption.contains(Pager.FreePageKind) then freePages += index
      .map: _ =>
        freePagesLoaded = true

  private def rollback(journal: RollbackJournal): Either[StorageError, Unit] =
    transactionJournal = None
    freePagesLoaded = false
    val restored = journal.beforeImages.foldLeft(Right(()): Either[StorageError, Unit]):
      case (result, (id, bytes)) => result.flatMap(_ => rawWrite(id, bytes))
    restored.flatMap: _ =>
      try
        file.setLength(HeaderSize.toLong + journal.originalPageCount.toLong * pageSize)
        force()
        journal.remove()
      catch
        case error: java.io.IOException =>
          Left(StorageError(s"rollback failed: ${error.getMessage}"))

object Pager:
  private val Magic =
    "LSQLDB01".getBytes(java.nio.charset.StandardCharsets.US_ASCII)
  private val HeaderSize = 16
  private val FreePageKind: Byte = 126
  val DefaultPageSize = 4096

  def open(
    path: Path,
    pageSize: Int = DefaultPageSize
  ): Either[StorageError, Pager] =
    if pageSize < 512 || Integer.bitCount(pageSize) != 1 then
      Left(
        StorageError("page size must be a power of two of at least 512 bytes")
      )
    else
      Option(path.getParent).foreach(Files.createDirectories(_))
      val fresh = !Files.exists(path) || Files.size(path) == 0
      val file = RandomAccessFile(path.toFile, "rw")
      val channel = file.getChannel
      if fresh then
        val header = ByteBuffer.allocate(HeaderSize)
        header.put(Magic).putInt(1).putInt(pageSize).flip()
        while header.hasRemaining do
          val _ = channel.write(header)
        channel.force(true)
        Right(Pager(path, pageSize, file, channel))
      else
        recover(path, pageSize, file, channel) match
          case Right(_) => validate(path, pageSize, file, channel)
          case Left(error) =>
            channel.close()
            file.close()
            Left(error)

  private def recover(
    path: Path,
    pageSize: Int,
    file: RandomAccessFile,
    channel: FileChannel
  ): Either[StorageError, Unit] =
    RollbackJournal.load(path, pageSize).flatMap:
      case None => Right(())
      case Some(journal) =>
        try
          journal.beforeImages.foreach: (id, bytes) =>
            val buffer = ByteBuffer.wrap(bytes)
            var position = HeaderSize.toLong + id.value.toLong * pageSize
            while buffer.hasRemaining do position += channel.write(buffer, position)
          file.setLength(HeaderSize.toLong + journal.originalPageCount.toLong * pageSize)
          channel.force(true)
          journal.remove()
        catch
          case error: java.io.IOException =>
            Left(StorageError(s"hot journal recovery failed: ${error.getMessage}"))

  private def validate(
    path: Path,
    requested: Int,
    file: RandomAccessFile,
    channel: FileChannel
  ) =
    val header = ByteBuffer.allocate(HeaderSize)
    while header.hasRemaining && channel.read(header) >= 0 do ()
    header.flip()
    val magic = Array.ofDim[Byte](Magic.length)
    if header.remaining() < HeaderSize then
      closeWith(file, channel, "database header is truncated")
    else
      header.get(magic)
      val version = header.getInt()
      val storedSize = header.getInt()
      if !java.util.Arrays.equals(magic, Magic) then
        closeWith(file, channel, "not an LSQL database")
      else if version != 1 then
        closeWith(file, channel, s"unsupported format version: $version")
      else if storedSize != requested then
        closeWith(file, channel, s"page size is $storedSize, not $requested")
      else if (channel.size() - HeaderSize) % storedSize != 0 then
        closeWith(file, channel, "database ends inside a page")
      else Right(Pager(path, storedSize, file, channel))

  private def closeWith(
    file: RandomAccessFile,
    channel: FileChannel,
    message: String
  ) =
    channel.close()
    file.close()
    Left(StorageError(message))
