package org.allenai.ari.solvers.tableilp.ilpsolver

import org.allenai.common.Logging

import de.zib.jscip.nativ.jni._

/** Various relevant status values after an ILP is solved */
sealed trait IlpStatus
case object IlpStatusUnknown extends IlpStatus { override def toString = "Unknown" }
case object IlpStatusOptimal extends IlpStatus { override def toString = "Optimal" }
case object IlpStatusFeasible extends IlpStatus { override def toString = "Feasible" }
case object IlpStatusInfeasible extends IlpStatus { override def toString = "Infeasible" }

/** This is a generic interface to the SCIP ILP solver providing a number of common initialization
  * steps and access to the SCIP environment. This class is NOT guaranteed to be thread-safe!
  */
class ScipInterface(probName: String, scipParams: ScipParams) extends Logging {
  // Min and max values to use when defining the model
  // TODO(ashish33) check how to access SCIP's built-in SCIP_REAL_MAX, etc.
  private val ScipMin = -1e+20
  private val ScipMax = 1e+20

  // initialization: load JNI library
  logger.debug("Java library path = " + System.getProperty("java.library.path"))
  JniScipLibraryLoader.loadLibrary()

  // initialization: create various handlers in the SCIP environment
  // create the SCIP environment
  private val env: JniScip = new JniScip

  // create the SCIP variable environment
  private val envVar: JniScipVar = new JniScipVar

  // create SCIP set packing constraint environment
  private val envConsSetppc = new JniScipConsSetppc

  // create the SCIP linear constraint environment
  private val envConsLinear = new JniScipConsLinear

  // initialization: create a SCIP instance
  private val scip: Long = env.create

  // mutable problem stats to be captured after presolve() is called
  // TODO: would be cleaner to change solve() to return (ProblemStats, SearchStats, TimingStats)
  // rather than using these mutable vars and computing stats later when building IlpSolution
  private var nPresolvedVarsOpt: Option[Int] = None
  private var nPresolvedBinVarsOpt: Option[Int] = None
  private var nPresolvedIntVarsOpt: Option[Int] = None
  private var nPresolvedContVarsOpt: Option[Int] = None
  private var nPresolvedConssOpt: Option[Int] = None

  // initialization: set various parameters
  env.printVersion(scip, scipParams.printVersion)
  env.setMessagehdlrQuiet(scip, scipParams.messagehdlrQuiet)
  env.setMessagehdlrLogfile(scip, scipParams.logFile)
  env.includeDefaultPlugins(scip) // include default plugins of SCIP
  env.setRealParam(scip, "limits/time", scipParams.timeLimit) // set SCIP's overall time limit
  env.setIntParam(scip, "lp/threads", scipParams.threads) // number of threads used for LP

  // initialization: create empty problem tied to the given problem name
  env.createProbBasic(scip, probName)

  /** set objective function as minimization */
  def setAsMinimization(): Unit = env.setObjsense(scip, JniScipObjsense.SCIP_OBJSENSE_MINIMIZE)

  /** set objective function as maximization */
  def setAsMaximization(): Unit = env.setObjsense(scip, JniScipObjsense.SCIP_OBJSENSE_MAXIMIZE)

  /** create a binary variable */
  def createBinaryVar(name: String, obj: Double): Long = {
    env.createVarBasic(scip, name, 0, 1, obj, JniScipVartype.SCIP_VARTYPE_BINARY)
  }

  /** create a relaxed binary variable, that is, a continuous various with domain [0,1] */
  def createRelaxedBinaryVar(name: String, obj: Double): Long = {
    env.createVarBasic(scip, name, 0, 1, obj, JniScipVartype.SCIP_VARTYPE_CONTINUOUS)
  }

  /** create an integer variable */
  def createIntegerVar(name: String, lb: Double, ub: Double, objCoeff: Double): Long = {
    env.createVarBasic(scip, name, lb, ub, objCoeff, JniScipVartype.SCIP_VARTYPE_INTEGER)
  }

  /** create a continuous variable */
  def createContinuousVar(name: String, lb: Double, ub: Double, objCoeff: Double): Long = {
    env.createVarBasic(scip, name, lb, ub, objCoeff, JniScipVartype.SCIP_VARTYPE_CONTINUOUS)
  }

  /** add variable to the environment */
  def addVar(x: Long): Unit = env.addVar(scip, x)

  /** add constraint to the environment */
  def addCons(c: Long): Unit = env.addCons(scip, c)

  /** release constraint from the environment */
  def releaseCons(c: Long): Unit = env.releaseCons(scip, c)

