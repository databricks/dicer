package com.databricks.caching.util
import scala.collection.mutable

import com.databricks.caching.util.TestUtils.assertThrow
import com.databricks.testing.DatabricksTest

class AsciiTableSuite extends DatabricksTest {

  test("AsciiTable simple") {
    // Test plan: create a table with two columns, add three rows, and verify the output.
    val table = new AsciiTable(AsciiTable.Header("Name", 10), AsciiTable.Header("Age", 3))
      .appendRow("Alice", "30")
      .appendRow("Bob", "20")
      .appendRow("Charlie", "40")
    assert(
      table.toString() ==
      """┌─────────┬─────┐
        |│ Name    │ Age │
        |├─────────┼─────┤
        |│ Alice   │ 30  │
        |│ Bob     │ 20  │
        |│ Charlie │ 40  │
        |└─────────┴─────┘
        |""".stripMargin
    )
  }

  test("appendNextChunk returns None when table is empty") {
    // Test plan: verify that appendNextChunk returns None for an empty table and that the header
    // block and bottom border are still written.

    // Empty table: header block + bottom border is written; None is returned.
    val emptyTable = new AsciiTable(AsciiTable.Header("X"), AsciiTable.Header("Y"))
    val emptyBuilder = new mutable.StringBuilder()
    assert(
      emptyTable.appendNextChunk(emptyBuilder, AsciiTable.Cursor.begin, maxChars = 1000).isEmpty
    )
    assert(
      emptyBuilder.toString() ==
      """┌───┬───┐
        |│ X │ Y │
        |├───┼───┤
        |└───┴───┘
        |""".stripMargin
    )
  }

  test("appendNextChunk returns None when cursor is past end") {
    // Test plan: verify that appendNextChunk returns None while appending the table content
    // to the string builder.
    val table = new AsciiTable(AsciiTable.Header("X"), AsciiTable.Header("Y")).appendRow("a", "b")
    val builder = new mutable.StringBuilder()
    val result: Option[AsciiTable.Cursor] =
      table.appendNextChunk(builder, AsciiTable.Cursor.begin, maxChars = 1000)
    assert(result.isEmpty)
    assert(
      builder.toString() ==
      """┌───┬───┐
        |│ X │ Y │
        |├───┼───┤
        |│ a │ b │
        |└───┴───┘
        |""".stripMargin
    )
  }

  test("appendNextChunk returns all rows in one chunk when budget is large") {
    // Test plan: verify that a single appendNextChunk call with a large budget consumes all rows
    // and returns None, and that the written chunk equals the full table.toString() output.
    val table = new AsciiTable(AsciiTable.Header("Name"), AsciiTable.Header("Age"))
      .appendRow("Alice", "30")
      .appendRow("Bob", "20")
      .appendRow("Charlie", "40")

    val builder = new mutable.StringBuilder()
    val result: Option[AsciiTable.Cursor] =
      table.appendNextChunk(builder, AsciiTable.Cursor.begin, maxChars = 10000)
    assert(result.isEmpty) // single chunk, no more rows
    assert(
      builder.toString() ==
      """┌─────────┬─────┐
        |│ Name    │ Age │
        |├─────────┼─────┤
        |│ Alice   │ 30  │
        |│ Bob     │ 20  │
        |│ Charlie │ 40  │
        |└─────────┴─────┘
        |""".stripMargin
    )

  }

  test("appendNextChunk splits rows across multiple chunks") {
    // Test plan: verify that appendNextChunk packs as many whole rows as the per-call maxChars
    // budget allows and respects different character budgets for each chunk.
    val table = new AsciiTable(AsciiTable.Header("Name"), AsciiTable.Header("Age"))
      .appendRow("Alice", "10")
      .appendRow("Bob", "20")
      .appendRow("Charlie", "30")
      .appendRow("Diana", "40")
      .appendRow("Eddie", "50")
      .appendRow("Fried", "60")

    // All lines have the same character length (column widths fixed at call time).
    // Starting budget = header(3 lines) + 1 data row + bottom border = 5 lines.
    val lineCharLen: Int = table.toString().split("\n")(0).length + 1 // +1 for '\n'
    var charBudget: Int = 5 * lineCharLen

    // Drive appendNextChunk in a loop; each call writes to a fresh builder.
    val chunks = mutable.ArrayBuffer[String]()
    var cursorOpt: Option[AsciiTable.Cursor] = Some(AsciiTable.Cursor.begin)
    while (cursorOpt.isDefined) {
      val chunkBuilder = new mutable.StringBuilder()
      cursorOpt = table.appendNextChunk(chunkBuilder, cursorOpt.get, charBudget)
      charBudget += lineCharLen // add 1 more data row to the budget
      chunks += chunkBuilder.toString()
    }

    assert(chunks.size == 3)
    assert(
      chunks(0) ==
      """┌─────────┬─────┐
        |│ Name    │ Age │
        |├─────────┼─────┤
        |│ Alice   │ 10  │
        |└─────────┴─────┘
        |""".stripMargin
    )
    assert(
      chunks(1) ==
      """┌─────────┬─────┐
        |│ Name    │ Age │
        |├─────────┼─────┤
        |│ Bob     │ 20  │
        |│ Charlie │ 30  │
        |└─────────┴─────┘
        |""".stripMargin
    )
    assert(
      chunks(2) ==
      """┌─────────┬─────┐
        |│ Name    │ Age │
        |├─────────┼─────┤
        |│ Diana   │ 40  │
        |│ Eddie   │ 50  │
        |│ Fried   │ 60  │
        |└─────────┴─────┘
        |""".stripMargin
    )
  }

