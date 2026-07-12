package learnsqlite.storage

import learnsqlite.core.Value

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8

/** Encodes rows using SQLite's record format and serial type codes.
  *
  * A record is a varint-sized header followed by payload bytes. The header
  * contains one varint serial type per value. See
  * [[https://www.sqlite.org/fileformat.html#record_format Record Format]].
  */
object RecordCodec:
  def encode(values: Vector[Value]): Either[StorageError, Array[Byte]] =
    val fields = values.map(field)
    val serialBytes = fields.map(value => Varint.size(value.serialType)).sum
    val headerSize = fixedPointHeaderSize(serialBytes)
    val totalSize = headerSize + fields.map(_.payload.length).sum
    val output = ByteBuffer.allocate(totalSize)
    for
      _ <- Varint.put(headerSize, output)
      _ <- traverseUnit(fields)(value => Varint.put(value.serialType, output))
    yield
      fields.foreach(value => output.put(value.payload))
      output.array()

  def decode(bytes: Array[Byte]): Either[StorageError, Vector[Value]] =
    val input = ByteBuffer.wrap(bytes)
    for
      headerSizeLong <- Varint.get(input)
      headerSize <- checkedInt(headerSizeLong, "record header size")
      _ <- Either.cond(
        headerSize >= input.position() && headerSize <= bytes.length,
        (),
        StorageError("invalid record header size")
      )
      serialTypes <- readSerialTypes(input, headerSize)
      values <- traverseVector(serialTypes)(serialType =>
        decodeField(serialType, input)
      )
      _ <- Either.cond(
        !input.hasRemaining,
        (),
        StorageError("record has trailing payload bytes")
      )
    yield values

  private final case class EncodedField(serialType: Long, payload: Array[Byte])

  private def field(value: Value): EncodedField = value match
    case Value.Null            => EncodedField(0, Array.emptyByteArray)
    case Value.Integer(0)      => EncodedField(8, Array.emptyByteArray)
    case Value.Integer(1)      => EncodedField(9, Array.emptyByteArray)
    case Value.Integer(number) =>
      val width = integerWidth(number)
      EncodedField(serialTypeForWidth(width), signedBytes(number, width))
    case Value.Real(number) =>
      val buffer = ByteBuffer.allocate(8); buffer.putDouble(number)
      EncodedField(7, buffer.array())
    case Value.Text(text) =>
      val bytes = text.getBytes(UTF_8)
      EncodedField(13L + bytes.length.toLong * 2, bytes)
    case Value.Blob(bytes) =>
      EncodedField(12L + bytes.length.toLong * 2, bytes.clone())

  private def fixedPointHeaderSize(serialBytes: Int): Int =
    var size = serialBytes + 1
    var next = serialBytes + Varint.size(size)
    while next != size do
      size = next
      next = serialBytes + Varint.size(size)
    size

  private def readSerialTypes(
      input: ByteBuffer,
      headerEnd: Int
  ): Either[StorageError, Vector[Long]] =
    val result = Vector.newBuilder[Long]
    while input.position() < headerEnd do
      Varint.get(input) match
        case Right(value) if input.position() <= headerEnd => result += value
        case Right(_)                                      =>
          return Left(StorageError("serial type crosses record header"))
        case Left(error) => return Left(error)
    Either.cond(
      input.position() == headerEnd,
      result.result(),
      StorageError("record header is misaligned")
    )

  private def decodeField(
      serialType: Long,
      input: ByteBuffer
  ): Either[StorageError, Value] = serialType match
    case 0 => Right(Value.Null)
    case 1 => readInteger(input, 1)
    case 2 => readInteger(input, 2)
    case 3 => readInteger(input, 3)
    case 4 => readInteger(input, 4)
    case 5 => readInteger(input, 6)
    case 6 => readInteger(input, 8)
    case 7 =>
      take(input, 8).map(bytes =>
        Value.Real(ByteBuffer.wrap(bytes).getDouble())
      )
    case 8       => Right(Value.Integer(0))
    case 9       => Right(Value.Integer(1))
    case 10 | 11 => Left(StorageError(s"reserved serial type: $serialType"))
    case value if value >= 12 =>
      checkedInt((value - 12) / 2, "field length").flatMap: length =>
        take(input, length).flatMap: bytes =>
          if value % 2 == 0 then Right(Value.blob(bytes))
          else
            val decoder = UTF_8.newDecoder()
            try
              Right(Value.Text(decoder.decode(ByteBuffer.wrap(bytes)).toString))
            catch
              case _: java.nio.charset.CharacterCodingException =>
                Left(StorageError("invalid UTF-8 text field"))
    case _ => Left(StorageError(s"invalid serial type: $serialType"))

  private def readInteger(
      input: ByteBuffer,
      width: Int
  ): Either[StorageError, Value] =
    take(input, width).map: bytes =>
      var result = if (bytes.head & 0x80) != 0 then -1L else 0L
      bytes.foreach(byte => result = (result << 8) | (byte & 0xffL))
      Value.Integer(result)

  private def integerWidth(value: Long): Int =
    if value >= Byte.MinValue && value <= Byte.MaxValue then 1
    else if value >= Short.MinValue && value <= Short.MaxValue then 2
    else if value >= -8_388_608 && value <= 8_388_607 then 3
    else if value >= Int.MinValue && value <= Int.MaxValue then 4
    else if value >= -140_737_488_355_328L && value <= 140_737_488_355_327L then
      6
    else 8

  private def serialTypeForWidth(width: Int): Long = width match
    case 1 => 1;
    case 2 => 2;
    case 3 => 3;
    case 4 => 4;
    case 6 => 5;
    case 8 => 6

  private def signedBytes(value: Long, width: Int): Array[Byte] =
    Array.tabulate(width)(index => (value >>> ((width - index - 1) * 8)).toByte)

  private def take(
      input: ByteBuffer,
      count: Int
  ): Either[StorageError, Array[Byte]] =
    if count < 0 || input.remaining() < count then
      Left(StorageError("truncated record payload"))
    else
      val bytes = Array.ofDim[Byte](count); input.get(bytes); Right(bytes)

  private def checkedInt(
      value: Long,
      label: String
  ): Either[StorageError, Int] =
    if value < 0 || value > Int.MaxValue then
      Left(StorageError(s"$label is too large"))
    else Right(value.toInt)

  private def traverseUnit[A](
      values: Vector[A]
  )(f: A => Either[StorageError, Unit]): Either[StorageError, Unit] =
    values.foldLeft(Right(()): Either[StorageError, Unit])((result, value) =>
      result.flatMap(_ => f(value))
    )

  private def traverseVector[A, B](values: Vector[A])(
      f: A => Either[StorageError, B]
  ): Either[StorageError, Vector[B]] =
    values.foldLeft(Right(Vector.empty): Either[StorageError, Vector[B]])(
      (result, value) => result.flatMap(acc => f(value).map(acc :+ _))
    )
