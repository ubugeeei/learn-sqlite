package learnsqlite.storage

import learnsqlite.sql.{ColumnDefinition, Identifier}
import java.nio.file.Files

class CatalogSuite extends munit.FunSuite:
  test("persist multiple table schemas and roots across reopen"):
    val path = Files.createTempDirectory("catalog").resolve("database.db")
    val pager = Pager.open(path, 512).toOption.get
    val catalog = Catalog.open(pager).toOption.get
    val (usersRoot, _) = TableBTree.create(pager).toOption.get
    val (postsRoot, _) = TableBTree.create(pager).toOption.get
    val users = CatalogEntry(
      Identifier("Users"),
      Vector(
        ColumnDefinition(
          Identifier("id"),
          Some("INTEGER"),
          primaryKey = true,
          nullable = false
        ),
        ColumnDefinition(
          Identifier("name"),
          Some("TEXT"),
          primaryKey = false,
          nullable = true
        )
      ),
      usersRoot
    )
    val posts = CatalogEntry(
      Identifier("posts"),
      Vector(
        ColumnDefinition(
          Identifier("body"),
          Some("TEXT"),
          primaryKey = false,
          nullable = false
        )
      ),
      postsRoot
    )
    assert(catalog.add(users).isRight)
    assert(catalog.add(posts).isRight)
    assert(
      catalog.add(users).left.toOption.get.message.contains("already exists")
    )
    pager.force()
    pager.close()

    val reopenedPager = Pager.open(path, 512).toOption.get
    val reopened = Catalog.open(reopenedPager).toOption.get
    assertEquals(reopened.find(Identifier("USERS")), Right(Some(users)))
    assertEquals(
      reopened.tables.toOption.get.map(_.name.normalized),
      Vector("posts", "users")
    )
    reopenedPager.close()

  test("reject malformed catalog record shapes"):
    val pager = Pager
      .open(
        Files.createTempDirectory("bad-catalog").resolve("database.db"),
        512
      )
      .toOption
      .get
    val catalogTree = TableBTree.open(pager).toOption.get
    val invalid = RecordCodec
      .encode(Vector(learnsqlite.core.Value.Text("only-a-name")))
      .toOption
      .get
    assert(catalogTree.insert(1, invalid).isRight)
    assert(Catalog.open(pager).flatMap(_.tables).isLeft)
    pager.close()
