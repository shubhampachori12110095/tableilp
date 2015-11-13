package org.allenai.ari.solvers.tableilp

import org.allenai.ari.models.tables.{ Metadata, ColumnMetadata, Table => DatastoreTable }
import org.allenai.ari.solvers.common.KeywordTokenizer
import org.allenai.common.Logging

case class TokenizedCell(values: Seq[String])

class Table(val fileName: String, fullContents: Seq[Seq[String]], tokenizer: KeywordTokenizer) extends Logging {
  // config: ignore "gray" columns in the KB tables that act as textual fillers between columns
  private val ignoreTableColumnFillers: Boolean = true
  // config: ignore columns whose title starts with the word SKIP
  private val ignoreTableColumnsMarkedSkip: Boolean = true
  // config: keep tokenized values
  private val tokenizeCells: Boolean = true

  // extract title row, key columns, the rest of the content matrix, and also tokenized content
  // cells if tokenizeCells = true; reads the input CSV file with the following convention:
  //   columns with header starting with prefix "KEY" are designated as key columns;
  //   columns with header starting with the prefix "SKIP" are skipped

  private val sep = "\\s+".r
  private val filteredColIndices = (for {
    (title, idx) <- fullContents.head.zipWithIndex
    if !ignoreTableColumnFillers || title != ""
    // skip columns whose header starts with the word "SKIP"
    firstWord = sep.split(title)(0)
    if !ignoreTableColumnsMarkedSkip || !Seq("SKIP", "[SKIP]").contains(firstWord)
  } yield idx).toIndexedSeq
  private val fullContentsFiltered = fullContents.map(filteredColIndices collect _).toIndexedSeq

  // optionally tokenize every cell (include column headers) of the table
  // TODO: create a case class for each cell to keep its rawText, tokens, etc., together
  val fullContentTokenized: IndexedSeq[IndexedSeq[TokenizedCell]] = if (tokenizeCells) {
    fullContentsFiltered.map { row =>
      row.map(cell => TokenizedCell(tokenizer.stemmedKeywordTokenize(cell)))
    }
  } else {
    IndexedSeq.empty
  }

  private val titleRowOrig = fullContentsFiltered.head

  // retrieve indices of columns whose header starts with the word "KEY"
  val keyColumns: Seq[Int] = titleRowOrig.zipWithIndex.filter(_._1.startsWith("KEY ")).map(_._2)

  val titleRow: IndexedSeq[String] = titleRowOrig.map(_.stripPrefix("KEY "))
  val contentMatrix: IndexedSeq[IndexedSeq[String]] = fullContentsFiltered.tail
}

class TableWithMetadata(table: DatastoreTable, tokenizer: KeywordTokenizer) extends Table(table
  .metadata.id.get.toString, IndexedSeq(table.header) ++ table.data, tokenizer) {
  private val shouldIgnoreColumns = table.columnMetadata.filter(_.shouldIgnore).map(_.columnNumber)
  private val fillerColumns = table.columnMetadata.filter(_.isFiller).map(_.columnNumber)
  private val columnsToKeep = table.header.indices.diff(shouldIgnoreColumns ++ fillerColumns)

  private val columnsToKeepToFinalIndexMap = columnsToKeep.zipWithIndex.map {
    case (col, i) => col -> i
  }.toMap
  override val keyColumns = table.columnMetadata.filter(_.isImportant).map {
    cm => columnsToKeepToFinalIndexMap.get(cm.columnNumber).get
  }

  private val alternateHeaderMap = table.columnMetadata.map(cm => cm.columnNumber -> cm
    .alternateHeader).toMap
  override val titleRow = columnsToKeep.map(i => {
    alternateHeaderMap.get(i) match {
      case Some(Some(s)) => if (s == "") table.header(i) else s
      case _ => table.header(i)
    }
  })

  private val filteredData = table.data.map(columnsToKeep collect _).toIndexedSeq
  private val filteredFullContents = IndexedSeq(titleRow) ++ filteredData

  override val contentMatrix = filteredData

  override val fullContentTokenized: IndexedSeq[IndexedSeq[TokenizedCell]] =
    filteredFullContents.map { row =>
      row.map(cell => TokenizedCell(tokenizer.stemmedKeywordTokenize(cell)))
    }

}