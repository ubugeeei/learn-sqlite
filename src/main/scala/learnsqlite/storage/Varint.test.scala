package learnsqlite.storage

import java.nio.ByteBuffer

class VarintSuite extends munit.FunSuite:
  private val boundaries = Vector(
    0L -> 1,
    127L -> 1,
    128L -> 2,
    16_383L -> 2,
    16_384L -> 3,
    (1L << 56) - 1 -> 8,
    (1L << 56) -> 9,
    Long.MaxValue -> 9,
    -1L -> 9
  )

  boundaries.foreach: (value, expectedSize) =>
    test(s"round-trip unsigned bits for $value in $expectedSize byte(s)"):
      val buffer = ByteBuffer.allocate(Varint.MaxBytes)
      assertEquals(Varint.size(value), expectedSize)
      assertEquals(Varint.put(value, buffer), Right(()))
      assertEquals(buffer.position(), expectedSize)
      buffer.flip()
      assertEquals(Varint.get(buffer), Right(value))

  test("reject a truncated continuation sequence"):
    assert(
      Varint
        .get(ByteBuffer.wrap(Array(0x80.toByte)))
        .left
        .toOption
        .get
        .message
        .contains("truncated")
    )

  test("refuse to overflow the destination"):
    assert(Varint.put(128, ByteBuffer.allocate(1)).isLeft)
