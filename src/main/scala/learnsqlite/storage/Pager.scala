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

/** Fixed-size page I/O for the private LSQL format.
  *
  * The design mirrors SQLite's pager boundary, described by
  * [[https://www.sqlite.org/arch.html The Architecture Of SQLite]], while the
  * bytes intentionally use a distinct magic header to prevent format confusion.
  */
final class Pager private (
    val path: Path,
    val pageSize: Int,
    private val file: RandomAccessFile,
    private val channel: FileChannel
) extends AutoCloseable:
  import Pager.HeaderSize

  def pageCount: Int = ((channel.size() - HeaderSize) / pageSize).toInt

  def allocate(): Either[StorageError, PageId] =
    val id = PageId(pageCount)
    write(id, Array.fill(pageSize)(0.toByte)).map(_ => id)

  def read(id: PageId): Either[StorageError, Array[Byte]] =
    if id.value >= pageCount then Left(StorageError(s"page ${id.value} is out of bounds"))
    else
      val bytes = ByteBuffer.allocate(pageSize)
      var position = fileOffset(id)
      while bytes.hasRemaining do
        val count = channel.read(bytes, position)
        if count < 0 then return Left(StorageError(s"short read for page ${id.value}"))
        position += count
      Right(bytes.array())

  def write(id: PageId, bytes: Array[Byte]): Either[StorageError, Unit] =
    if bytes.length != pageSize then Left(StorageError(s"page must contain exactly $pageSize bytes"))
    else if id.value > pageCount then Left(StorageError("pages must be allocated sequentially"))
    else
      val buffer = ByteBuffer.wrap(bytes.clone())
      var position = fileOffset(id)
      while buffer.hasRemaining do position += channel.write(buffer, position)
      Right(())

  def force(): Unit = channel.force(true)
  override def close(): Unit =
    channel.close()
    file.close()
  private def fileOffset(id: PageId): Long = HeaderSize.toLong + id.value.toLong * pageSize

object Pager:
  private val Magic = "LSQLDB01".getBytes(java.nio.charset.StandardCharsets.US_ASCII)
  private val HeaderSize = 16
  val DefaultPageSize = 4096

  def open(path: Path, pageSize: Int = DefaultPageSize): Either[StorageError, Pager] =
    if pageSize < 512 || Integer.bitCount(pageSize) != 1 then
      Left(StorageError("page size must be a power of two of at least 512 bytes"))
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
      else validate(path, pageSize, file, channel)

  private def validate(path: Path, requested: Int, file: RandomAccessFile, channel: FileChannel) =
    val header = ByteBuffer.allocate(HeaderSize)
    while header.hasRemaining && channel.read(header) >= 0 do ()
    header.flip()
    val magic = Array.ofDim[Byte](Magic.length)
    if header.remaining() < HeaderSize then closeWith(file, channel, "database header is truncated")
    else
      header.get(magic)
      val version = header.getInt()
      val storedSize = header.getInt()
      if !java.util.Arrays.equals(magic, Magic) then closeWith(file, channel, "not an LSQL database")
      else if version != 1 then closeWith(file, channel, s"unsupported format version: $version")
      else if storedSize != requested then closeWith(file, channel, s"page size is $storedSize, not $requested")
      else if (channel.size() - HeaderSize) % storedSize != 0 then closeWith(file, channel, "database ends inside a page")
      else Right(Pager(path, storedSize, file, channel))

  private def closeWith(file: RandomAccessFile, channel: FileChannel, message: String) =
    channel.close()
    file.close()
    Left(StorageError(message))