  /** get the name of a variable */
  def varGetName(l: Long): String = envVar.varGetName(l)

  /** get number of variables in the original ILP */
  def getNOrigVars: Int = env.getNOrigVars(scip)

  /** get number of binary variables in the original ILP */
  def getNOrigBinVars: Int = env.getNOrigBinVars(scip)

  /** get number of integer variables in the original ILP */
  def getNOrigIntVars: Int = env.getNOrigIntVars(scip)

  /** get number of continuous variables in the original ILP */
  def getNOrigContVars: Int = env.getNOrigContVars(scip)

  /** get number of constraints in the original ILP */
  def getNOrigConss: Int = env.getNOrigConss(scip)

  /** get number of currently active variables when this method is called */
  def getNVars: Int = env.getNVars(scip)

  /** get number of currently active binary variables when this method is called */
  def getNBinVars: Int = env.getNBinVars(scip)

  /** get number of currently active integer variables when this method is called */
  def getNIntVars: Int = env.getNIntVars(scip)

  /** get number of currently active continuous variables when this method is called */
  def getNContVars: Int = env.getNContVars(scip)

  /** get number of currently active constraints when this method is called */
  def getNConss: Int = env.getNConss(scip)

  /** get number of variables in the ILP after presolve; defaults to -1 */
  def getNPresolvedVars: Int = nPresolvedVarsOpt.getOrElse(-1)

  /** get number of binary variables in the ILP after presolve; defaults to -1 */
  def getNPresolvedBinVars: Int = nPresolvedBinVarsOpt.getOrElse(-1)

  /** get number of integer variables in the ILP after presolve; defaults to -1 */
  def getNPresolvedIntVars: Int = nPresolvedIntVarsOpt.getOrElse(-1)

  /** get number of continuous variables in the ILP after presolve; defaults to -1 */
  def getNPresolvedContVars: Int = nPresolvedContVarsOpt.getOrElse(-1)

  /** get number of constraints in the ILP after presolve; defaults to -1 */
  def getNPresolvedConss: Int = nPresolvedConssOpt.getOrElse(-1)

  /** get solution status */
  def getStatus: IlpStatus = {
    env.getStatus(scip) match {
      case JniScipStatus.SCIP_STATUS_OPTIMAL => IlpStatusOptimal
      case JniScipStatus.SCIP_STATUS_INFEASIBLE => IlpStatusInfeasible
      case _ if getBestSol != 0 => IlpStatusFeasible // best solution isn't null
      case _ => IlpStatusUnknown
    }
  }

  /** Gets the coefficient for the variable in the objective function */
  def getVarObjCoeff(l: Long): Double = envVar.varGetObj(l)

  /** check whether a solution has been found */
  private val IlpStatusFeasibleOrOptimal = Seq(IlpStatusOptimal, IlpStatusFeasible)
  def hasSolution: Boolean = IlpStatusFeasibleOrOptimal.contains(getStatus)

  /** check whether an optimal solution has been found */
  def hasOptimalSolution: Boolean = (getStatus == IlpStatusOptimal)

  /** get pointer to the best solution found */
  def getBestSol: Long = env.getBestSol(scip)

  /** get objective value (primal bound) */
  def getPrimalbound: Double = env.getPrimalbound(scip)

  /** get objective value (dual bound) */
  def getDualbound: Double = env.getDualbound(scip)

  /** get optimality gap */
  def getGap: Double = env.getGap(scip)

  /** get solution values */
  def getSolVals(vars: Iterable[Long]): Iterable[Double] = {
    env.getSolVals(scip, getBestSol, vars.size, vars.toArray)
  }

  /** get one solution value */
  def getSolVal(variable: Long): Double = env.getSolVal(scip, getBestSol, variable)

  /** get time spent in presolve routine */
  def getPresolvingTime: Double = env.getPresolvingTime(scip)

  /** get time spent in main solve routine */
  def getSolvingTime: Double = env.getSolvingTime(scip)

  /** get total time spent by SCIP */
  def getTotalTime: Double = env.getTotalTime(scip)

  /** get the number of search nodes explored during branch and bound */
  def getNNodes: Long = env.getNNodes(scip)

  /** get the number of simplex iterations used when solving LP relaxations */
  def getNLPIterations: Long = env.getNLPIterations(scip)

  /** get the maximal depth of nodes explored during branch and bound */
  def getMaxDepth: Int = env.getMaxDepth(scip)

