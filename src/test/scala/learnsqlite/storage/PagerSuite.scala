package learnsqlite.storage

import java.nio.file.Files

class PagerSuite extends munit.FunSuite:
  test("allocate, write, force, reopen, and read whole pages"):
    val path = Files.createTempDirectory("pager").resolve("data.db")
    val pager = Pager.open(path, 512).toOption.get
    val id = pager.allocate().toOption.get
    val content = Array.tabulate[Byte](512)(index => (index % 127).toByte)
    assert(pager.write(id, content).isRight)
    pager.force()
    pager.close()
    val reopened = Pager.open(path, 512).toOption.get
    assertEquals(reopened.pageCount, 1)
    assert(java.util.Arrays.equals(reopened.read(id).toOption.get, content))
    reopened.close()

  test("reject invalid headers instead of opening SQLite files"):
    val path = Files.createTempFile("foreign", ".db")
    Files.writeString(path, "SQLite format 3 plus bytes")
    assert(Pager.open(path, 512).left.toOption.get.message.contains("not an LSQL"))

  test("reject partial and incorrectly sized writes"):
    val path = Files.createTempDirectory("pager-errors").resolve("data.db")
    val pager = Pager.open(path, 512).toOption.get
    assert(pager.write(PageId(0), Array.emptyByteArray).isLeft)
    assert(pager.read(PageId(0)).isLeft)
    pager.close()
