package org.allenai.ari.solvers.tableilp

import org.allenai.ari.solvers.tableilp.ilpsolver.ScipInterface
import org.allenai.ari.solvers.tableilp.params.{ IlpParams, IlpWeights }
import org.allenai.common.Logging

import scala.collection.mutable

/** The ILP model for Table Inference.
  *
  * @param ilpSolver a ScipInterface object; safest to create a new instance of it per question
  * @param aligner a blackbox to align textual mentions
  * @param ilpParams various parameters for the ILP model
  * @param weights various weights for the ILP model
  * @param tableInterface a TableInterface object with information about all tables
  * @param tableIdsWithScores a seq of table IDs ((indices to tableInterface.allTables) as the
  *   knowledge, with a question dependent score
  */
class IlpModel(
    ilpSolver: ScipInterface,
    aligner: AlignmentFunction,
    ilpParams: IlpParams,
    weights: IlpWeights,
    tableInterface: TableInterface,
    tableIdsWithScores: Seq[(Int, Double)]
) extends Logging {

  /** An index into a cell in a table */
  private case class CellIdx(tableIdx: Int, rowIdx: Int, colIdx: Int)

  /** A title index in a table */
  private case class TitleIdx(tableIdx: Int, colIdx: Int)

  /** A constituent index in a question */
  private case class QuestionIdx(qConsIdx: Int)

  // these set of words in the question text will be ignored before alignment
  private val ignoredWords: Set[String] = Set("?", ":", ",")

  // gather info about the few tables relevant to this ILP model
  private val (tableIds: IndexedSeq[Int], tableScores: IndexedSeq[Double]) = {
    tableIdsWithScores.toIndexedSeq.unzip
  }
  private val tables: IndexedSeq[Table] = tableIds.map(tableInterface.allTables)
  private val tableNameToIdx: Map[String, Int] = tables.map(_.fileName).zipWithIndex.toMap

  /** The main method to build an ILP model for a question.
    *
    * @param question a question
    * @return a seq of (a subset of) variables of interest whose values may be queried later
    */
  def buildModel(question: TableQuestion): AllVariables = {
    // set this up as a maximization problem
    ilpSolver.setAsMaximization()

    // create question independent variables
    logger.info("Creating question independent variables")
    val allVarsQuestionIndependent = createQuestionIndependentVars

    // create question dependent variables; allVars is guaranteed to be a superset of
    // allVarsQuestionIndependent
    logger.info("Creating question dependent variables")
    val allVars = createQuestionDependentVars(question, allVarsQuestionIndependent)

    // add the question dependent part of the model;
    // NOTE: this MUST be done before building the question independent part, as the activeCellVars
    // map is modified by this method and addQuestionIndependentConstraints relies on this change
    logger.info("Building question dependent model")
    addQuestionDependentConstraints(question, allVars)

    // build the question independent part of the model
    logger.info("Building question independent model")
    addQuestionIndependentConstraints(allVars)

    // return all variables
    allVars
  }

  /** Set a particular answer choice variable to 0, preventing it from being selected by the ILP.
    *
    * @param activeChoiceVar an answer choice selection indicator variable
    */
  def disableAnswerChoice(activeChoiceVar: Long): Unit = {
    ilpSolver.chgVarUb(activeChoiceVar, 0d)
  }

  /** Auxiliary variables: whether a cell within a given table is "active" */
  private val activeCellVars: mutable.Map[CellIdx, Long] = mutable.Map() ++ (for {
    tableIdx <- tables.indices
    table = tables(tableIdx)
    rowIdx <- table.contentMatrix.indices
    row = table.contentMatrix(rowIdx)
    colIdx <- row.indices
    name = s"activeCell_t=${tableIdx}_r=${rowIdx}_c=$colIdx"
    objCoeff = weights.activeCellObjCoeff
    x = createPossiblyRelaxedBinaryVar(name, objCoeff)
  } yield {
    // do NOT yet add activeCellVar to IlpSolver; while the variable is being created here, its
    // addition to the solver is delayed, contingent upon the cell having some external alignment
    CellIdx(tableIdx, rowIdx, colIdx) -> x
  })

  /** Auxiliary variables: whether a row within a given table is "active" */
  private val activeRowVars: mutable.Map[(Int, Int), Long] = mutable.Map() ++ (for {
    tableIdx <- tables.indices
    table = tables(tableIdx)
    rowIdx <- table.contentMatrix.indices
    name = s"activeRow_t=${tableIdx}_r=$rowIdx"
    // penalize row usage to discourage too many rows from being aligned unnecessarily
    objCoeff = -weights.rowUsagePenalty
    x = createPossiblyRelaxedBinaryVar(name, objCoeff)
  } yield {
    // do NOT yet add activeRowVar to IlpSolver; while the variable is being created here, its
    // addition to the solver is delayed, contingent upon the row having potentially active cells
    (tableIdx, rowIdx) -> x
  })

  /** Auxiliary variables: whether a column within a given table is "active" */
  private val activeColVars: mutable.Map[(Int, Int), Long] = mutable.Map() ++ (for {
    tableIdx <- tables.indices
    table = tables(tableIdx)
    if table.contentMatrix.indices.nonEmpty
    colIdx <- table.contentMatrix.head.indices
    name = s"activeCol_t=${tableIdx}_r=$colIdx"
    // prefer larger fraction of columns matching
    objCoeff = weights.activeColObjCoeff / table.contentMatrix.head.indices.size
    x = createPossiblyRelaxedBinaryVar(name, objCoeff)
  } yield {
    // do NOT yet add activeColVar to IlpSolver; while the variable is being created here, its
    // addition to the solver is delayed, contingent upon the column having potentially active cells
    (tableIdx, colIdx) -> x
  })

  /** Auxiliary variables: whether a title column of a given table is "active" */
  private val activeTitleVars: Map[(Int, Int), Long] = (for {
    tableIdx <- tables.indices
    table = tables(tableIdx)
    colIdx <- table.titleRow.indices
    name = s"activeTitle_t=${tableIdx}_r=$colIdx"
    objCoeff = weights.activeTitleObjCoeff
    x = createPossiblyRelaxedBinaryVar(name, objCoeff)
  } yield {
    ilpSolver.addVar(x)
    (tableIdx, colIdx) -> x
  }).toMap

  /** Auxiliary variables: whether a table is "active" */
  private val activeTableVars: Map[Int, Long] = (for {
    tableIdx <- tableIds.indices
    tableScore = tableScores(tableIdx)
    name = s"activeTable_t=$tableIdx"
    objCoeff = (weights.tableScoreObjCoeff * tableScore) - weights.tableUsagePenalty
    x = createPossiblyRelaxedBinaryVar(name, objCoeff)
  } yield {
    ilpSolver.addVar(x)
    tableIdx -> x
  }).toMap

  /** Create question independent variables.
    * Note: using 'val' rather than 'def' makes this be computed only once.
    *
    * @return a seq of (a subset of) variables of interest whose values may be queried later
    */
  private lazy val createQuestionIndependentVars: AllVariables = {
    // Intra-table variables
    val intraTableVariables = for {
      tableIdx <- tables.indices
      table = tables(tableIdx)
      rowIdx <- table.contentMatrix.indices
      row = table.contentMatrix(rowIdx)
      colIdx1 <- row.indices
      colIdx2 <- colIdx1 + 1 until row.length
      x <- addIntraTableVariable(tableIdx, rowIdx, colIdx1, colIdx2)
    } yield x

    // Inter-table variables
    val interTableVariables = if (ilpParams.maxTablesToChain <= 1) {
      IndexedSeq.empty
    } else {
      // collect tuples of the form (table1,col1,table2,col2) for which to create inter-table vars
      val tableColumnPairs: Seq[(Int, Int, Int, Int)] = if (ilpParams.useCachedTitleAlignmentFile) {
        for {
          allowedTitleAlignment <- tableInterface.allowedTitleAlignments
          tableIdx1 <- tableNameToIdx.get(allowedTitleAlignment.table1Name)
          tableIdx2 <- tableNameToIdx.get(allowedTitleAlignment.table2Name)
          _ = require(tableIdx1 != tableIdx2, "Table indices must be different")
          // switch, if needed, so that the table with a lower index appears first;
          // this ensures that the tuples generated here form a subset of the
          // all-undirectional-pairs case in the else { ... } block below
          (t1, c1, t2, c2) = if (tableIdx1 < tableIdx2) {
            (tableIdx1, allowedTitleAlignment.col1Idx, tableIdx2, allowedTitleAlignment.col2Idx)
          } else {
            (tableIdx2, allowedTitleAlignment.col2Idx, tableIdx1, allowedTitleAlignment.col1Idx)
          }
        } yield (t1, c1, t2, c2)
      } else {
        for {
          tableIdx1 <- tables.indices
          tableIdx2 <- tableIdx1 + 1 until tables.length
          table1 = tables(tableIdx1)
          table2 = tables(tableIdx2)
          colIdx1 <- table1.contentMatrix.head.indices
          colIdx2 <- table2.contentMatrix.head.indices
          // allow inter-table cell alignment only if the titles match
          title1 = table1.titleRow(colIdx1)
          title2 = table2.titleRow(colIdx2)
          if aligner.scoreTitleTitle(title1, title2) >= weights.minTitleTitleAlignment
        } yield (tableIdx1, colIdx1, tableIdx2, colIdx2)
      }
      for {
        (tableIdx1, colIdx1, tableIdx2, colIdx2) <- tableColumnPairs.toIndexedSeq
        rowIdx1 <- tables(tableIdx1).contentMatrix.indices
        rowIdx2 <- tables(tableIdx2).contentMatrix.indices
        x <- addInterTableVariable(tableIdx1, tableIdx2, rowIdx1, rowIdx2, colIdx1, colIdx2)
      } yield x
    }

    logger.debug(s"\t${intraTableVariables.size} intra-table vars, " +
      s"${interTableVariables.size} inter-table vars")

    logger.debug(s"\tTotal ${ilpSolver.getNVars} variables so far")

    // return all variables
    AllVariables(intraTableVariables, interTableVariables,
      IndexedSeq.empty, IndexedSeq.empty, IndexedSeq.empty, IndexedSeq.empty, Map.empty)
  }

  /** Create question dependent variables.
    *
    * @param question a question
    * @param existingAllVars all variables currently in existence
    * @return a seq of (a subset of) variables of interest whose values may be queried later;
    * this will be a superset of existingAllVars
    */
  private def createQuestionDependentVars(
    question: TableQuestion, existingAllVars: AllVariables
  ): AllVariables = {
    val questionTableVariables = for {
      tableIdx <- tables.indices
      qConsIdx <- question.questionCons.indices
      qCons = question.questionCons(qConsIdx)
      if !ignoredWords.contains(qCons)
      table = tables(tableIdx)
      rowIdx <- table.contentMatrix.indices
      row = tables(tableIdx).contentMatrix(rowIdx)
      colIdx <- row.indices
      x <- addQuestionTableVariable(qCons, qConsIdx, tableIdx, rowIdx, colIdx)
    } yield x
    val questionTitleVariables = for {
      tableIdx <- tables.indices
      qConsIdx <- question.questionCons.indices
      qCons = question.questionCons(qConsIdx)
      if !ignoredWords.contains(qCons)
      table = tables(tableIdx)
      colIdx <- table.titleRow.indices
      x <- addQuestionTitleVariable(qCons, qConsIdx, tableIdx, colIdx)
    } yield x
    val qChoiceTableVariables = for {
      tableIdx <- tables.indices
      qChoiceIdx <- question.choices.indices
      qChoice = question.choices(qChoiceIdx)
      table = tables(tableIdx)
      rowIdx <- table.contentMatrix.indices
      row = tables(tableIdx).contentMatrix(rowIdx)
      colIdx <- row.indices
      x <- addQChoiceTableVariable(qChoice, qChoiceIdx, tableIdx, rowIdx, colIdx)
    } yield x
    val qChoiceTitleVariables = for {
      tableIdx <- tables.indices
      qChoiceIdx <- question.choices.indices
      qChoiceCons = question.choices(qChoiceIdx)
      table = tables(tableIdx)
      colIdx <- table.titleRow.indices
      x <- addQChoiceTitleVariable(qChoiceCons, qChoiceIdx, tableIdx, colIdx)
    } yield x

    // Auxiliary variables: whether an answer choice is aligned to something
    val activeChoiceVars: Map[Int, Long] = (for {
      choiceIdx <- question.choices.indices
      name = s"choice=$choiceIdx"
      objCoeff = weights.activeChoiceObjCoeff
      x = createPossiblyRelaxedBinaryVar(name, objCoeff)
    } yield {
      ilpSolver.addVar(x)
      choiceIdx -> x
    }).toMap

    logger.debug(s"\t${questionTableVariables.size} question-table vars, " +
      s"${questionTitleVariables.size} question-title vars, " +
      s"${qChoiceTableVariables.size} choice-table vars, " +
      s"${qChoiceTitleVariables.size} choice-title vars")

    logger.debug(s"\tTotal ${ilpSolver.getNVars} variables so far")

    // return all variables
    AllVariables(existingAllVars.intraTableVariables, existingAllVars.interTableVariables,
      questionTableVariables, questionTitleVariables, qChoiceTableVariables, qChoiceTitleVariables,
      activeChoiceVars)
  }

  /** The main method to build the question dependent aspects of the ILP model.
    *
    * @param question a question
    * @param allVars all variables previously created
    */
  private def addQuestionDependentConstraints(
    question: TableQuestion, allVars: AllVariables
  ): Unit = {
    val intraTableVariables = allVars.intraTableVariables
    val interTableVariables = allVars.interTableVariables
    val questionTableVariables = allVars.questionTableVariables
    val questionTitleVariables = allVars.questionTitleVariables
    val qChoiceTableVariables = allVars.qChoiceTableVariables
    val qChoiceTitleVariables = allVars.qChoiceTitleVariables
    val activeChoiceVars = allVars.activeChoiceVars

    // Auxiliary variables: whether a constituent of a given question is "active"
    val activeQuestionVars: Map[Int, Long] = (for {
      qConsIdx <- question.questionCons.indices
      name = s"activeQuestion_t=$qConsIdx"
      objCoeff = weights.activeQConsObjCoeff
      x = createPossiblyRelaxedBinaryVar(name, objCoeff)
    } yield {
      ilpSolver.addVar(x)
      qConsIdx -> x
    }).toMap

    // A convenient map from a cell to intra-table ILP variables associated with it
    val tmpIntraTriples = intraTableVariables.flatMap {
      case IntraTableVariable(tableIdx, rowIdx, colIdx1, colIdx2, x) =>
        val cellIdx1 = CellIdx(tableIdx, rowIdx, colIdx1)
        val cellIdx2 = CellIdx(tableIdx, rowIdx, colIdx2)
        Seq(cellIdx1 -> x, cellIdx2 -> x)
    }
    // A convenient map from a cell to inter-table ILP variables associated with it
    val tmpInterTriples = interTableVariables.flatMap {
      case InterTableVariable(tableIdx1, tableIdx2, rowIdx1, rowIdx2, colIdx1, colIdx2, x) =>
        val cellIdx1 = CellIdx(tableIdx1, rowIdx1, colIdx1)
        val cellIdx2 = CellIdx(tableIdx2, rowIdx2, colIdx2)
        Seq(cellIdx1 -> x, cellIdx2 -> x)
    }
    // A convenient map from a cell to question-table ILP variables associated with it
    val tmpQuestionTriples = questionTableVariables.map {
      case QuestionTableVariable(_, tableIdx, rowIdx, colIdx, x) =>
        CellIdx(tableIdx, rowIdx, colIdx) -> x
    }
    // A convenient map from a cell to question-choice ILP variables associated with it
    val tmpChoicesTriples = qChoiceTableVariables.map {
      case ChoiceTableVariable(_, tableIdx, rowIdx, colIdx, x) =>
        CellIdx(tableIdx, rowIdx, colIdx) -> x
    }

    // Collect all external alignment variables per cell; note: simply doing
    // cellToInterTableVars ++ cellToInterTableVars may not work
    val cellToExtAlignmentVars = Utils.toMapUsingGroupByFirst(
      tmpInterTriples ++ tmpQuestionTriples ++ tmpChoicesTriples
    )

    // Collect all external alignments per answer choice
    val tmpChoiceToTableVars = qChoiceTableVariables.map {
      case ChoiceTableVariable(qChoiceCons, _, _, _, x) => qChoiceCons -> x
    }
    val tmpChoiceToTitleVars = qChoiceTitleVariables.map {
      case ChoiceTitleVariable(qChoiceCons, _, _, x) => qChoiceCons -> x
    }
    val choiceToExtAlignmentVars = Utils.toMapUsingGroupByFirst(tmpChoiceToTableVars ++
      tmpChoiceToTitleVars)

    // Collect all external alignments per title
    val tmpTitleToQuestionVars = questionTitleVariables.map {
      case QuestionTitleVariable(_, tableIdx, colIdx, x) => TitleIdx(tableIdx, colIdx) -> x
    }
    val tmpTitleToChoiceVars = qChoiceTitleVariables.map {
      case ChoiceTitleVariable(_, tableIdx, colIdx, x) => TitleIdx(tableIdx, colIdx) -> x
    }
    val titleToExtAlignmentVars = Utils.toMapUsingGroupByFirst(tmpTitleToQuestionVars ++
      tmpTitleToChoiceVars)

    // Collect all external alignments per question constituent
    val tmpQuestionToTitleVars = questionTitleVariables.map {
      case QuestionTitleVariable(qConsIdx, _, _, x) => QuestionIdx(qConsIdx) -> x
    }
    val tmpQuestionToTableVars = questionTableVariables.map {
      case QuestionTableVariable(qConsIdx, _, _, _, x) => QuestionIdx(qConsIdx) -> x
    }
    val questionToExtAlignmentVars = Utils.toMapUsingGroupByFirst(tmpQuestionToTableVars ++
      tmpQuestionToTitleVars)

    // add question dependent activity constraints
    // NOTE: this MUST be the very first use of activeCellVars, as it alters this mutable.Map
    tables.indices.foreach { tableIdx =>
      val table = tables(tableIdx)
      table.contentMatrix.indices.foreach { rowIdx =>
        val row = table.contentMatrix(rowIdx)
        row.indices.map { colIdx =>
          val cellIdx = CellIdx(tableIdx, rowIdx, colIdx)
          val activeCellVar = activeCellVars(cellIdx)
          cellToExtAlignmentVars.get(cellIdx) match {
            case Some(extAlignmentVarsForCell) => {
              if (extAlignmentVarsForCell.isEmpty) {
                throw new IllegalStateException("Cell must have some external alignments")
              }
              // add activeCellVar to IlpSolver; while the variable was created much earlier, its
              // addition to the solver had been delayed as it is contingent upon potential external
              // alignments existing; without such potential alignments, this variable has no use
              ilpSolver.addVar(activeCellVar)
              // if any variable aligning to a cell is 1, make the corresponding activeCellVar be 1
              extAlignmentVarsForCell.foreach {
                ilpSolver.addConsXLeqY("activeCell", _, activeCellVar)
              }
              // if an activeCellVar is 1, at least one external cell alignment variable must be 1;
              // model as sum(extAlignmentVarsForCell) >= activeCellVar, i.e.,
              // 0 <= sum(extAlignmentVarsForCell) - activeCellVar
              ilpSolver.addConsYImpliesAtLeastK(
                "activeCellImpliesAtLeastOneExt",
                activeCellVar, extAlignmentVarsForCell, 1d
              )
            }
            case None => {
              // remove this variable from the activeCellVars mutable.Map, as it can never be 1
              activeCellVars.remove(cellIdx)
            }
          }
          // return the active cell var
          activeCellVar
        }
      }

      // a title is active if and only if there is an external alignment to it
      table.titleRow.indices.foreach { colIdx =>
        val titleIdx = TitleIdx(tableIdx, colIdx)
        val activeTitleVar = activeTitleVars((tableIdx, colIdx))
        val extAlignmentVarsForTitle = titleToExtAlignmentVars.getOrElse(titleIdx, Seq.empty)
        ilpSolver.addConsYImpliesAtLeastK("activeTitleImpliesAlignments", activeTitleVar,
          extAlignmentVarsForTitle, 1d)
        extAlignmentVarsForTitle.foreach {
          ilpSolver.addConsXLeqY("activeTitle", _, activeTitleVar)
        }
      }
    }

    // if the question choice is active, there is at least one active thing connected to it.
    // i.e. ChoiceVariable <= Sum(incomingToChoice)
    question.choices.indices.foreach { choiceIdx =>
      val choiceVar = activeChoiceVars(choiceIdx)
      val extAlignmentVarsForChoice = choiceToExtAlignmentVars.getOrElse(choiceIdx, Seq.empty)
      ilpSolver.addConsYImpliesAtLeastK("activeChoiceImpliesAlignments", choiceVar,
        extAlignmentVarsForChoice, 1d)
      // activate the choice variables if there is anything aligned to it: alignedCell => Choice
      extAlignmentVarsForChoice.foreach {
        ilpSolver.addConsXLeqY("choiceActivation", _, choiceVar)
      }
    }

    // a choice should align to at most one column
    if (ilpParams.maxRowsPerTable <= 1) {
      // at most one 1 row: simply enforce that a choice should align to at most one cell;
      // note that the "else" block below would achieve the same thing when maxRowsPerTable == 1,
      // albeit with unnecessary additional variables and multiple constraints.
      choiceToExtAlignmentVars.values.foreach { extAlignmentVarsForChoice =>
        ilpSolver.addConsAtMostOne("choiceAlignsToAtMostOneCell", extAlignmentVarsForChoice)
      }
    } else {
      // at most k rows, for k > 1
      val tableVarsMap = qChoiceTableVariables.groupBy(v => (v.qChoiceIdx, v.tableIdx, v.colIdx))
      val titleVarsMap = qChoiceTitleVariables.groupBy(v => (v.qChoiceIdx, v.tableIdx, v.colIdx))
      question.choices.indices.foreach { qChoiceIdx =>
        tables.indices.foreach { tableIdx =>
          val table = tables(tableIdx)
          val activeChoiceColumnVars = table.contentMatrix.head.indices.map { colIdx =>
            // create a variable indicating whether qChoice is aligned with a given table column
            val name = s"activeChoiceColumnVar-$qChoiceIdx-$tableIdx-$colIdx"
            val activeChoiceColumnVar = createPossiblyRelaxedBinaryVar(name, 0d)
            ilpSolver.addVar(activeChoiceColumnVar)
            // key to look up alignment variables connecting qChoice to a cell or title of a column
            val key = (qChoiceIdx, tableIdx, colIdx)
            // if a cell in colIdx is aligned with qChoice, the column is active for qChoice
            tableVarsMap.getOrElse(key, Seq.empty).foreach { tableVar =>
              ilpSolver.addConsXLeqY("choiceToCol", tableVar.variable, activeChoiceColumnVar)
            }
            // if the title of colIdx is aligned with qChoice, the column is active for qChoice
            titleVarsMap.getOrElse(key, Seq.empty).foreach { titleVar =>
              ilpSolver.addConsXLeqY("choiceToTitle", titleVar.variable, activeChoiceColumnVar)
            }
            activeChoiceColumnVar
          }
          // at most one column may be active for qChoice
          ilpSolver.addConsAtMostOne(s"atMostOneColumn-$qChoiceIdx", activeChoiceColumnVars)
        }
      }
    }

    // align at least one question constituent
    val qConsVars = question.questionCons.indices.map(activeQuestionVars)
    ilpSolver.addConsAtLeastOne("atLeastOneQCons", qConsVars)

    // select at most one answer choice
    val choiceVars = question.choices.indices.map(activeChoiceVars)
    ilpSolver.addConsAtMostOne("atMostOneChoice", choiceVars)

    // (optional) select at least one answer choice
    if (ilpParams.mustChooseAnAnswer) ilpSolver.addConsAtLeastOne("atLeastOneChoice", choiceVars)

    // active question variables
    question.questionCons.indices.foreach { qIdx =>
      val questionIdx = QuestionIdx(qIdx)
      val activeQuestionVar = activeQuestionVars(qIdx)
      val extAlignmentVars = questionToExtAlignmentVars.getOrElse(questionIdx, Seq.empty)
      ilpSolver.addConsYImpliesAtLeastK("activeQuestionImpliesAlignments", activeQuestionVar,
        extAlignmentVars, 1d)
      extAlignmentVars.foreach {
        ilpSolver.addConsXLeqY("activeQuestionCons", _, activeQuestionVar)
      }
      // a question constituent may not align to more than K cells
      ilpSolver.addConsAtMostK("qConsAtMostK", extAlignmentVars, weights.maxAlignmentsPerQCons)
    }

    // Boost cell alignments to consecutive question constituents
    val cellToQuestionAlignmentVarMap = Utils.toMapUsingGroupByFirst(tmpQuestionTriples)
    // A map from qCons alignment variable to the question constituent index
    val qConsVarToQIdx = tmpQuestionToTableVars.map { case (qIdx, x) => x -> qIdx.qConsIdx }.toMap

    for {
      qCons1QuestionTabVar <- questionTableVariables
      // Get another qCons aligned to the same cell
      qCons2Var <- cellToQuestionAlignmentVarMap.getOrElse(CellIdx(
        qCons1QuestionTabVar.tableIdx,
        qCons1QuestionTabVar.rowIdx, qCons1QuestionTabVar.colIdx
      ), Seq.empty)
      qIdx1 = qCons1QuestionTabVar.qConsIdx
      qIdx2 = qConsVarToQIdx(qCons2Var)
      // Boost score only if the two aligned constituents are within 3 words of each other
      // TODO(tushar) Make this configurable
      if (qIdx2 > qIdx1 && qIdx2 - qIdx1 <= 3)
      qCons1Var = qCons1QuestionTabVar.variable
      varName = s"T=${qCons1QuestionTabVar.tableIdx}-R=${qCons1QuestionTabVar.rowIdx}-" +
        s"C=${qCons1QuestionTabVar.colIdx}-Q1=$qIdx1-Q2=$qIdx2"
      // Boost consecutive alignment with 1/(distance + 1). The +1 is to prevent a high boost for
      // adjacent words compared to one word apart(1 -> 0.5 vs 0.5 -> 0.33).
      q1q2CellVar = createPossiblyRelaxedBinaryVar(varName, 1.0 / (qIdx2 - qIdx1 + 1.0))
    } {
      ilpSolver.addVar(q1q2CellVar)
      // q1q2 should be true only if both q1 and q2 align with this cell, i.e.
      // q1q2 = min(q1, q2) encoded as q1q2 <= q1 and q1q2 <= q2. q1q2 >= min(q1,q2) isn't needed
      // as the coefficient of q1q2 is positive and we are maximizing
      ilpSolver.addConsXLeqY("consecutiveQConsAlign", q1q2CellVar, qCons1Var)
      ilpSolver.addConsXLeqY("consecutiveQConsAlign", q1q2CellVar, qCons2Var)
    }

    logger.debug(s"\tTotal ${ilpSolver.getNConss} constraints so far")
  }

  /** The main method to build the question independent aspects of the ILP model. */
  private def addQuestionIndependentConstraints(allVars: AllVariables): Unit = {
    val interTableVariables = allVars.interTableVariables

    // add question independent activity constraints
    tables.indices.foreach { tableIdx =>
      val table = tables(tableIdx)

      // cell, row, and column activity constraints
      // NOTE: this MUST be the very first use of activeRowVars, as it alters this mutable.Map
      table.contentMatrix.indices.foreach { rowIdx =>
        val activeRowVar = activeRowVars((tableIdx, rowIdx))
        val row = table.contentMatrix(rowIdx)
        val activeCellVarsInRow = for {
          colIdx <- row.indices
          cellIdx = CellIdx(tableIdx, rowIdx, colIdx)
          activeCellVar <- activeCellVars.get(cellIdx)
        } yield activeCellVar
        val minActiveCellsPerRow = 2
        if (activeCellVarsInRow.size >= minActiveCellsPerRow) {
          // add activeRowVar to IlpSolver; while the variable was created much earlier, its
          // addition to the solver had been delayed as it is contingent upon potential enough cells
          // in the row being active; without such potential activity, this variable has no use
          ilpSolver.addVar(activeRowVar)
          activeCellVarsInRow.foreach { activeCellVar =>
            ilpSolver.addConsXLeqY("activeRow", activeCellVar, activeRowVar)
            // non-redundant use of tables: if a row is active, at least TWO of its cells must be
            // active; model as sum(activeCellVarsInRow) >= 2*activeRowVar, i.e.,
            // 0 <= sum(activeCellVarsInRow) - 2*activeRowVar
            ilpSolver.addConsYImpliesAtLeastK("activeRowImpliesAtLeastTwoCells", activeRowVar,
              activeCellVarsInRow, minActiveCellsPerRow)
          }
        } else {
          // remove this variable from the activeRowVars mutable.Map, as it can never be 1
          activeRowVars.remove((tableIdx, rowIdx))
          // make the cells in this row inactive
          activeCellVarsInRow.foreach(ilpSolver.chgVarUb(_, 0d))
        }
      }

      // NOTE: this MUST be the very first use of activeColVars, as it alters this mutable.Map
      // non-redundant use of tables: if a col is active, at least ONE of its cells must be
      // active; model as sum(activeCellVarsInCol) >= 1*activeColVar, i.e.,
      // 0 <= sum(activeCellVarsInCol) - 1*activeColVar
      table.titleRow.indices.foreach { colIdx =>
        val activeColVar = activeColVars((tableIdx, colIdx))
        val activeCellVarsInCol = for {
          rowIdx <- table.contentMatrix.indices
          cellIdx = CellIdx(tableIdx, rowIdx, colIdx)
          activeCellVar <- activeCellVars.get(cellIdx)
        } yield activeCellVar
        val minActiveCellsPerCol = 1
        if (activeCellVarsInCol.size >= minActiveCellsPerCol) {
          // add activeColVar to IlpSolver; while the variable was created much earlier, its
          // addition to the solver had been delayed as it is contingent upon potential enough cells
          // in the column being active; without such potential activity, this variable has no use
          ilpSolver.addVar(activeColVar)
          activeCellVarsInCol.foreach { activeCellVar =>
            // if any activeCellVar for a column is 1, make the corresponding activeColVar be 1
            val activeColVar = activeColVars((tableIdx, colIdx))
            ilpSolver.addConsXLeqY("activeCol", activeCellVar, activeColVar)
          }
          ilpSolver.addConsYImpliesAtLeastK("activeColImpliesAtLeastOneCell", activeColVar,
            activeCellVarsInCol, 1d)
        } else {
          // remove this variable from the activeColVars mutable.Map, as it can never be 1
          activeColVars.remove((tableIdx, colIdx))
          // make the cells in this column inactive
          activeCellVarsInCol.foreach(ilpSolver.chgVarUb(_, 0d))
        }
      }

      // row activity constraint: allow at most K rows per table to be active
      // Note: question dependent variables may also make the row active; this constraint will
      // take that into account as well
      val tableRowVars = table.contentMatrix.indices.flatMap(r => activeRowVars.get((tableIdx, r)))
      val ub = ilpParams.maxRowsPerTable
      ilpSolver.addConsAtMostK(s"atMost${ub}Rows_T=$tableIdx", tableRowVars, ub)

      // if two rows of a table are active, the corresponding active cell variables across the two
      // rows must match; in other words, the two rows must have identical activity signature;
      //   Horn constraint: activeRow_i AND activeRow_j AND activeCell_ik => activeCell_jk
      for {
        rowIdx1 <- table.contentMatrix.indices
        activeRowVar1 <- activeRowVars.get((tableIdx, rowIdx1))
        rowIdx2 <- table.contentMatrix.indices
        if rowIdx2 != rowIdx1
        activeRowVar2 <- activeRowVars.get((tableIdx, rowIdx2))
        colIdx <- table.contentMatrix.head.indices
        activeCellVar1 <- activeCellVars.get(CellIdx(tableIdx, rowIdx1, colIdx))
        name = s"ActivitySignature_$tableIdx-$rowIdx1-$rowIdx2"
        body = Seq(activeRowVar1, activeRowVar2, activeCellVar1)
      } {
        // if activeCellVar2 is present, add a Horn clause with it as the head;
        // otherwise at least one variable in the body of the intended Horn clause must be false
        activeCellVars.get(CellIdx(tableIdx, rowIdx2, colIdx)) match {
          case Some(activeCellVar2) => ilpSolver.addConsHorn(name, body, activeCellVar2)
          case None => ilpSolver.addConsAtMostK(name, body, body.size - 1)
        }
      }

      // table activity constraints
      // (a) if a row is active, then the table is active (NOTE: if title is active, then a row must
      // be active, and then this constraint makes the table active as well);
      val activeTableVar = activeTableVars(tableIdx)
      tableRowVars.foreach { activeRowVar =>
        ilpSolver.addConsXLeqY(s"activeRowImpliesActiveTable_T=$tableIdx", activeRowVar,
          activeTableVar)
      }
      // (b) if the table is active, then at least one row is active
      ilpSolver.addConsYImpliesAtLeastK(s"activeRowsImpliesActiveTable_T=$tableIdx", activeTableVar,
        tableRowVars, 1d)
      // (c) if this table T1 is active and another table T2 is also active, then:
      //   - if there exists a T1-T2 inter-table edge, then at least one such edge must be used;
      // first create a map from tables to inter-table variables connecting this table to that table
      val interTableMap = interTableVariables.filter(_.tableIdx1 == tableIdx).groupBy(_.tableIdx2)
      interTableMap.foreach {
        case (tableIdx2, interTableVars) =>
          val activeTableVar2 = activeTableVars(tableIdx2)
          val name = s"tablesMustConnect-$tableIdx-$tableIdx2"
          // logical constraint: activeTable1 AND activeTable2 => sum(interTableVars) >= 1
          // modeled as: activeTable1 + activeTable2 - sum(interTableVars) <= 1
          val vars = Seq(activeTableVar, activeTableVar2) ++ interTableVars.map(_.variable)
          val coeffs = Seq(1d, 1d) ++ Seq.fill(interTableVars.size)(-1d)
          ilpSolver.addConsBasicLinear(name, vars, coeffs, None, Some(1d))
      }

      // if a title is active, then the corresponding column must be active
      // otherwise we don't need, i.e.: activeTitleVar <= activeColVar
      table.titleRow.indices.foreach { colIdx =>
        val activeTitleVar = activeTitleVars((tableIdx, colIdx))
        activeColVars.get((tableIdx, colIdx)) match {
          case Some(activeColVar) => {
            ilpSolver.addConsXLeqY("activeTitle", activeTitleVar, activeColVar)
          }
          case None => {
            // if activeColVar isn't available, the title may not be active
            ilpSolver.chgVarUb(activeTitleVar, 0d)
          }
        }
      }

      // (optional) if a table is active, then at least one of its KEY columns must be active
      if (ilpParams.keyColumnsMustMatch && table.keyColumns.nonEmpty) {
        val activeKeyColVars = for {
          colIdx <- table.keyColumns
          activeColVar <- activeColVars.get((tableIdx, colIdx))
        } yield activeColVar
        ilpSolver.addConsYImpliesAtLeastK("activeKeyColumns", activeTableVar, activeKeyColVars, 1d)
      }
    }

    // at most k tables may be active
    val maxTables = ilpParams.maxTablesToChain
    ilpSolver.addConsAtMostK(s"atMost${maxTables}Tables", activeTableVars.values.toSeq, maxTables)

    logger.debug(s"\tTotal ${ilpSolver.getNConss} constraints so far")
  }

  /** An internal method to create an intra-table variable */
  private val enableIntraTableVars = false // TODO(ashish33) experiment to determine usefulness
  private def addIntraTableVariable(
    tableIdx: Int, rowIdx: Int, colIdx1: Int, colIdx2: Int
  ): Option[IntraTableVariable] = {
    if (enableIntraTableVars) {
      val name = s"T=$tableIdx-R=$rowIdx-C1=$colIdx1-C2=$colIdx2"
      val objCoeff = 1d //TODO correct this with alignment score
      val variable = ilpSolver.createBinaryVar(name, objCoeff)
      ilpSolver.addVar(variable)
      Some(IntraTableVariable(tableIdx, rowIdx, colIdx1, colIdx2, variable))
    } else {
      None // intra-table variables not enabled
    }
  }

  /** An internal method to create an inter-table variable, if there is sufficient lexical match */
  private def addInterTableVariable(
    tableIdx1: Int, tableIdx2: Int, rowIdx1: Int, rowIdx2: Int, colIdx1: Int, colIdx2: Int
  ): Option[InterTableVariable] = {
    val alignmentScore = aligner.scoreCellCell(
      tables(tableIdx1).contentMatrix(rowIdx1)(colIdx1),
      tables(tableIdx2).contentMatrix(rowIdx2)(colIdx2)
    )
    if (alignmentScore < weights.minCellCellAlignment) {
      None
    } else {
      val name = s"T1=$tableIdx1-T2=$tableIdx2-R1=$rowIdx1-R2=$rowIdx2-C1=$colIdx1-C2=$colIdx2"
      // penalize inter-table alignment edges to prevent too many alignments; the gain obtained by
      // using a second table should be large enough to make up for this penalty
      val objCoeff = alignmentScore - weights.interTableAlignmentPenalty
      val variable = ilpSolver.createBinaryVar(name, objCoeff)
      ilpSolver.addVar(variable)
      Some(InterTableVariable(tableIdx1, tableIdx2, rowIdx1, rowIdx2, colIdx1, colIdx2, variable))
    }
  }

  /** An internal method to create a question-to-table variable */
  private def addQuestionTableVariable(
    qCons: String, qConsIdx: Int, tableIdx: Int, rowIdx: Int, colIdx: Int
  ): Option[QuestionTableVariable] = {
    val objCoeff = aligner.scoreCellQCons(tables(tableIdx).contentMatrix(rowIdx)(colIdx), qCons)
    if (objCoeff < weights.minCellQConsAlignment) {
      None
    } else {
      val name = s"T=$tableIdx-Con=$qConsIdx-R=$rowIdx-C=$colIdx"
      val variable = ilpSolver.createBinaryVar(name, objCoeff)
      ilpSolver.addVar(variable)
      Some(QuestionTableVariable(qConsIdx, tableIdx, rowIdx, colIdx, variable))
    }
  }

  /** An internal method to create a question-to-title variable */
  private def addQuestionTitleVariable(
    qCons: String, qConsIdx: Int, tableIdx: Int, colIdx: Int
  ): Option[QuestionTitleVariable] = {
    val objCoeff = aligner.scoreTitleQCons(tables(tableIdx).titleRow(colIdx), qCons)
    if (objCoeff < weights.minTitleQConsAlignment) {
      None
    } else {
      val name = s"T=$tableIdx-Title=$qConsIdx-C=$colIdx"
      val variable = ilpSolver.createBinaryVar(name, objCoeff)
      ilpSolver.addVar(variable)
      Some(QuestionTitleVariable(qConsIdx, tableIdx, colIdx, variable))
    }
  }

  /** An internal method to create a question choice-to-title variable */
  private def addQChoiceTitleVariable(
    qChoice: String, qChoiceIdx: Int, tableIdx: Int, colIdx: Int
  ): Option[ChoiceTitleVariable] = {
    val objCoeff = aligner.scoreTitleQChoice(tables(tableIdx).titleRow(colIdx), qChoice)
    if (objCoeff < weights.minTitleQChoiceAlignment) {
      None
    } else {
      val name = s"T=$tableIdx-ChoiceIdx=$qChoiceIdx-C=$colIdx"
      val variable = ilpSolver.createBinaryVar(name, objCoeff)
      ilpSolver.addVar(variable)
      Some(ChoiceTitleVariable(qChoiceIdx, tableIdx, colIdx, variable))
    }
  }

  /** An internal method to create a option-to-table variable */
  private def addQChoiceTableVariable(
    qChoice: String, qChoiceIdx: Int, tableIdx: Int, rowIdx: Int, colIdx: Int
  ): Option[ChoiceTableVariable] = {
    val objCoeff = aligner.scoreCellQChoice(tables(tableIdx).contentMatrix(rowIdx)(colIdx), qChoice)
    if (objCoeff < weights.minCellQChoiceAlignment) {
      None
    } else {
      val name = s"T=$tableIdx-QChoice=$qChoiceIdx-R=$rowIdx-C=$colIdx"
      val variable = ilpSolver.createBinaryVar(name, objCoeff)
      ilpSolver.addVar(variable)
      Some(ChoiceTableVariable(qChoiceIdx, tableIdx, rowIdx, colIdx, variable))
    }
  }

  private def createPossiblyRelaxedBinaryVar(name: String, objCoeff: Double): Long = {
    if (ilpParams.useRelaxedVars) {
      ilpSolver.createRelaxedBinaryVar(name, objCoeff)
    } else {
      ilpSolver.createBinaryVar(name, objCoeff)
    }
  }
}
