package slamdata.engine

import org.specs2.mutable._
import org.specs2.ScalaCheck
import org.scalacheck._
import Gen._

import slamdata.engine.fp._

import scalaz._
import Scalaz._

class LogicalPlanSpecs extends Specification with ScalaCheck {
  import LogicalPlan._
  import analysis.fixplate._
  import std.MathLib.{Add}

  "optimalBoundPhase" should {
    // Use State to count the number of "Add" nodes that are evaluated:
    def eval(node: LogicalPlan[Cofree[LogicalPlan, (Unit, Int)]]): State[Int, Int] =
      node match {
        case ReadF(_) => sys.error("read")
        case ConstantF(Data.Int(x)) => state(x.toInt)
        case ConstantF(_) => sys.error("not an int")
        case JoinF(_, _, _, _, _, _) => sys.error("join")
        case InvokeF(Add, l :: r :: Nil) =>
          State(_ + 1 -> (l.head._2 + r.head._2))
        case InvokeF(_, _) => sys.error("invoke")
        case FreeF(sym) => sys.error("should have been intercepted: " + sym)
        case LetF(_, _, body) => state(body.head._2)
      }

    val stateEval = Phase[LogicalPlan, Unit, Int] { (attr: Cofree[LogicalPlan, Unit]) =>
      scanPara0[LogicalPlan, Unit, Int](attr) {
        (orig: Cofree[LogicalPlan, Unit], node: LogicalPlan[Cofree[LogicalPlan, (Unit, Int)]]) =>
          eval(node).eval(0)
      }
    }

    val boundEval = lpBoundPhase(stateEval)

    val countAddTerms: LogicalPlan[Int] => Int = {
      case InvokeF(Add, x) => 1 + x.sum
      case x               => x.fold
    }

    val fancyEval: PhaseS[LogicalPlan, Int, Unit, Int] = optimalBoundPhaseS(eval)

    implicit val IntRenderTree = new RenderTree[Int] {
      def render(v: Int) = Terminal(v.toString, "Int" :: Nil)
    }

    "evaluate expression only once in trivial example" in {
      val lp = Invoke(std.MathLib.Add, List(
                  Constant(Data.Int(1)),
                  Constant(Data.Int(2))))

      val (count, result) = fancyEval(attrUnit(lp)).run(0)

      result.head must_== 3
      count must_== 1
    }

    "evaluate let form only once in simple example" in {
      val lp = Let('tmp0,
                Invoke(std.MathLib.Add, List(
                  Constant(Data.Int(1)),
                  Constant(Data.Int(2)))),
                Invoke(std.MathLib.Add, List(
                  Free('tmp0),
                  Free('tmp0))))

      val (count, result) = fancyEval(attrUnit(lp)).run(0)

      result.head must_== 6
      count must_== 2
    }

    "evaluate each Add term exactly once" ! prop { (expr: Term[LogicalPlan]) =>
      val attr1 = boundEval(attrUnit(expr))
      val expectedResult = attr1.head

      val (evaluated, attr2) = fancyEval(attrUnit(expr)).run(0)
      val result = attr2.head

      val expectedEvaluated = expr.cata(countAddTerms)

      evaluated must_== expectedEvaluated
      result must_== expectedResult
    }
  }

  implicit def arbitraryExpr: Arbitrary[Term[LogicalPlan]] =
    Arbitrary { Gen.sized(size => exprGen(size/30, Nil, Nil, true)) }

  def exprGen(size: Int, in: List[Term[LogicalPlan]], out: List[Term[LogicalPlan]], free: Boolean): Gen[Term[LogicalPlan]] = {
    val simple = if (free) Gen.oneOf(constGen, Gen.oneOf(in)) else constGen
    if (size == 0)
      simple
    else
      Gen.oneOf(simple, addGen(size, in, out), letGen(size, in, out))
  }

  def addGen(size: Int, in: List[Term[LogicalPlan]], out: List[Term[LogicalPlan]]): Gen[Term[LogicalPlan]] = for {
    l <- exprGen(size-1, in, out, true)
    r <- exprGen(size-1, in, out, true)
  } yield Invoke(std.MathLib.Add, l :: r :: Nil)

  def letGen(size: Int, in: List[Term[LogicalPlan]], out: List[Term[LogicalPlan]]): Gen[Term[LogicalPlan]] = {
    val n = Symbol("tmp" + ((in.length + out.length)+1))
    for {
      expr <- exprGen(size-1, in, Free(n) :: out, false) // don't generate Let(_, Free(), _) forms that break lpBoundPhase
      body <- exprGen(size-1, Free(n) :: in, out, true)
    } yield Let(n, expr, body)
  }

  def constGen: Gen[Term[LogicalPlan]] = for {
    n <- Gen.choose(0, 100)
  } yield Constant(Data.Int(n))
}