  /** Adds a constraint to SCIP and "release" it */
  def addReleaseCons(cons: Long): Unit = {
    env.addCons(scip, cons)
    env.releaseCons(scip, cons)
  }

  /** Sets the lower bound for a variable */
  def chgVarLb(x: Long, bound: Double): Unit = env.chgVarLb(scip, x, bound)

  /** If triggered, imposes a lower bound for a variable; trigger is binary variable */
  def chgVarLb(x: Long, bound: Double, trigger: Long): Unit = {
    // TODO Find a way to require that 'trigger' is a binary variable
    addConsBasicLinear("VarLb", Seq(x), Seq(1d), Some(bound), None, trigger)
  }

  /** Sets the upper bound for a variable */
  def chgVarUb(x: Long, bound: Double): Unit = env.chgVarUb(scip, x, bound)

  /** If triggered, imposes a upper bound for a variable; trigger is binary variable */
  def chgVarUb(x: Long, bound: Double, trigger: Long): Unit = {
    addConsBasicLinear("VarUb", Seq(x), Seq(1d), None, Some(bound), trigger)
  }

  /** Adds a basic linear constraints with an optional LHS and an optional RHS */
  def addConsBasicLinear(name: String, vars: Seq[Long], coeffs: Seq[Double],
    lhsOpt: Option[Double], rhsOpt: Option[Double]): Unit = {
    addReleaseCons(createConsBasicLinear(name, vars, coeffs, lhsOpt, rhsOpt))
  }

  /** If triggered, imposes a basic linear constraint on the solver; trigger is binary variable */
  def addConsBasicLinear(name: String, vars: Seq[Long], coeffs: Seq[Double],
    lhsOpt: Option[Double], rhsOpt: Option[Double], trigger: Long): Unit = {
    // a very large value, compared to sum_i var[i] * coeffs[i]
    // NOTE: for some reason, 1000000d does NOT work! appears to cause internal overflow in SCIP,
    // resulting in incorrect answers.
    val largeDbl = 10000d
    if (lhsOpt.isDefined) {
      // model as:  vars * coeffs  -  trigger * largeDbl  >=  lhs - largeDbl
      val newVars = vars :+ trigger
      val newCoeffs = coeffs :+ -largeDbl
      val newLhs = lhsOpt.get - largeDbl
      addReleaseCons(createConsBasicLinear(name, newVars, newCoeffs, Some(newLhs), None))
    }
    if (rhsOpt.isDefined) {
      // model as:  vars * coeffs  +  trigger * largeDbl  <=  rhs + largeDbl
      val newVars = vars :+ trigger
      val newCoeffs = coeffs :+ largeDbl
      val newRhs = rhsOpt.get + largeDbl
      addReleaseCons(createConsBasicLinear(name, newVars, newCoeffs, None, Some(newRhs)))
    }
  }

  /** Adds coefficient to a linear constraint (if it is not zero)
    *
    * @param cons                  constraint data
    * @param x                     variable of constraint entry
    * @param coeff                 coefficient of constraint entry
    */
  def addCoefLinear(cons: Long, x: Long, coeff: Double): Unit = {
    envConsLinear.addCoefLinear(scip, cons, x, coeff)
  }

  /** Gets the seq of coefficient values in the linear constraint; the user must not modify
    * this seq!
    *
    * @param cons                  constraint data
    */
  def getValsLinear(cons: Long): Seq[Double] = envConsLinear.getValsLinear(scip, cons)

  /** Adds the constraint sum_i x_i = 1 */
  def addConsExactlyOne(name: String, X: Seq[Long]): Unit = {
    // use special implementation if all variables are binary
    if (X.forall(envVar.varGetType(_) == JniScipVartype.SCIP_VARTYPE_BINARY)) {
      addReleaseCons(createConsBasicSetpart(name, X))
    } else {
      val coeffs = Seq.fill(X.size)(1d)
      addConsBasicLinear(name, X, coeffs, Some(1d), Some(1d))
    }

  }

  /** If triggered, imposes a set partitioning constraint, sum_i x_i = 1; trigger is binary var */
  def addConsExactlyOne(name: String, vars: Seq[Long], trigger: Long): Unit = {
    addConsBasicLinear(name, vars, Seq.fill(vars.size)(1d), Some(1d), Some(1d), trigger)
  }