  test("appendNextChunk bumps budget to floor when maxChars is too small") {
    // Test plan: pass a maxChars of 1 (smaller than any viable chunk) and verify that
    // appendNextChunk still produces a well-formed, complete table rather than throwing, and
    // that the output equals table.toString().
    val table = new AsciiTable(AsciiTable.Header("Name"), AsciiTable.Header("Age"))
      .appendRow("Alice", "30")

    val builder = new mutable.StringBuilder()
    val result: Option[AsciiTable.Cursor] =
      table.appendNextChunk(builder, AsciiTable.Cursor.begin, maxChars = 1)
    assert(result.isEmpty) // only 1 row, so all consumed
    assert(
      builder.toString() ==
      """┌───────┬─────┐
        |│ Name  │ Age │
        |├───────┼─────┤
        |│ Alice │ 30  │
        |└───────┴─────┘
        |""".stripMargin
    )
  }

  test("appendNextChunk includes rows appended between calls, including width changes") {
    // Test plan: verify that rows appended to the table between calls to appendNextChunk are
    // included in future chunks. Also verify that a newly appended row that widens a column
    // changes the character length of subsequent chunks but not of already-rendered chunks.
    val table = new AsciiTable(AsciiTable.Header("Name"), AsciiTable.Header("Age"))
      .appendRow("Alice", "30")
      .appendRow("Bob", "20")

    // Budget tight enough for 1 row when Name col width = 5 ("Alice").
    val lineCharLen: Int = table.toString().split("\n")(0).length + 1
    val tightBudget: Int = 5 * lineCharLen

    // First chunk: Alice, rendered with Name col width = 5.
    val aliceBuilder = new mutable.StringBuilder()
    val cursorAfterAlice: AsciiTable.Cursor =
      table.appendNextChunk(aliceBuilder, AsciiTable.Cursor.begin, tightBudget).get

    // Append a wider row BEFORE consuming the next chunk.
    table.appendRow("Alexander", "25") // "Alexander" = 9 chars → widens Name col to 9

    // Second chunk (Bob) and third chunk (Alexander) are rendered with Name col width = 9.
    val bobBuilder = new mutable.StringBuilder()
    val cursorAfterBob: AsciiTable.Cursor =
      table.appendNextChunk(bobBuilder, cursorAfterAlice, tightBudget).get
    val alexanderBuilder = new mutable.StringBuilder()
    table.appendNextChunk(alexanderBuilder, cursorAfterBob, tightBudget)

    assert(
      aliceBuilder.toString() ==
      """┌───────┬─────┐
        |│ Name  │ Age │
        |├───────┼─────┤
        |│ Alice │ 30  │
        |└───────┴─────┘
        |""".stripMargin
    )

    // Name col is now 9 wide because "Alexander" was added before these chunks were rendered.
    assert(
      bobBuilder.toString() ==
      """┌───────────┬─────┐
        |│ Name      │ Age │
        |├───────────┼─────┤
        |│ Bob       │ 20  │
        |└───────────┴─────┘
        |""".stripMargin
    )

    assert(
      alexanderBuilder.toString() ==
      """┌───────────┬─────┐
        |│ Name      │ Age │
        |├───────────┼─────┤
        |│ Alexander │ 25  │
        |└───────────┴─────┘
        |""".stripMargin
    )
  }

  test("appendNextChunk throws when maxChars is not positive") {
    // Test plan: verify that appendNextChunk throws IllegalArgumentException for maxChars <= 0,
    // covering both zero and a negative value.
    val table = new AsciiTable(AsciiTable.Header("X")).appendRow("a")
    assertThrow[IllegalArgumentException]("`maxChars` must be > 0") {
      table.appendNextChunk(new mutable.StringBuilder(), AsciiTable.Cursor.begin, maxChars = 0)
    }
    assertThrow[IllegalArgumentException]("`maxChars` must be > 0") {
      table.appendNextChunk(new mutable.StringBuilder(), AsciiTable.Cursor.begin, maxChars = -1)
    }
  }

  test("AsciiTable truncation") {
    // Test plan: create a table with three columns each with a different max width. Add three rows,
    // each containing values overflowing one of the columns, and verify the output renders the
    // truncated values correctly.
    val table = new AsciiTable(
      AsciiTable.Header("Foo", 5),
      AsciiTable.Header("Bar", 3),
      AsciiTable.Header("Foobar", 8)
    ).appendRow("123456", "123", "12345678")
      .appendRow("12345", "1234", "12345678")
      .appendRow("12345", "123", "123456789")
    assert(
      table.toString() ==
      """┌───────┬─────┬──────────┐
        |│ Foo   │ Bar │ Foobar   │
        |├───────┼─────┼──────────┤
        |│ 12... │ 123 │ 12345678 │
        |│ 12345 │ ... │ 12345678 │
        |│ 12345 │ 123 │ 12345... │
        |└───────┴─────┴──────────┘
        |""".stripMargin
    )
  }
}
