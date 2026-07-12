package learnsqlite.storage

import java.nio.ByteBuffer

/** SQLite's 1–9 byte, big-endian variable-length unsigned integer.
  *
  * The first eight bytes contribute seven bits each. A ninth byte, when
  * present, contributes all eight bits. See
  * [[https://www.sqlite.org/fileformat.html#varint Varint]]. Values are carried
  * in a signed `Long`; negative values represent unsigned values with bit 63
  * set, exactly as the SQLite serial representation needs.
  */
object Varint:
  val MaxBytes = 9

  def size(value: Long): Int =
    if (value & 0xff00000000000000L) != 0 then 9
    else
      var remaining = value
      var bytes = 1
      while remaining > 0x7f do
        remaining >>>= 7
        bytes += 1
      bytes

  def put(value: Long, target: ByteBuffer): Either[StorageError, Unit] =
    val length = size(value)
    if target.remaining() < length then
      Left(StorageError("not enough space for varint"))
    else
      if length == 9 then
        (7 to 0 by -1).foreach: shift =>
          target.put((((value >>> (shift * 7 + 8)) & 0x7f) | 0x80).toByte)
        target.put((value & 0xff).toByte)
      else
        (length - 1 to 0 by -1).foreach: index =>
          val continuation = if index == 0 then 0 else 0x80
          target.put((((value >>> (index * 7)) & 0x7f) | continuation).toByte)
      Right(())

  def get(source: ByteBuffer): Either[StorageError, Long] =
    var value = 0L
    var index = 0
    while index < 8 do
      if !source.hasRemaining then return Left(StorageError("truncated varint"))
      val byte = source.get() & 0xff
      value = (value << 7) | (byte & 0x7f)
      if (byte & 0x80) == 0 then return Right(value)
      index += 1
    if !source.hasRemaining then
      Left(StorageError("truncated nine-byte varint"))
    else Right((value << 8) | (source.get() & 0xffL))
