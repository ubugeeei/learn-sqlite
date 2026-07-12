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
    val pager = Pager
      .open(Files.createTempDirectory("duplicate").resolve("tree.db"), 512)
      .toOption
      .get
    val tree = TableBTree.open(pager).toOption.get
    assert(tree.insert(1, Array[Byte](1)).isRight)
    assert(tree.insert(1, Array[Byte](2)).isLeft)
    assertEquals(tree.get(1).toOption.flatten.get.toVector, Vector[Byte](1))
    pager.close()

  test("defensively copy values at the storage boundary"):
    val pager = Pager
      .open(Files.createTempDirectory("copy").resolve("tree.db"), 512)
      .toOption
      .get
    val tree = TableBTree.open(pager).toOption.get
    val input = Array[Byte](1, 2, 3)
    assert(tree.insert(1, input).isRight)
    input(0) = 9
    val output = tree.get(1).toOption.flatten.get; output(1) = 9
    assertEquals(
      tree.get(1).toOption.flatten.get.toVector,
      Vector[Byte](1, 2, 3)
    )
    pager.close()

  test("open independent trees by stable root page"):
    val pager = Pager
      .open(Files.createTempDirectory("roots").resolve("trees.db"), 512)
      .toOption
      .get
    val (firstRoot, first) = TableBTree.create(pager).toOption.get
    val (secondRoot, second) = TableBTree.create(pager).toOption.get
    assertNotEquals(firstRoot, secondRoot)
    assert(first.insert(1, Array[Byte](10)).isRight)
    assert(second.insert(1, Array[Byte](20)).isRight)
    assertEquals(
      TableBTree
        .at(pager, firstRoot)
        .flatMap(_.get(1))
        .toOption
        .flatten
        .get
        .toVector,
      Vector[Byte](10)
    )
    assertEquals(
      TableBTree
        .at(pager, secondRoot)
        .flatMap(_.get(1))
        .toOption
        .flatten
        .get
        .toVector,
      Vector[Byte](20)
    )
    pager.close()

  test("replace keeps the root usable and removes old keys"):
    val pager = Pager
      .open(Files.createTempDirectory("replace").resolve("tree.db"), 512)
      .toOption
      .get
    val tree = TableBTree.open(pager).toOption.get
    assert(tree.insert(1, Array[Byte](1)).isRight)
    assert(tree.insert(2, Array[Byte](2)).isRight)
    assert(tree.replace(Vector(3L -> Array[Byte](3))).isRight)
    assertEquals(tree.scan.toOption.get.map(_._1), Vector(3L))
    pager.close()

  private val largeInsertionOrders = Vector[(String, Vector[Long])](
    "ascending" -> (1L to 1_500L).toVector,
    "descending" -> (1_500L to 1L by -1L).toVector,
    "alternating edges" -> {
      val lows = (1L to 750L).toVector
      val highs = (1_500L to 751L by -1L).toVector
      lows.zip(highs).flatMap((low, high) => Vector(low, high))
    }
  )

  largeInsertionOrders.foreach: (scenario, keys) =>
    test(s"grow a multi-level tree under $scenario insertion"):
      val path = Files.createTempDirectory(s"deep-tree-$scenario").resolve("tree.db")
      val pager = Pager.open(path, 512).toOption.get
      val tree = TableBTree.open(pager).toOption.get
      val payload = Array.fill[Byte](32)(42)
      keys.foreach(key => assert(tree.insert(key, payload).isRight, s"failed at key $key"))
      assert(pager.pageCount > 100, "test data should require interior-page splits")
      assertEquals(tree.scan.toOption.get.map(_._1), (1L to 1_500L).toVector)
      Vector(1L, 2L, 749L, 750L, 751L, 1_499L, 1_500L).foreach: key =>
        assertEquals(tree.get(key).toOption.flatten.map(_.toVector), Some(payload.toVector))
      pager.force()
      pager.close()

      val reopenedPager = Pager.open(path, 512).toOption.get
      val reopened = TableBTree.open(reopenedPager).toOption.get
      assertEquals(reopened.scan.toOption.get.size, 1_500)
      assertEquals(reopened.get(1_234L).toOption.flatten.map(_.toVector), Some(payload.toVector))
      reopenedPager.close()

  test("reject a payload that needs overflow pages before mutating the tree"):
    val pager =
      Pager.open(Files.createTempDirectory("large-payload").resolve("tree.db"), 512).toOption.get
    val tree = TableBTree.open(pager).toOption.get
    val pagesBefore = pager.pageCount
    assert(tree.insert(1, Array.fill[Byte](512)(1)).left.toOption.get.message.contains("overflow"))
    assertEquals(pager.pageCount, pagesBefore)
    assertEquals(tree.scan, Right(Vector.empty))
    pager.close()
