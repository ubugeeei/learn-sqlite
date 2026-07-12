package learnsqlite.storage

import learnsqlite.core.Value

class RecordCodecSuite extends munit.FunSuite:
  test("round-trip all five storage classes"):
    val values = Vector(
      Value.Null,
      Value.Integer(-9_000_000_000L),
      Value.Real(3.141592653589793),
      Value.Text("SQLite 🪶 Scala"),
      Value.blob(Array[Byte](0, -1, 42))
    )
    val decoded = RecordCodec.encode(values).flatMap(RecordCodec.decode)
    assertEquals(decoded.map(normalize), Right(normalize(values)))

  test("use compact integer serial types at every width"):
    val values = Vector[Long](
      -129,
      -128,
      127,
      128,
      32_767,
      32_768,
      8_388_607,
      8_388_608,
      Int.MaxValue.toLong + 1
    )
    values.foreach: value =>
      assertEquals(
        RecordCodec
          .encode(Vector(Value.Integer(value)))
          .flatMap(RecordCodec.decode),
        Right(Vector(Value.Integer(value)))
      )

  test("encode zero and one without payload bytes"):
    val encoded = RecordCodec
      .encode(Vector(Value.Integer(0), Value.Integer(1)))
      .toOption
      .get
    assertEquals(encoded.length, 3)

  test("reject truncated headers and payloads"):
    assert(RecordCodec.decode(Array(0x80.toByte)).isLeft)
    val encoded = RecordCodec.encode(Vector(Value.Text("hello"))).toOption.get
    assert(
      RecordCodec
        .decode(encoded.dropRight(1))
        .left
        .toOption
        .get
        .message
        .contains("truncated")
    )

  test("reject reserved serial types"):
    assert(
      RecordCodec
        .decode(Array[Byte](2, 10))
        .left
        .toOption
        .get
        .message
        .contains("reserved")
    )

  private def normalize(values: Vector[Value]): Vector[Any] = values.map:
    case Value.Blob(bytes) => bytes.toVector
    case other             => other
