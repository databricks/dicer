package com.databricks.caching.util

import scala.collection.mutable

import com.databricks.caching.util.AsciiTable.Header
import javax.annotation.concurrent.NotThreadSafe

/**
 * A simple textual table printer.
 *
 * For example:
 *
 * {{{
 *   val table = new AsciiTable(Header("X"), Header("Y"))
 *           .appendRow("1", "2")
 *           .appendRow("3", "4")
 *   println(table.toString())
 * }}}
 *
 * prints:
 *
 * <pre>
 * ┌───┬───┐
 * │ X │ Y │
 * ├───┼───┤
 * │ 1 │ 2 │
 * │ 3 │ 4 │
 * └───┴───┘
 * </pre>
 *
 * Note: The table is not literally ASCII, because the class is used to output arbitrary Unicode
 * characters (such as the ∞ used for formatting Dicer slices), and includes Unicode box drawing
 * characters for the borders. We have verified that the table renders correctly in the environments
 * we require.
 */
@NotThreadSafe
class AsciiTable(headers: Header*) {
  import AsciiTable._

  /**
   * The maximum (possibly truncated) length of any value added to each column in the table.
   * Initialized to the lengths of the header names, and updated as rows are appended.
   */
  private val columnWidths: Array[Int] = headers.map { header: Header =>
    header.name.length
  }.toArray

  /** Rows (excluding headers) added to the table using [[appendRow()]]. */
  private val rows = new mutable.ArrayBuffer[Array[String]]()

  /**
   * REQUIRES: the number of `values` matches the number of `headers`.
   *
   * Appends a row with given values to the table.
   */
  def appendRow(values: String*): this.type = {
    require(values.length == columnWidths.length)
    val truncatedValues = new Array[String](values.length)
    for (i: Int <- values.indices) {
      val maxWidth: Int = headers(i).maxWidth
      val cell: String = values(i)
      val truncatedCell: String = if (cell.length > maxWidth) {
        cell.substring(0, maxWidth - TRUNCATION_SUFFIX.length) + TRUNCATION_SUFFIX
      } else {
        cell
      }
      truncatedValues(i) = truncatedCell
      columnWidths(i) = columnWidths(i).max(truncatedCell.length)
    }
    rows += truncatedValues
    this
  }

  /** Writes the table to the given string builder. */
  def appendTo(builder: mutable.StringBuilder): Unit = {
    // Since the maximum string length is Int.MaxValue, this will append
    // the entire table to the builder.
    appendNextChunk(builder, Cursor.begin, maxChars = Int.MaxValue)
  }

  override def toString(): String = {
    val builder = new mutable.StringBuilder
    appendTo(builder)
    builder.toString()
  }

  /**
   * Appends a self-contained chunk of at most `maxChars` characters to `builder`,
   * starting at `from`. Returns a cursor to the first unrendered row, or [[None]] when the
   * table is fully rendered.
   *
   * Each chunk includes the header block. `maxChars` is a soft target: the budget
   * is silently raised to fit at least one data row if necessary.
   *
   * This method is read-only: it does not modify the table or consume its rows. Rows appended
   * via [[appendRow]] between calls are included in future chunks, though they may widen the
   * table and change the character length of subsequent rows.
   *
   * @param builder  string builder to append the chunk to.
   * @param from     cursor indicating the first data row to include in this chunk.
   * @param maxChars soft per-chunk character target; may be exceeded to fit at least one row.
   * @return Cursor pointing to the first row not yet rendered, or [[None]] when done.
   *
   * @throws IllegalArgumentException if `maxChars` <= 0.
   */
  def appendNextChunk(
      builder: mutable.StringBuilder,
      from: Cursor,
      maxChars: Int): Option[Cursor] = {
    require(maxChars > 0, "`maxChars` must be > 0")

    // Write the header block (top border + header row + separator) for every chunk.
    writeBorder(builder, Border.Top)
    writeCellRow(builder, headers.map((_: Header).name))
    writeBorder(builder, Border.HeaderBottom)
    // Baseline length of the builder before we add a data row.
    val builderLengthWithOnlyHeader: Int = builder.length

    // If there are no data rows starting at `from`, close the table and signal completion.
    if (from.tableRow >= rows.size) {
      writeBorder(builder, Border.Bottom)
      return None
    }

    // Write the first row unconditionally — guarantees at least one data row per chunk.
    writeCellRow(builder, rows(from.tableRow).toIndexedSeq)

    // All rows render to the same character length since columnWidths
    // is fixed at call time and the padding of every column is always the same.
    val rowCharLength: Int = builder.length - builderLengthWithOnlyHeader

    // Greedily append more rows while the budget can still fit a row and the bottom border.
    var currentRow: Int = from.tableRow + 1
    while (currentRow < rows.size && builder.length + 2 * rowCharLength <= maxChars) {
      writeCellRow(builder, rows(currentRow).toIndexedSeq)
      currentRow += 1
    }

    writeBorder(builder, Border.Bottom)
    if (currentRow < rows.size) Some(Cursor(currentRow)) else None
  }

