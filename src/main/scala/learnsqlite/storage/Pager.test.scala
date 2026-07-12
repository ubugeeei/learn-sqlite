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
    assert(
      Pager.open(path, 512).left.toOption.get.message.contains("not an LSQL")
    )

  test("reject partial and incorrectly sized writes"):
    val path = Files.createTempDirectory("pager-errors").resolve("data.db")
    val pager = Pager.open(path, 512).toOption.get
    assert(pager.write(PageId(0), Array.emptyByteArray).isLeft)
    assert(pager.read(PageId(0)).isLeft)
    pager.close()

  test("rollback a failed transaction and remove newly allocated pages"):
    val path = Files.createTempDirectory("pager-rollback").resolve("data.db")
    val pager = Pager.open(path, 512).toOption.get
    val original = Array.fill[Byte](512)(1)
    val root = pager.allocate().toOption.get
    assert(pager.write(root, original).isRight)
    pager.force()

    val result = pager.transaction:
      for
        _ <- pager.write(root, Array.fill[Byte](512)(2))
        _ <- pager.allocate()
        failure <- Left(StorageError("injected failure"))
      yield failure

    assertEquals(result, Left(StorageError("injected failure")))
    assertEquals(pager.pageCount, 1)
    assert(java.util.Arrays.equals(pager.read(root).toOption.get, original))
    assert(!Files.exists(RollbackJournal.pathFor(path)))
    pager.close()

  test("commit forces database and removes the journal"):
    val path = Files.createTempDirectory("pager-commit").resolve("data.db")
    val pager = Pager.open(path, 512).toOption.get
    val root = pager.allocate().toOption.get
    assertEquals(pager.transaction(pager.write(root, Array.fill[Byte](512)(9))), Right(()))
    assert(!Files.exists(RollbackJournal.pathFor(path)))
    pager.close()

    val reopened = Pager.open(path, 512).toOption.get
    assertEquals(reopened.read(root).toOption.get.head, 9.toByte)
    reopened.close()

  test("recover a hot journal before exposing database pages"):
    val path = Files.createTempDirectory("pager-recovery").resolve("data.db")
    val pager = Pager.open(path, 512).toOption.get
    val root = pager.allocate().toOption.get
    val original = Array.fill[Byte](512)(3)
    assert(pager.write(root, original).isRight)
    pager.force()

    val journal = RollbackJournal.create(path, 512, pager.pageCount).toOption.get
    assert(journal.capture(root, original).isRight)
    assert(pager.write(root, Array.fill[Byte](512)(7)).isRight)
    assert(pager.allocate().isRight)
    pager.force()
    pager.close() // Simulate a process that died without commit cleanup.

    val recovered = Pager.open(path, 512).toOption.get
    assertEquals(recovered.pageCount, 1)
    assert(java.util.Arrays.equals(recovered.read(root).toOption.get, original))
    assert(!Files.exists(RollbackJournal.pathFor(path)))
    recovered.close()

  private val journalCorruptions = Vector[(String, Array[Byte] => Array[Byte])](
    "truncated bytes" -> (_.dropRight(3)),
    "checksum mismatch" -> (bytes => bytes.updated(bytes.length - 1, (bytes.last ^ 0xff).toByte)),
    "trailing bytes" -> (bytes => bytes :+ 0.toByte)
  )

  journalCorruptions.foreach: (scenario, corrupt) =>
    test(s"refuse to open with a hot journal containing $scenario"):
      val path = Files.createTempDirectory("bad-journal").resolve("data.db")
      val pager = Pager.open(path, 512).toOption.get
      val root = pager.allocate().toOption.get
      val original = Array.fill[Byte](512)(4)
      assert(pager.write(root, original).isRight)
      pager.force()
      val journal = RollbackJournal.create(path, 512, pager.pageCount).toOption.get
      assert(journal.capture(root, original).isRight)
      pager.close()

      val journalPath = RollbackJournal.pathFor(path)
      Files.write(journalPath, corrupt(Files.readAllBytes(journalPath)))
      assert(Pager.open(path, 512).isLeft)