  /** Adds the constraint sum_i x_i <= 1 */
  def addConsAtMostOne(name: String, X: Seq[Long]): Unit = {
    // use special implementation if all variables are binary
    if (X.forall(envVar.varGetType(_) == JniScipVartype.SCIP_VARTYPE_BINARY)) {
      addReleaseCons(createConsBasicSetpack(name, X))
    } else {
      val coeffs = Seq.fill(X.size)(1d)
      addConsBasicLinear(name, X, coeffs, None, Some(1d))
    }
  }

  /** If triggered, imposes a set packing constraint, sum_i x_i <= 1; trigger is binary variable */
  def addConsAtMostOne(name: String, vars: Seq[Long], trigger: Long): Unit = {
    addConsBasicLinear(name, vars, Seq.fill(vars.size)(1d), None, Some(1d), trigger)
  }

  /** Adds the constraint sum_i x_i >= 1 */
  def addConsAtLeastOne(name: String, X: Seq[Long]): Unit = {
    // use special implementation if all variables are binary
    if (X.forall(envVar.varGetType(_) == JniScipVartype.SCIP_VARTYPE_BINARY)) {
      addReleaseCons(createConsBasicSetcover(name, X))
    } else {
      val coeffs = Seq.fill(X.size)(1d)
      addConsBasicLinear(name, X, coeffs, Some(1d), None)
    }
  }

  /** If triggered, imposes a set covering constraint, sum_i x_i >= 1; trigger is binary variable */
  def addConsAtLeastOne(name: String, vars: Seq[Long], trigger: Long): Unit = {
    addConsBasicLinear(name, vars, Seq.fill(vars.size)(1d), Some(1d), None, trigger)
  }

  /** Adds coefficient in set partitioning / packing / covering constraint
    *
    * @param cons                  constraint data
    * @param x                     variable to add to the constraint
    */
  def addCoefSetppc(cons: Long, x: Long): Unit = envConsSetppc.addCoefSetppc(scip, cons, x)

  /** Adds the constraint x <= y + c */
  def addConsXLeqYPlusC(name: String, x: Long, y: Long, c: Double): Unit = {
    addConsBasicLinear(name, Seq(x, y), Seq(1d, -1d), None, Some(c))
  }

  /** If triggered, imposes the constraint x <= y + c; trigger is binary variable */
  def addConsXLeqYPlusC(name: String, x: Long, y: Long, c: Double, trigger: Long): Unit = {
    addConsBasicLinear(name, Seq(x, y), Seq(1d, -1d), None, Some(c), trigger)
  }

  /** Adds the constraint x <= y */
  def addConsXLeqY(name: String, x: Long, y: Long): Unit = addConsXLeqYPlusC(name, x, y, 0d)

  /** If triggered, imposes the constraint x <= y; trigger is binary variable */
  def addConsXLeqY(name: String, x: Long, y: Long, trigger: Long): Unit = {
    addConsXLeqYPlusC(name, x, y, 0d, trigger)
  }

  /** Adds the constraint x = y + c */
  def addConsXEqYPlusC(name: String, x: Long, y: Long, c: Double): Unit = {
    addConsBasicLinear(name, Seq(x, y), Seq(1d, -1d), Some(c), Some(c))
  }

  /** If triggered, imposes the constraint x = y + c; trigger is binary variable */
  def addConsXEqYPlusC(name: String, x: Long, y: Long, c: Double, trigger: Long): Unit = {
    addConsBasicLinear(name, Seq(x, y), Seq(1d, -1d), Some(c), Some(c), trigger)
  }

  /** Adds the constraint x = y */
  def addConsXEqY(name: String, x: Long, y: Long): Unit = addConsXEqYPlusC(name, x, y, 0d)

  /** If triggered, imposes the constraint x = y; trigger is binary variable */
  def addConsXEqY(name: String, x: Long, y: Long, trigger: Long): Unit = {
    addConsXEqYPlusC(name, x, y, 0d, trigger)
  }

  /** Adds the constraint sum(X) >= k */
  def addConsAtLeastK(name: String, X: Seq[Long], k: Double): Unit = {
    if (k == 1) {
      addConsAtLeastOne(name, X)
    } else {
      val coeffs = Seq.fill(X.size)(1d)
      addConsBasicLinear(name, X, coeffs, Some(k), None)
    }
  }

  /** If triggered, imposes the constraint sum(X) >= k; trigger is binary variable */
  def addConsAtLeastK(name: String, X: Seq[Long], k: Double, trigger: Long): Unit = {
    val coeffs = Seq.fill(X.size)(1d)
    addConsBasicLinear(name, X, coeffs, Some(k), None, trigger)
  }

