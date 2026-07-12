package learnsqlite.storage

import java.nio.ByteBuffer
import java.nio.file.Files

class OverflowPagesSuite extends munit.FunSuite:
  private val sizes = Vector(0, 1, 63, 64, 65, 503, 504, 1_070, 4_096)

  sizes.foreach: size =>
    test(s"round-trip a $size-byte payload"):
      val pager = temporaryPager(s"overflow-$size")
      val overflow = OverflowPages(pager)
      val payload = Array.tabulate[Byte](size)(index => (index % 251).toByte)
      val reference = overflow.store(payload).toOption.get
      assertEquals(overflow.load(reference).toOption.get.toVector, payload.toVector)
      assertEquals(reference.overflow.nonEmpty, size > overflow.localLimit)
      pager.close()

  test("detect a cycle before returning partial payload"):
    val pager = temporaryPager("overflow-cycle")
    val overflow = OverflowPages(pager)
    val reference = overflow.store(Array.fill[Byte](1_200)(7)).toOption.get
    val first = reference.overflow.get
    val page = pager.read(first).toOption.get
    ByteBuffer.wrap(page).putInt(1, first.value)
    assert(pager.write(first, page).isRight)
    assert(overflow.load(reference).left.toOption.get.message.contains("cycle"))
    pager.close()

  private val corruptions = Vector[(String, Array[Byte] => Unit, String)](
    ("wrong page kind", page => page(0) = 99, "expected an overflow page"),
    (
      "invalid chunk length",
      page => { val _ = ByteBuffer.wrap(page).putInt(5, Int.MaxValue) },
      "chunk length"
    ),
    ("short chain", page => { val _ = ByteBuffer.wrap(page).putInt(1, -1) }, "shorter")
  )

  corruptions.foreach: (scenario, corrupt, expectedMessage) =>
    test(s"reject a $scenario"):
      val pager = temporaryPager(s"overflow-corrupt-$scenario")
      val overflow = OverflowPages(pager)
      val reference = overflow.store(Array.fill[Byte](1_200)(5)).toOption.get
      val first = reference.overflow.get
      val page = pager.read(first).toOption.get
      corrupt(page)
      assert(pager.write(first, page).isRight)
      assert(overflow.load(reference).left.toOption.get.message.contains(expectedMessage))
      pager.close()

  test("rollback truncates newly allocated overflow pages"):
    val pager = temporaryPager("overflow-rollback")
    val initialPages = pager.pageCount
    val overflow = OverflowPages(pager)
    val result = pager.transaction:
      overflow.store(Array.fill[Byte](2_000)(9)).flatMap(_ => Left(StorageError("injected")))
    assertEquals(result, Left(StorageError("injected")))
    assertEquals(pager.pageCount, initialPages)
    pager.close()

  private def temporaryPager(prefix: String): Pager =
    Pager.open(Files.createTempDirectory(prefix).resolve("data.db"), 512).toOption.get