  /**
   * Writes the given `cells` to `builder`, where cells are padded to fill out the corresponding
   * column widths and are separated by the column separator (" │ ").
   *
   * For example, for `cells=Seq(1, 2)`, this would append something like "│ 1 │ 2 │"
   */
  private def writeCellRow(builder: mutable.StringBuilder, cells: Seq[String]): Unit = {
    writeRow(builder, Border.Cells, cellsOpt = Some(cells))
  }

  /**
   * Writes a border row to `builder` using the given `border` type.
   *
   * For example, for [[Border.Top]], this will append ""┌───┬───┐".
   */
  private def writeBorder(builder: mutable.StringBuilder, border: Border): Unit = {
    writeRow(builder, border, cellsOpt = None)
  }

  /**
   * Writes a row to `builder` using the given `border`, where the values of the cells are filled
   * with the padded values of the cells in by `cellsOpt`, and with empty padding otherwise.
   */
  private def writeRow(
      builder: mutable.StringBuilder,
      border: Border,
      cellsOpt: Option[Seq[String]]): Unit = {
    require(cellsOpt.isEmpty || cellsOpt.get.length == columnWidths.length)

    builder.append(border.left)
    for (i: Int <- columnWidths.indices) {
      val columnWidth: Int = columnWidths(i)
      // Append the padded value of the cell if provided, or empty padding otherwise.
      val cell: String = cellsOpt.map((_: Seq[String])(i)).getOrElse("")
      builder.append(cell.padTo(columnWidth, border.padding))
      if (i < columnWidths.length - 1) {
        builder.append(border.columnSeparator)
      } else {
        builder.append(border.right)
      }
    }
    builder.append('\n')
  }
}

object AsciiTable {
  private val TRUNCATION_SUFFIX = "..."

  /**
   * Marks a position in an [[AsciiTable]] for sequential chunked rendering via
   * [[appendNextChunk]]. Obtain via [[Cursor.begin]] to start from the first data row.
   */
  case class Cursor private[AsciiTable] (private[AsciiTable] val tableRow: Int)
  object Cursor {

    /** Returns a cursor pointing at the first data row. */
    def begin: Cursor = Cursor(tableRow = 0)
  }

  /**
   * REQUIRES: `maxWidth` is at least as long as `name` and `TRUNCATION_SUFFIX`.
   *
   * A header for a column in an [[AsciiTable]].
   *
   * @param name the name of the column
   * @param maxWidth the maximum width of the column. If a column value exceeds this width, it will
   *                 be truncated to fit, with a "..." suffix.
   */
  case class Header(name: String, maxWidth: Int = 100) {
    require(maxWidth >= name.length)
    require(maxWidth >= TRUNCATION_SUFFIX.length)
  }

  /** A trait that describes the characters used to format different parts of a table border. */
  private sealed trait Border {
    def left: String
    def padding: Char
    def columnSeparator: String
    def right: String
  }
  private object Border {

    /** The [[Border]] for the top of a table, e.g. "┌───┬───┐" */
    case object Top extends Border {
      val left: String = "┌─"
      val padding: Char = '─'
      val columnSeparator: String = "─┬─"
      val right: String = "─┐"
    }

    /** The [[Border]] beneath the header of a table, e.g "├───┼───┤" */
    object HeaderBottom extends Border {
      val left: String = "├─"
      val padding: Char = '─'
      val columnSeparator: String = "─┼─"
      val right: String = "─┤"
    }

    /** The [[Border]] for the cells in a table, e.g. "│   │   │" */
    object Cells extends Border {
      val left: String = "│ "
      val padding: Char = ' '
      val columnSeparator: String = " │ "
      val right: String = " │"
    }

    /** The [[Border]] for the bottom of a table, e.g. "└───┴───┘" */
    object Bottom extends Border {
      val left: String = "└─"
      val padding: Char = '─'
      val columnSeparator: String = "─┴─"
      val right: String = "─┘"
    }
  }
}
