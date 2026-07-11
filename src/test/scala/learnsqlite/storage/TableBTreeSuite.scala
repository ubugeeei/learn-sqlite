package learnsqlite.storage

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files

class TableBTreeSuite extends munit.FunSuite:
  test("split leaves, preserve ordering, and reopen"):
    val path = Files.createTempDirectory("btree").resolve("tree.db")
    val pager = Pager.open(path, 512).toOption.get
    val tree = TableBTree.open(pager).toOption.get
    (100L to 1L by -1L).foreach: key =>
      assert(tree.insert(key, s"row-$key".getBytes(UTF_8)).isRight)
    assert(pager.pageCount > 1)
    assertEquals(tree.scan.toOption.get.map(_._1), (1L to 100L).toVector)
    assertEquals(new String(tree.get(42).toOption.flatten.get, UTF_8), "row-42")
    pager.force(); pager.close()

    val reopenedPager = Pager.open(path, 512).toOption.get
    val reopened = TableBTree.open(reopenedPager).toOption.get
    assertEquals(reopened.scan.toOption.get.size, 100)
    reopenedPager.close()

  test("reject duplicate keys without changing data"):
    val pager = Pager.open(Files.createTempDirectory("duplicate").resolve("tree.db"), 512).toOption.get
    val tree = TableBTree.open(pager).toOption.get
    assert(tree.insert(1, Array[Byte](1)).isRight)
    assert(tree.insert(1, Array[Byte](2)).isLeft)
    assertEquals(tree.get(1).toOption.flatten.get.toVector, Vector[Byte](1))
    pager.close()

  test("defensively copy values at the storage boundary"):
    val pager = Pager.open(Files.createTempDirectory("copy").resolve("tree.db"), 512).toOption.get
    val tree = TableBTree.open(pager).toOption.get
    val input = Array[Byte](1, 2, 3)
    assert(tree.insert(1, input).isRight)
    input(0) = 9
    val output = tree.get(1).toOption.flatten.get; output(1) = 9
    assertEquals(tree.get(1).toOption.flatten.get.toVector, Vector[Byte](1, 2, 3))
    pager.close()