  /** Adds the constraint sum(X) <= k */
  def addConsAtMostK(name: String, X: Seq[Long], k: Double): Unit = {
    if (k == 1) {
      addConsAtMostOne(name, X)
    } else {
      val coeffs = Seq.fill(X.size)(1d)
      addConsBasicLinear(name, X, coeffs, None, Some(k))
    }
  }

  /** If triggered, imposes the constraint sum(X) <= k; trigger is binary variable */
  def addConsAtMostK(name: String, X: Seq[Long], k: Double, trigger: Long): Unit = {
    val coeffs = Seq.fill(X.size)(1d)
    addConsBasicLinear(name, X, coeffs, None, Some(k), trigger)
  }

  /** Adds the Horn constraint x1 AND x2 AND ... AND xk => y;
    * modeled as: sum(X) - y <= |X| - 1
    */
  def addConsHorn(name: String, body: Seq[Long], head: Long): Unit = {
    val vars = body :+ head
    val coeffs = Seq.fill(body.size)(1d) :+ (-1d)
    addConsBasicLinear(name, vars, coeffs, None, Some(body.size - 1))
  }

  /** If triggered, adds the Horn constraint x1 AND x2 AND ... AND xk => y;
    * modeled as: sum(X) - y + trigger <= |X|
    */
  def addConsHorn(name: String, body: Seq[Long], head: Long, trigger: Long): Unit = {
    val vars = body ++ Seq(head, trigger)
    val coeffs = Seq.fill(body.size)(1d) ++ Seq(-1d, 1d)
    addConsBasicLinear(name, vars, coeffs, None, Some(body.size))
  }

  /** Adds the constraint sum(X) >= k * y */
  def addConsYImpliesAtLeastK(name: String, y: Long, X: Seq[Long], k: Double): Unit = {
    val vars = X :+ y
    val coeffs = Seq.fill(X.size)(1d) :+ (-k)
    addConsBasicLinear(name, vars, coeffs, Some(0d), None)
  }

  /** If triggered, imposes the constraint sum(X) >= k * y; trigger is binary variable */
  def addConsYImpliesAtLeastK(name: String, y: Long, X: Seq[Long], k: Double,
    trigger: Long): Unit = {
    val vars = X :+ y
    val coeffs = Seq.fill(X.size)(1d) :+ (-k)
    addConsBasicLinear(name, vars, coeffs, Some(0d), None, trigger)
  }

  /** Adds the constraint sum(X) >= y */
  def addConsYImpliesAtLeastOne(name: String, y: Long, X: Seq[Long]): Unit = {
    addConsYImpliesAtLeastK(name, y, X, 1d)
  }

  /** If triggered, imposes the constraint sum(X) >= y; trigger is binary variable */
  def addConsYImpliesAtLeastOne(name: String, y: Long, X: Seq[Long], trigger: Long): Unit = {
    addConsYImpliesAtLeastK(name, y, X, 1d, trigger)
  }

  /** Adds the constraint sum(X) <= k * y */
  def addConsYImpliesAtMostK(name: String, y: Long, X: Seq[Long], k: Double): Unit = {
    val vars = X :+ y
    val coeffs = Seq.fill(X.size)(1d) :+ (-k)
    addConsBasicLinear(name, vars, coeffs, None, Some(0d))
  }

  /** If triggered, imposes the constraint sum(X) <= k * y; trigger is binary variable */
  def addConsYImpliesAtMostK(name: String, y: Long, X: Seq[Long], k: Double,
    trigger: Long): Unit = {
    val vars = X :+ y
    val coeffs = Seq.fill(X.size)(1d) :+ (-k)
    addConsBasicLinear(name, vars, coeffs, None, Some(0d), trigger)
  }

  /** Adds the constraint sum(X) <= y */
  def addConsYImpliesAtMostOne(name: String, y: Long, X: Seq[Long]): Unit = {
    addConsYImpliesAtMostK(name, y, X, 1d)
  }

  /** If triggered, imposes the constraint sum(X) <= y; trigger is binary variable */
  def addConsYImpliesAtMostOne(name: String, y: Long, X: Seq[Long], trigger: Long): Unit = {
    addConsYImpliesAtMostK(name, y, X, 1d, trigger)
  }

  /** Export the generated ILP model to a file, either original or reduced/transformed */
  def exportModel(useOriginal: Boolean): Unit = {
    val ilpFile = scipParams.ilpExportFile
    if (useOriginal) {
      logger.debug(s"Writing original ILP model to $ilpFile")
      env.writeOrigProblem(scip, ilpFile, null, false)
    } else {
      logger.debug(s"Writing reduced ILP model to $ilpFile")
      env.writeTransProblem(scip, ilpFile, null, false)
    }
  }

