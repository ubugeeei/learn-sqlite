package learnsqlite.storage

import java.io.{
  BufferedInputStream,
  BufferedOutputStream,
  DataInputStream,
  DataOutputStream,
  FileInputStream,
  FileOutputStream
}
import java.nio.file.{Files, Path, StandardOpenOption}
import java.util.zip.CRC32

/**
 * Durable before-images for one pager transaction.
 *
 * The algorithm follows the ordering argument in
 * [[https://www.sqlite.org/atomiccommit.html Atomic Commit In SQLite]]: force original page images
 * before overwriting database pages, then delete the journal only after forcing the database. This
 * is a private teaching format, not SQLite's byte-compatible journal format.
 */
final private[storage] class RollbackJournal private (
  val path: Path,
  val originalPageCount: Int,
  val pageSize: Int,
  private var images: Vector[(PageId, Array[Byte])]
):
  private val captured = scala.collection.mutable.Set.from(images.map(_._1.value))

  /** Persists a page's original bytes exactly once before its first overwrite. */
  def capture(id: PageId, bytes: Array[Byte]): Either[StorageError, Unit] =
    if captured.contains(id.value) then Right(())
    else
      images :+= id -> bytes.clone()
      captured += id.value
      persist()

  /** Returns immutable copies of all original pages in capture order. */
  def beforeImages: Vector[(PageId, Array[Byte])] = images.map((id, bytes) => id -> bytes.clone())

  /** Removes the commit marker after database bytes are known durable. */
  def remove(): Either[StorageError, Unit] = io("remove rollback journal"):
    Files.deleteIfExists(path)
    forceDirectory(path)

  private def persist(): Either[StorageError, Unit] = io("write rollback journal"):
    Option(path.getParent).foreach(Files.createDirectories(_))
    val stream = FileOutputStream(path.toFile, false)
    val output = DataOutputStream(BufferedOutputStream(stream))
    try
      output.write(RollbackJournal.Magic)
      output.writeInt(RollbackJournal.Version)
      output.writeInt(pageSize)
      output.writeInt(originalPageCount)
      output.writeInt(images.size)
      images.foreach: (id, bytes) =>
        output.writeInt(id.value)
        output.write(bytes)
        output.writeInt(checksum(id, bytes))
      output.flush()
      stream.getFD.sync()
    finally output.close()

  private def checksum(id: PageId, bytes: Array[Byte]): Int =
    val crc = CRC32()
    crc.update(id.value)
    crc.update(bytes)
    crc.getValue.toInt

  private def forceDirectory(file: Path): Unit =
    Option(file.toAbsolutePath.getParent).foreach: directory =>
      try
        val channel = java.nio.channels.FileChannel.open(directory, StandardOpenOption.READ)
        try channel.force(true)
        finally channel.close()
      catch case _: java.io.IOException => () // Some filesystems cannot fsync directories.

  private def io[A](operation: String)(body: => A): Either[StorageError, A] =
    try Right(body)
    catch
      case error: java.io.IOException =>
        Left(StorageError(s"failed to $operation: ${error.getMessage}"))

private[storage] object RollbackJournal:
  private val Magic = "LSQLJRNL".getBytes(java.nio.charset.StandardCharsets.US_ASCII)
  private val Version = 1

  /** Creates and forces an empty journal before any database page changes. */
  def create(
    database: Path,
    pageSize: Int,
    originalPageCount: Int
  ): Either[StorageError, RollbackJournal] =
    val journal = RollbackJournal(pathFor(database), originalPageCount, pageSize, Vector.empty)
    journal.persist().map(_ => journal)

  /** Reads and validates a hot journal left by an interrupted writer. */
  def load(database: Path, expectedPageSize: Int): Either[StorageError, Option[RollbackJournal]] =
    val path = pathFor(database)
    if !Files.exists(path) then Right(None)
    else
      try
        val input = DataInputStream(BufferedInputStream(FileInputStream(path.toFile)))
        try
          val magic = Array.ofDim[Byte](Magic.length)
          input.readFully(magic)
          if !java.util.Arrays.equals(magic, Magic) then
            Left(StorageError("invalid rollback journal magic"))
          else if input.readInt() != Version then
            Left(StorageError("unsupported rollback journal version"))
          else
            val pageSize = input.readInt()
            val originalCount = input.readInt()
            val count = input.readInt()
            if pageSize != expectedPageSize || originalCount < 0 || count < 0 then
              Left(StorageError("invalid rollback journal header"))
            else
              val entries = Vector.newBuilder[(PageId, Array[Byte])]
              var index = 0
              while index < count do
                val id = PageId(input.readInt())
                val bytes = Array.ofDim[Byte](pageSize)
                input.readFully(bytes)
                val storedChecksum = input.readInt()
                val crc = CRC32(); crc.update(id.value); crc.update(bytes)
                if storedChecksum != crc.getValue.toInt then
                  throw StorageError("rollback journal checksum mismatch")
                entries += id -> bytes
                index += 1
              if input.read() != -1 then Left(StorageError("rollback journal has trailing bytes"))
              else Right(Some(RollbackJournal(path, originalCount, pageSize, entries.result())))
        finally input.close()
      catch
        case error: StorageError     => Left(error)
        case _: java.io.EOFException => Left(StorageError("rollback journal is truncated"))
        case error: java.io.IOException =>
          Left(StorageError(s"cannot read rollback journal: ${error.getMessage}"))

  def pathFor(database: Path): Path =
    database.resolveSibling(database.getFileName.toString + "-journal")