  /** Solve the ILP model and report the result */
  def solve(): Unit = {
    // although solve() could have been directly called here, first call presolve() so that
    // simplified problem stats can be stored for future reference
    env.presolve(scip)
    nPresolvedVarsOpt = Some(getNVars)
    nPresolvedBinVarsOpt = Some(getNBinVars)
    nPresolvedIntVarsOpt = Some(getNIntVars)
    nPresolvedContVarsOpt = Some(getNContVars)
    nPresolvedConssOpt = Some(getNConss)

    // now do branch-and-bound search using solve()
    env.solve(scip)

    logger.info(s"Solution status: $getStatus")
    logger.info(s"Objective value: $getPrimalbound")
  }

  /** Reset after calling solve() so that more constraint may be added */
  def resetSolve(): Unit = {
    // clear presolved problem stats
    nPresolvedVarsOpt = None
    nPresolvedBinVarsOpt = None
    nPresolvedIntVarsOpt = None
    nPresolvedContVarsOpt = None
    nPresolvedConssOpt = None

    // reset SCIP to pre-presolve stage
    val origScipStage = env.getStage(scip)
    env.freeTransform(scip)
    val newScipStage = env.getStage(scip)
    logger.debug(s"SCIP solver stage changed from $origScipStage to $newScipStage")
  }

  /** Print result of the call to solve(), along with solution values of vars */
  def printResult(vars: Seq[Long]): Unit = {
    // retrieve best solution found so far
    if (getStatus == IlpStatusOptimal || getStatus == IlpStatusFeasible) {
      val values = getSolVals(vars)
      val solution = vars.zip(values) map { case (x, v) => varGetName(x) + " : " + v }
      logger.info("Solution found:\n\t" + solution.mkString("\n\t"))
    }
  }

  /** Explicitly free up SCIP data structures */
  def free(): Unit = env.free(scip)

  /** Creates and captures a linear constraint in its most basic version; all constraint flags are
    * set to their basic value as explained for the method SCIPcreateConsLinear(); all flags can
    * be set via SCIPsetConsFLAGNAME methods in scip.h
    *
    * @see SCIPcreateConsLinear() for information about the basic constraint flag configuration
    *
    * @param name                  name of constraint
    * @param vars                  seq with variables of constraint entries
    * @param coeffs                seq with coefficients of constraint entries
    * @param lhsOpt                left hand side of constraint, optional
    * @param rhsOpt                right hand side of constraint, optional
    */
  private def createConsBasicLinear(name: String, vars: Seq[Long], coeffs: Seq[Double],
    lhsOpt: Option[Double], rhsOpt: Option[Double]): Long = {
    envConsLinear.createConsBasicLinear(scip, name, vars.length, vars.toArray, coeffs.toArray,
      lhsOpt.getOrElse(ScipMin), rhsOpt.getOrElse(ScipMax))
  }

  /** Creates and captures a basic Set Partitioning constraint, sum_i x_i = 1, emulating C++ API's
    * createConsBasicSetpack constraint which is not provided in the Java API.
    *
    * @param name                  name of constraint
    * @param vars                  seq with variables of constraint entries
    */
  private def createConsBasicSetpart(name: String, vars: Seq[Long]): Long = {
    envConsSetppc.createConsSetpart(scip, name, vars.length, vars.toArray,
      true, true, true, true, true, false, false, false, false, false)
  }

  /** Creates and captures a basic Set Packing constraint, sum_i x_i <= 1, emulating C++ API's
    * createConsBasicSetpack constraint which is not provided in the Java API.
    *
    * @param name                  name of constraint
    * @param vars                  seq with variables of constraint entries
    */
  private def createConsBasicSetpack(name: String, vars: Seq[Long]): Long = {
    envConsSetppc.createConsSetpack(scip, name, vars.length, vars.toArray,
      true, true, true, true, true, false, false, false, false, false)
  }

  /** Creates and captures a basic Set covering constraint, sum_i x_i >= 1, emulating C++ API's
    * createConsBasicSetpack constraint which is not provided in the Java API.
    *
    * @param name                  name of constraint
    * @param vars                  seq with variables of constraint entries
    */
  private def createConsBasicSetcover(name: String, vars: Seq[Long]): Long = {
    envConsSetppc.createConsSetcover(scip, name, vars.length, vars.toArray,
      true, true, true, true, true, false, false, false, false, false)
  }
}
