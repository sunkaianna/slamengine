package slamdata.engine.physical.mongodb

import collection.immutable.ListMap

import slamdata.engine.fs.Path
import slamdata.engine.{Error, RenderTree, RenderedTree, Terminal, NonTerminal}
import slamdata.engine.fp._
import optimize.pipeline._
import WorkflowTask._
import slamdata.engine.analysis.fixplate._
import slamdata.engine.javascript._

import scalaz._
import Scalaz._
import spire.algebra.Ring
import spire.syntax.ring._
import monocle.Macro._
import monocle.syntax._

sealed trait IdHandling
object IdHandling {
  case object ExcludeId extends IdHandling
  case object IncludeId extends IdHandling
  case object IgnoreId extends IdHandling

  implicit val IdHandlingRing = new Ring[IdHandling] {
    // This is the `merge` function
    def plus(f1: IdHandling, f2: IdHandling) = (f1, f2) match {
      case (IncludeId, _)         => IncludeId
      case (_,         IncludeId) => IncludeId
      case (_,         ExcludeId) => ExcludeId
      case (_,         IgnoreId)  => f1
    }

    def negate(a: IdHandling) = a match {
      case IncludeId => ExcludeId
      case ExcludeId => IncludeId
      case IgnoreId  => IgnoreId
    }

    // this is the `coalesce` function
    def times(f1: IdHandling, f2: IdHandling) = (f1, f2) match {
      case (_, IgnoreId) => f1
      case (_, _)        => f2
    }

    def zero = IgnoreId
    def one = IgnoreId
  }
}

/**
  A Workflow is a graph of atomic operations, with WorkflowOps for the vertices.
  We crush them down into a WorkflowTask. This `crush` gives us a location to
  optimize our workflow decisions. EG, A sequence of simple ops may be combined
  into a single pipeline request, but if one of those operations contains JS, we
  have to execute that outside of a pipeline, possibly reordering the other
  operations to avoid having two pipelines with a JS operation in the middle.

  We also implement the optimizations at
  http://docs.mongodb.org/manual/core/aggregation-pipeline-optimization/ so that
  we can build others potentially on top of them (including reordering
  non-pipelines around pipelines, etc.).
  */
sealed trait WorkflowF[+A]
object Workflow {
  import ExprOp.{GroupOp => _, _}
  import IdHandling._
  import MapReduce._

  type Workflow = Term[WorkflowF]
  type WorkflowOp = Workflow => Workflow
  type PipelineOp = PipelineF[Unit]

  val ExprLabel  = "value"
  val ExprName   = BsonField.Name(ExprLabel)
  val ExprVar    = ExprOp.DocVar.ROOT(ExprName)

  val IdLabel  = "_id"
  val IdName   = BsonField.Name(IdLabel)
  val IdVar    = ExprOp.DocVar.ROOT(IdName)

  implicit val PipelineFTraverse = new Traverse[PipelineF] {
    def traverseImpl[G[_], A, B](fa: PipelineF[A])(f: A => G[B])
      (implicit G: Applicative[G]):
        G[PipelineF[B]] = fa match {
      case $Match(src, sel)         => G.apply(f(src))($Match(_, sel))
      case $Project(src, shape, id) => G.apply(f(src))($Project(_, shape, id))
      case $Redact(src, value)      => G.apply(f(src))($Redact(_, value))
      case $Limit(src, count)       => G.apply(f(src))($Limit(_, count))
      case $Skip(src, count)        => G.apply(f(src))($Skip(_, count))
      case $Unwind(src, field)      => G.apply(f(src))($Unwind(_, field))
      case $Group(src, grouped, by) => G.apply(f(src))($Group(_, grouped, by))
      case $Sort(src, value)        => G.apply(f(src))($Sort(_, value))
      case $GeoNear(src, near, distanceField, limit, maxDistance, query, spherical, distanceMultiplier, includeLocs, uniqueDocs) =>
        G.apply(f(src))($GeoNear(_, near, distanceField, limit, maxDistance, query, spherical, distanceMultiplier, includeLocs, uniqueDocs))
      case $Out(src, col)           => G.apply(f(src))($Out(_, col))
    }
  }

  implicit val WorkflowFTraverse = new Traverse[WorkflowF] {
    def traverseImpl[G[_], A, B](fa: WorkflowF[A])(f: A => G[B])
      (implicit G: Applicative[G]):
        G[WorkflowF[B]] = fa match {
      case x @ $Pure(_)             => G.point(x)
      case x @ $Read(_)             => G.point(x)
      case $Map(src, fn, scope)     => G.apply(f(src))($Map(_, fn, scope))
      case $FlatMap(src, fn, scope) => G.apply(f(src))($FlatMap(_, fn, scope))
      case $SimpleMap(src, expr, flatten, scope) =>
        G.apply(f(src))($SimpleMap(_, expr, flatten, scope))
      case $Reduce(src, fn, scope)  => G.apply(f(src))($Reduce(_, fn, scope))
      case $FoldLeft(head, tail)    =>
        G.apply2(
          f(head), Traverse[NonEmptyList].sequence(tail.map(f)))(
          $FoldLeft(_, _))
      case $Join(srcs)              =>
        G.apply(
          Traverse[List].sequence(srcs.map(f).toList))(
          x => $Join(x.toSet))
      // NB: Would be nice to replace the rest of this impl with the following
      //     line, but the invariant definition of Traverse doesn’t allow it.
      // case p: PipelineF[_]           => PipelineFTraverse.traverseImpl(p)(f)
      case $Match(src, sel)         => G.apply(f(src))($Match(_, sel))
      case $Project(src, shape, id) => G.apply(f(src))($Project(_, shape, id))
      case $Redact(src, value)      => G.apply(f(src))($Redact(_, value))
      case $Limit(src, count)       => G.apply(f(src))($Limit(_, count))
      case $Skip(src, count)        => G.apply(f(src))($Skip(_, count))
      case $Unwind(src, field)      => G.apply(f(src))($Unwind(_, field))
      case $Group(src, grouped, by) => G.apply(f(src))($Group(_, grouped, by))
      case $Sort(src, value)        => G.apply(f(src))($Sort(_, value))
      case $GeoNear(src, near, distanceField, limit, maxDistance, query, spherical, distanceMultiplier, includeLocs, uniqueDocs) =>
        G.apply(f(src))($GeoNear(_, near, distanceField, limit, maxDistance, query, spherical, distanceMultiplier, includeLocs, uniqueDocs))
      case $Out(src, col)           => G.apply(f(src))($Out(_, col))
    }
  }

  def task(op: Workflow): WorkflowTask =
    (WorkflowTask.finish _).tupled(finalize(op).para(crush))._2

  def finish(op: Workflow): Workflow = deleteUnusedFields(op, None)

  def coalesce(op: Workflow): Workflow =
    op.unFix match {
      case $Match(src, selector) => src.unFix match {
        case $Sort(src0, value) =>
          chain(src0, $match(selector), $sort(value))
        case $Match(src0, sel0) =>
          chain(src0, $match(Semigroup[Selector].append(sel0, selector)))
        case _ => op
      }
      case p @ $Project(src, shape, id) => src.unFix match {
        case $Project(src0, shape0, id0) =>
          $project(inlineProject(p, List(shape0)), id0 * id)(src0)
        // Would like to inline a $project into a preceding $simpleMap, but
        // This is not safe, because sometimes a $project is inserted after
        // $simpleMap specifically to pull fields out of `value`, and those
        // $project ops need to be preserved.
        // case $SimpleMap(src0, js, flatten, scope) =>
        //   shape.toJs.fold(
        //     κ(op),
        //     jsShape => chain(src0,
        //       $simpleMap(
        //         JsMacro(base =>
        //           JsCore.Let(
        //             ListMap("__tmp" -> js(base)),
        //             jsShape(JsCore.Ident("__tmp").fix)).fix),
        //         flatten, scope)))
        case $Group(src, grouped, by) if id != ExcludeId =>
          inlineProjectGroup(shape, grouped).map($group(_, by)(src)).getOrElse(op)
        case $Unwind(Term($Group(src, grouped, by)), unwound)
            if id != ExcludeId =>
          inlineProjectUnwindGroup(shape, unwound, grouped).map { case (unwound, grouped) =>
            chain(src,
              $group(grouped, by),
              $unwind(unwound))
          }.getOrElse(op)
        case _ => op
      }
      case $Limit(src, count) => src.unFix match {
        case $Limit(src0, count0) =>
          chain(src0, $limit(Math.min(count0, count)))
        case $Skip(src0, count0) =>
          chain(src0, $limit(count0 + count), $skip(count0))
        case _ => op
      }
      case $Skip(src, count) => src.unFix match {
        case $Skip(src0, count0) => $skip(count0 + count)(src0)
        case _                   => op
      }
      case $Group(src, grouped, -\/(Literal(bson))) if bson != Bson.Null =>
        coalesce($group(grouped, -\/(Literal(Bson.Null)))(src))
      case op0 @ $Group(_, _, _) =>
        inlineGroupProjects(op0).map(tup => Term(($Group[Workflow](_, _, _)).tupled(tup))).getOrElse(op)
      case $GeoNear(src, _, _, _, _, _, _, _, _, _) => src.unFix match {
        // FIXME: merge the params
        case $GeoNear(_, _, _, _, _, _, _, _, _, _) => op
        case _                                      => op
      }
      case $Map(src, fn, scope) => src.unFix match {
        case $Map(src0, fn0, scope0) =>
          Reshape.mergeMaps(scope0, scope).fold(
            op)(
            s => chain(src0, $map($Map.compose(fn, fn0), s)))
        case $FlatMap(src0, fn0, scope0) =>
          Reshape.mergeMaps(scope0, scope).fold(
            op)(
            s => chain(src0, $flatMap($FlatMap.mapCompose(fn, fn0), s)))
        case _                   => op
      }
      case $FlatMap(src, fn, scope) => src.unFix match {
        case $Map(src0, fn0, scope0)     =>
          Reshape.mergeMaps(scope0, scope).fold(
            op)(
            $flatMap($Map.compose(fn, fn0), _)(src0))
        case $FlatMap(src0, fn0, scope0) =>
          Reshape.mergeMaps(scope0, scope).fold(
            op)(
            $flatMap($FlatMap.kleisliCompose(fn, fn0), _)(src0))
        case _                   => op
      }
      case sm @ $SimpleMap(src, _, _, _) => src.unFix match {
        case sm0 @ $SimpleMap(_, _, _, _) => Term(sm0 >>> sm)
        case _                            => op
      }
      case $FoldLeft(head, tail) => head.unFix match {
        case $FoldLeft(head0, tail0) =>
          $FoldLeft.make(head0, tail0 append tail)
        case _                       => op
      }
      case $Out(src, _) => src.unFix match {
        case $Read(_) => src
        case _        => op
      }
      case _ => op
    }

  def merge(left: Workflow, right: Workflow):
      State[NameGen, ((DocVar, DocVar), Workflow)] = {
    def delegate =
      merge(right, left).map { case ((r, l), merged) => ((l, r), merged) }

    if (left == right)
      state((DocVar.ROOT(), DocVar.ROOT()) -> left)
    else
      (left.unFix, right.unFix) match {
        case ($Pure(lbson), $Pure(rbson)) =>
          for {
            lName <- freshName
            rName <- freshName
          } yield ((ExprOp.DocField(lName), ExprOp.DocField(rName)) ->
              $pure(Bson.Doc(ListMap(
                lName.asText -> lbson,
                rName.asText -> rbson))))
        case ($Pure(bson), _) =>
          for {
            lName <- freshName
            rName <- freshName
          } yield ((ExprOp.DocField(lName), ExprOp.DocField(rName)) ->
              chain(
                right,
                $project(
                  Reshape(ListMap(
                    lName -> -\/(ExprOp.Literal(bson)),
                    rName -> -\/(DocVar.ROOT()))),
                  IncludeId)))
        case (_, $Pure(_)) => delegate

        case (l @ $Group(lsrc, _, b1), r @ $Group(rsrc, _, b2))
            if (b1 == b2) =>
          for {
            lName <- freshName
            rName <- freshName
            t <- merge(lsrc, rsrc)
            ((lb, rb), src) = t
          } yield {
            val ($Group(_, Grouped(g1_), _), lb0) = rewrite(l, lb)
            val ($Group(_, Grouped(g2_), _), rb0) = rewrite(r, rb)

            Reshape.mergeMaps(g1_, g2_).fold({
              // Rewrite:
              // - each grouped value is given a new temp name in the merged GroupOp.
              // - a ProjectOp is added after grouping to rearrange the values
              //   under lEft and rIght.
              // This is needed because GroupOp cannot create nested structure, and
              // we need the value from each original op to be located under a single
              // name (lEft/rIght).
              val oldNames: List[BsonField.Leaf] = g1_.keys.toList ++ g2_.keys.toList
              val ops = g1_.values.toList ++ g2_.values.toList
              val tempNames = BsonField.genUniqNames(ops.length, Nil): List[BsonField.Leaf]

              // New grouped values:
              val g = ListMap((tempNames zip ops): _*)

              // Project from flat temps to lEft/rIght:
              val (ot1, ot2) = (oldNames zip tempNames).splitAt(g1_.length)
              val t = ListMap(lName -> ot1, rName -> ot2)
              val s: ListMap[BsonField.Name, ExprOp \/ Reshape] =
                t ∘ (ot =>
                  \/-(Reshape(
                    ot.map(_.bimap(_.toName, tmp => -\/(ExprOp.DocField(tmp)))).toListMap))
                )

              ((ExprOp.DocField(lName), ExprOp.DocField(rName)) ->
                chain(src,
                  $group(Grouped(g), b1),
                  $project(Reshape(s), IgnoreId)))
            })(
              g => ((lb0, rb0) -> chain(src, $group(Grouped(g), b1))))
          }

        case (l @ $Group(_, _, _), r: PipelineF[_]) =>
          merge(left, r.src).flatMap { case ((lb, rb), src) =>
            for {
              lName <- freshName
              rName <- freshName
              (r0, rb0) = rewrite(r, DocField(rName))
            } yield {
              ((DocField(lName), rb0) ->
                r0.reparentW(chain(src,
                  $project(Reshape(ListMap(
                    lName -> -\/(lb),
                    rName -> -\/(rb))),
                    IgnoreId))))
            }
          }
        case (_: PipelineF[_], $Group(_, _, _)) => delegate

        case (l @ $GeoNear(_, _, _, _, _, _, _, _, _, _), r: PipelineF[_]) =>
          merge(left, r.src).map { case ((lb, rb), src) =>
            val (left0, lb0) = rewrite(l, lb)
            val (right0, rb0) = rewrite(r, rb)
            ((lb0, rb), Term(right0.reparent(src)))
          }
        case (_, _: $GeoNear[_]) => delegate

        case ($Project(lsrc, lshape, id), _) if lsrc == right =>
        for {
          lName <- freshName
          rName <- freshName
        } yield ((ExprOp.DocField(lName), ExprOp.DocField(rName)) ->
            chain(lsrc,
              $project(
                Reshape(ListMap(
                  lName -> \/- (lshape),
                  rName -> -\/ (ExprOp.DocVar.ROOT()))),
                id + IncludeId)))
        case (_, $Project(rsrc, _, _)) if left == rsrc => delegate

        case (l @ $Unwind(lsrc, lfield), r @ $Unwind(rsrc, rfield)) =>
          merge(lsrc, rsrc).map { case ((lb, rb), src) =>
            val (left0, lb0) = rewrite(l, lb)
            val (right0, rb0) = rewrite(r, rb)
            if (left0.field == right0.field)
              ((lb0, rb0) -> chain(src,
                $unwind(left0.field)))
            else
              ((lb0, rb0) -> chain(src,
                $unwind(left0.field),
                $unwind(right0.field)))
          }

        case ($SimpleMap(lsrc, lexpr, Nil, lscope), $SimpleMap(rsrc, rexpr, Nil, rscope)) =>
          for {
            lName <- freshName
            rName <- freshName

            t <- merge(lsrc, rsrc)
            ((lb, rb), src) = t
          } yield
            ((ExprOp.DocField(lName), ExprOp.DocField(rName)) ->
              chain(src,
                $simpleMap(JsMacro(value =>
                  JsCore.Obj(ListMap(
                    lName.asText -> lexpr(lb.toJs(value)),
                    rName.asText -> rexpr(rb.toJs(value)))).fix),
                  Nil,
                  Reshape.mergeMaps(lscope, rscope).fold(
                    sys.error(s"cannot merge incompatible scopes ${lscope} with ${rscope}"))(
                    ɩ))))

        case ($SimpleMap(lsrc, lexpr, lflatten, lscope), _) =>
          for {
            lName <- freshName
            rName <- freshName

            t <- merge(lsrc, right)
            ((lb, rb), src) = t
          } yield
            ((ExprOp.DocField(lName), ExprOp.DocField(rName)) ->
              chain(src,
                $simpleMap(
                  JsMacro(value =>
                    JsCore.Obj(ListMap(
                      lName.asText -> lexpr(lb.toJs(value)),
                      rName.asText -> rb.toJs(value))).fix),
                  lflatten.map(m => JsMacro(value => m(lb.toJs(value)))),
                  lscope)))
        case (_, $SimpleMap(_, _, _, _)) => delegate

        case (l @ $Project(lsrc, _, lx), r @ $Project(rsrc, _, rx)) =>
          merge(lsrc, rsrc).flatMap { case ((lb, rb), src) =>
            val (left0, lb0) = rewrite(l, lb)
            val (right0, rb0) = rewrite(r, rb)
            Reshape.merge(left0.shape, right0.shape).fold(
              for {
                lName <- freshName
                rName <- freshName
              } yield
                ((ExprOp.DocField(lName) \\ lb0,
                  ExprOp.DocField(rName) \\ rb0) ->
                  chain(src,
                    $project(Reshape(ListMap(
                      lName -> \/-(left0.shape),
                      rName -> \/-(right0.shape))),
                      lx + rx)))
            )(
              merged =>
                state((lb0, rb0) -> chain(src, $project(merged, lx + rx))))
          }

        case (l @ $Project(lsrc, _, id), _: PipelineF[_]) =>
          for {
            lName <- freshName
            rName <- freshName

            t <- merge(lsrc, right)
            ((lb, rb), src) = t
          } yield {
            val (left0, lb0) = rewrite(l, lb)

            ((ExprOp.DocField(lName) \\ lb0, ExprOp.DocField(rName) \\ rb) ->
              chain(src,
                $project(
                  Reshape(ListMap(
                    lName -> \/- (left0.shape),
                    rName -> -\/ (DocVar.ROOT()))),
                  id + IncludeId)))
          }
        case (_: PipelineF[_], $Project(_, _, _)) => delegate

        case (l @ $Redact(lsrc, _), r @ $Redact(rsrc, _)) =>
          merge(lsrc, rsrc).map { case ((lb, rb), src) =>
            val (left0, lb0) = rewrite(l, lb)
            val (right0, rb0) = rewrite(r, rb)
            ((lb0, rb0) -> chain(src,
              $redact(left0.value),
              $redact(right0.value)))
          }

        case ($Unwind(lsrc, lfield), _) =>
          merge(lsrc, right).flatMap { case ((lb, rb), src) =>
            if (lb == rb) // NB: means we need to duplicate the field so we don’t trample the one on the other side
              for {
                lName <- freshName
                rName <- freshName
              } yield ((DocField(lName), DocField(rName)) ->
                chain(src,
                  $project(Reshape(ListMap(
                    lName -> -\/(lb),
                    rName -> -\/(rb))),
                    IgnoreId),
                  $unwind(DocField(lName) \\ lfield)))
            else
              state(((lb, rb) -> chain(src, $unwind(lb \\ lfield))))
          }
        case (_, $Unwind(_, _)) => delegate

        case (l @ $Map(_, _, _), r @ $Project(rsrc, shape, _)) =>
          merge(left, rsrc).flatMap { case ((lb, rb), src) =>
            val (left0, lb0) = rewrite(l, lb)
            val (right0, rb0) = rewrite(r, rb)
            for {
              lName <- freshName
              rName <- freshName
            } yield ((ExprOp.DocField(lName) \\ lb0, ExprOp.DocField(rName) \\ rb) ->
              chain(src,
                $project(
                  Reshape(ListMap(
                    lName -> -\/(DocVar.ROOT()),
                    rName -> \/-(shape))),
                  IncludeId)))
          }
        case ($Project(_, _, _), $Map(_, _, _)) => delegate

        case (l @ $Project(lsrc, shape, id), r: SourceOp) =>
          merge(lsrc, right).flatMap { case ((lb, rb), src) =>
            val (left0, _) = rewrite(l, lb)
            for {
              lName <- freshName
              rName <- freshName
            } yield ((ExprOp.DocField(lName), ExprOp.DocField(rName)) ->
                chain(src,
                  $project(Reshape(ListMap(
                    lName -> \/- (left0.shape),
                    rName -> -\/ (rb))),
                    id + IncludeId)))
          }
        case (_: SourceOp, $Project(_, _, _)) => delegate

        case (l: ShapePreservingF[_], r: WorkflowF[_]) =>
          merge(l.src, right).map { case ((lb, rb), src) =>
            val (left0, lb0) = rewrite(l, lb)
            ((lb0, rb) -> Term(l.reparent(src)))
          }
        case (_: WorkflowF[_], _: ShapePreservingF[_]) => delegate

        case (l: WorkflowF[_], r: PipelineF[_]) =>
          sys.error(s"cannot merge ${l.getClass} with ${r.getClass}")
        case (_: PipelineF[_], _: WorkflowF[_]) => delegate

        case _ =>
          for {
            lName <- freshName
            rName <- freshName
          } yield ((ExprOp.DocField(lName), ExprOp.DocField(rName)) ->
            $foldLeft(
              chain(left,
                $project(
                  Reshape(ListMap(lName -> -\/(DocVar.ROOT()))),
                  IncludeId)),
              chain(right,
                $project(
                  Reshape(ListMap(rName -> -\/(DocVar.ROOT()))),
                  IncludeId))))
      }
  }

  def pipeline[A <: PipelineF[Workflow]](op: A):
      Option[(DocVar, WorkflowTask, List[PipelineOp])] =
    op match {
      case $Match(src, selector) =>
        def pipelinable(sel: Selector): Boolean = sel match {
          case Selector.Where(_) => false
          case comp: Selector.CompoundSelector =>
            pipelinable(comp.left) && pipelinable(comp.right)
          case _ => true
        }
        if (pipelinable(selector)) {
          lazy val (base, crushed) = src.para(crush)
          src.unFix match {
            case p: PipelineF[Workflow] => pipeline(p).cata(
              { case (base, up, prev) => Some((base, up, prev :+ rewriteRefs(PipelineFTraverse.void(op), prefixBase(base)))) },

              Some((base, crushed, List(rewriteRefs(PipelineFTraverse.void(op), prefixBase(base))))))
            case _ => Some((base, crushed, List(rewriteRefs(PipelineFTraverse.void(op), prefixBase(base)))))
          }
        }
        else None
      // TODO: Not all $Groups can be pipelined. Need to determine when we may
      //       need the group command or a map/reduce.
      case _ => Some(alwaysPipePipe(op))
    }

  /**
    Returns both the final WorkflowTask as well as a DocVar indicating the base
    of the collection.
    */
  private val crush: WorkflowF[(Term[WorkflowF], (DocVar, WorkflowTask))] => (DocVar, WorkflowTask) = {
      case $Pure(value) => (DocVar.ROOT(), PureTask(value))
      case $Read(coll)  => (DocVar.ROOT(), ReadTask(coll))
      case op @ $Match((src, rez), selector) =>
        // TODO: If we ever allow explicit request of cursors (instead of
        //       collections), we could generate a FindQuery here.

        lazy val nonPipeline = {
          val (base, crushed) = (WorkflowTask.finish _).tupled(rez)
          (ExprVar,
            MapReduceTask(
              crushed,
              MapReduce(
                $Map.mapFn(base match {
                  case DocVar(DocVar.ROOT, None) => $Map.mapNOP
                  case _                         => $Map.mapProject(base)
                }),
                $Reduce.reduceNOP,
                // TODO: Get rid of this asInstanceOf!
                selection = Some(rewriteRefs(PipelineFTraverse.void(op).asInstanceOf[$Match[Workflow]], prefixBase(base)).selector))))
        }
        pipeline($Match(src, selector)) match {
          case Some((base, up, mine)) => (base, PipelineTask(up, mine))
          case None                   => nonPipeline
        }
      case p: PipelineF[_] =>
        alwaysPipePipe(p.reparent(p.src._1)) match {
          case (base, up, pipe) => (base, PipelineTask(up, pipe))
        }
      case op @ $Map((_, (base, src1 @ MapReduceTask(src0, mr @ MapReduce(_, _, _, _, _, _, None, scope0, _, _)))), fn, scope) =>
        Reshape.mergeMaps(scope0, scope).fold(
          op.newMR(base, src1, None, None, None))(
          s => base -> MapReduceTask(src0,
            mr applyLens MapReduce._finalizer set Some($Map.finalizerFn(fn))
              applyLens MapReduce._scope set s))

      case op @ $Reduce((_, (base, src1 @ MapReduceTask(src0, mr @ MapReduce(_, reduceNOP, _, _, _, _, None, scope0, _, _)))), fn, scope) =>
        Reshape.mergeMaps(scope0, scope).fold(
          op.newMR(base, src1, None, None, None))(
          s => base -> MapReduceTask(src0,
            mr applyLens MapReduce._reduce set fn
              applyLens MapReduce._scope set s))

      case op: MapReduceF[_] =>
        op.src match {
          case (_, (base, PipelineTask(src0, List($Match(_, sel))))) =>
            op.newMR(base, src0, Some(sel), None, None)
          case (_, (base, PipelineTask(src0, List($Sort(_, sort))))) =>
            op.newMR(base, src0, None, Some(sort), None)
          case (_, (base, PipelineTask(src0, List($Limit(_, count))))) =>
            op.newMR(base, src0, None, None, Some(count))
          case (_, (base, PipelineTask(src0, List($Match(_, sel), $Sort(_, sort))))) =>
            op.newMR(base, src0, Some(sel), Some(sort), None)
          case (_, (base, PipelineTask(src0, List($Match(_, sel), $Limit(_, count))))) =>
            op.newMR(base, src0, Some(sel), None, Some(count))
          case (_, (base, PipelineTask(src0, List($Sort(_, sort), $Limit(_, count))))) =>
            op.newMR(base, src0, None, Some(sort), Some(count))
          case (_, (base, PipelineTask(src0, List($Match(_, sel), $Sort(_, sort), $Limit(_, count))))) =>
            op.newMR(base, src0, Some(sel), Some(sort), Some(count))
          case (_, (base, srcTask)) =>
            val (nb, task) = WorkflowTask.finish(base, srcTask)
            op.newMR(nb, task, None, None, None)
        }
      case $FoldLeft(head, tail) =>
        (ExprVar,
          FoldLeftTask(
            (WorkflowTask.finish _).tupled(head._2)._2,
            tail.map(_._2._2 match {
              case MapReduceTask(src, mr) =>
                // FIXME: $FoldLeft currently always reduces, but in future we’ll
                //        want to have more control.
                MapReduceTask(src,
                  mr applyLens MapReduce._out set
                    Some(MapReduce.WithAction(
                      MapReduce.Action.Reduce,
                      nonAtomic = Some(true))))
              // NB: `finalize` should ensure that the final op is always a
              //     $Reduce.
              case src => sys.error("not a mapReduce: " + src)
            })))
      case $Join(srcs) =>
        (ExprVar,
          JoinTask(srcs.map(x => (WorkflowTask.finish _).tupled(x._2)._2)))
    }

  val collectShapes: WorkflowF[(Workflow, (List[Reshape], Workflow))] => (List[Reshape], Workflow) = {
    case $Project(src, shape, _) =>
      ((x: List[Reshape]) => shape :: x).first(src._2)
    case x                       => (Nil, Term(x.map(_._1)))
  }

  // helper for rewriteRefs
  def prefixBase(base: DocVar): PartialFunction[DocVar, DocVar] =
    PartialFunction(base \\ _)

  // TODO: Make this a trait, and implement it for actual types, rather than all
  //       in here (already done for ExprOp and Reshape). (#438)
  def rewriteRefs[A <: WorkflowF[_]](
    op: A, applyVar0: PartialFunction[DocVar, DocVar]):
      A = {
    val applyVar = (f: DocVar) => applyVar0.lift(f).getOrElse(f)

    def applyFieldName(name: BsonField): BsonField = {
      applyVar(DocField(name)).deref.getOrElse(name) // TODO: Delete field if it's transformed away to nothing???
    }

    def applySelector(s: Selector): Selector = s.mapUpFields(PartialFunction(applyFieldName _))

    def applyMap[A](m: ListMap[BsonField, A]): ListMap[BsonField, A] = m.map(t => applyFieldName(t._1) -> t._2)

    def applyNel[A](m: NonEmptyList[(BsonField, A)]): NonEmptyList[(BsonField, A)] = m.map(t => applyFieldName(t._1) -> t._2)

    def applyFindQuery(q: FindQuery): FindQuery = {
      q.copy(
        query   = applySelector(q.query),
        max     = q.max.map(applyMap _),
        min     = q.min.map(applyMap _),
        orderby = q.orderby.map(applyNel _)
      )
    }

    (op match {
      case $Project(src, shape, xId) =>
        $Project(src, shape.rewriteRefs(applyVar0), xId)
      case $Group(src, grouped, by)  =>
        $Group(src, grouped.rewriteRefs(applyVar0), by.bimap(_.rewriteRefs(applyVar0), _.rewriteRefs(applyVar0)))
      case $Match(src, s)            => $Match(src, applySelector(s))
      case $Redact(src, e)           => $Redact(src, e.rewriteRefs(applyVar0))
      case $Unwind(src, f)           => $Unwind(src, applyVar(f))
      case $Sort(src, l)             => $Sort(src, applyNel(l))
      case g: $GeoNear[_]            =>
        g.copy(
          distanceField = applyFieldName(g.distanceField),
          query = g.query.map(applyFindQuery _))
      case _                          => op
    }).asInstanceOf[A]
  }

  final def refs[A <: WorkflowF[_]](op: A): List[DocVar] = {
    // FIXME: Sorry world
    val vf = new scala.collection.mutable.ListBuffer[DocVar]
    rewriteRefs(op, { case v => vf += v; v })
    vf.toList
  }

  def rewrite[A <: WorkflowF[_]](op: A, base: ExprOp.DocVar):
      (A, ExprOp.DocVar) =
    (rewriteRefs(op, prefixBase(base)) -> (op match {
      case $Group(_, _, _)   => ExprOp.DocVar.ROOT()
      case $Project(_, _, _) => ExprOp.DocVar.ROOT()
      case _                  => base
    }))

  def simpleShape(op: Workflow): Option[List[BsonField.Leaf]] = op.unFix match {
    case $Pure(Bson.Doc(value))             => Some(value.keys.toList.map(BsonField.Name))
    case $Project(_, Reshape(value), _) => Some(value.keys.toList)
    case $SimpleMap(_, js, _, _) =>
      js(JsCore.Ident("_").fix).unFix match {
        case JsCore.Obj(value) => Some(value.keys.toList.map(BsonField.Name))
        case _ => None
      }
    case $Group(_, Grouped(value), _) => Some(value.keys.toList)
    case $Unwind(src, _) => simpleShape(src)
    case sp: ShapePreservingF[_] => simpleShape(sp.src)
    case _ => None
  }

  /** Operations without an input. */
  sealed trait SourceOp extends WorkflowF[Nothing]

  /** Operations with a single source op. */
  sealed trait SingleSourceF[A] extends WorkflowF[A] {
    def src: A
    def reparent[B](newSrc: B): SingleSourceF[B]
    /**
      Reparenting that handles coalescing (but is more restrictive as a result).
      */
    def reparentW[B](newSrc: Workflow): Workflow =
      coalesce(Term(reparent(newSrc)))
  }

  /**
   * This should be renamed once the other PipelineOp goes away, but it is the
   * subset of operations that can ever be pipelined.
   */
  abstract sealed class PipelineF[A](op: String) extends SingleSourceF[A] {
    override def reparent[B](newSrc: B): PipelineF[B]
    def rhs: Bson
    def bson: Bson.Doc = Bson.Doc(ListMap(op -> rhs))
  }
  abstract sealed class ShapePreservingF[A](op: String) extends PipelineF[A](op)

  /**
   * Flattens the sequence of operations like so:
   *
   *   chain(
   *     $read(Path.fileAbs("foo")),
   *     $match(Selector.Where(Js.Bool(true))),
   *     $limit(7))
   * ==
   *   val read = $read(Path.fileAbs("foo"))
   *   val match = $match(Selector.Where(Js.Bool(true))(read)
   *   $limit(7)(match)
   */
  def chain(src: Workflow, op1: WorkflowOp, ops: (WorkflowOp)*): Workflow =
    ops.foldLeft(op1(src))((s, o) => o(s))

  /**
    Performs some irreversible conversions, meant to be used once, after the
    entire workflow has been generated.
    */
  // probable conversions
  // to $Map:          $Project
  // to $FlatMap:      $Match, $Limit (using scope), $Skip (using scope), $Unwind, $GeoNear
  // to $Map/$Reduce:  $Group
  // ???:              $Redact
  // none:             $Sort
  // NB: We don’t convert a $Project after a map/reduce op because it could
  //     affect the final shape unnecessarily.
  private def finalize0(op: Workflow): Workflow = op.unFix match {
    case mr: MapReduceF[_] => mr.src.unFix match {
      case $Project(src, shape, _)  =>
        shape.toJs.fold(
          κ(op.descend(finalize(_))),
          x => finalize(mr.reparentW($simpleMap(x, Nil, ListMap())(src))))
      case uw @ $Unwind(_, _)          => finalize(mr.reparentW(Term(uw.flatmapop)))
      case sm @ $SimpleMap(_, _, _, _) => finalize(mr.reparentW(Term(sm.raw)))
      case _                           => op.descend(finalize(_))
    }
    case op @ $FoldLeft(head, tail) =>
      $foldLeft(
        finalize(chain(
          head,
          $project(Reshape(ListMap(
            ExprName -> -\/(ExprOp.DocVar.ROOT()))),
            IncludeId))),
        finalize(tail.head.unFix match {
          case $Reduce(_, _, _) => tail.head
          case _ => chain(tail.head, $reduce($Reduce.reduceFoldLeft, ListMap()))
        }),
        tail.tail.map(x => finalize(x.unFix match {
          case $Reduce(_, _, _) => x
          case _ => chain(x, $reduce($Reduce.reduceFoldLeft, ListMap()))
        })):_*)
    case _ => op.descend(finalize(_))
  }

  def finalize(op: Workflow): Workflow = {
    val finalized = finalize0(finish(op))

    def fixShape(wf: Workflow) =
      Workflow.simpleShape(wf).fold(
        finalized)(
        n => $project(Reshape(n.map(_.toName -> -\/(Include)).toListMap), IgnoreId)(finalized))

    def promoteKnownShape(wf: Workflow): Workflow = wf.unFix match {
      case $SimpleMap(_, _, _, _)  => fixShape(wf)
      case sp: ShapePreservingF[_] => promoteKnownShape(sp.src)
      case _                       => finalized
    }

    promoteKnownShape(finalized)
  }

  case class $Pure(value: Bson) extends SourceOp
  def $pure(value: Bson) = coalesce(Term[WorkflowF]($Pure(value)))

  case class $Read(coll: Collection) extends SourceOp
  def $read(coll: Collection) = coalesce(Term[WorkflowF]($Read(coll)))

  case class $Match[A](src: A, selector: Selector)
      extends ShapePreservingF[A]("$match") {
    def reparent[B](newSrc: B) = copy(src = newSrc)
    def rhs = selector.bson
  }
  object $Match {
    def make(selector: Selector)(src: Workflow): Workflow =
      coalesce(Term($Match(src, selector)))
  }
  val $match = $Match.make _

  private def alwaysPipePipe(op: PipelineF[Workflow]):
      (DocVar, WorkflowTask, Pipeline) = {
    lazy val (base, crushed) = (WorkflowTask.finish _).tupled(op.src.para(crush))
    // TODO: this is duplicated in `WorkflowBuilder.rewrite`
    def repairBase(base: DocVar) = op match {
      case $Group(_, _, _)   => DocVar.ROOT()
      case $Project(_, _, _) => DocVar.ROOT()
      case _                  => base
    }
    op.src.unFix match {
      case p: PipelineF[Workflow] => pipeline(p).cata(
        {
          case (base, up, prev) =>
            val (nb, task) = WorkflowTask.finish(base, up)
            (repairBase(nb),
              task,
              prev :+ rewriteRefs(PipelineFTraverse.void(op), prefixBase(nb)))
        },
        (repairBase(base),
          crushed,
          List(rewriteRefs(PipelineFTraverse.void(op), prefixBase(base)))))
      case _ =>
        (repairBase(base),
          crushed,
          List(rewriteRefs(PipelineFTraverse.void(op), prefixBase(base))))
    }
  }

  case class $Project[A](src: A, shape: Reshape, idExclusion: IdHandling)
      extends PipelineF[A]("$project") {
    def reparent[B](newSrc: B): $Project[B] = copy(src = newSrc)
    def rhs = idExclusion match {
      case IdHandling.ExcludeId =>
        Bson.Doc(shape.bson.value + (Workflow.IdLabel -> Bson.Bool(false)))
      case _         => shape.bson
    }
    def empty: $Project[A] = $Project.EmptyDoc(src)

    def set(field: BsonField, value: ExprOp \/ Reshape): $Project[A] =
      $Project(src,
        shape.set(field, value),
        if (field == IdName) IncludeId else idExclusion)

    def get(ref: DocVar): Option[ExprOp \/ Reshape] = ref match {
      case DocVar(_, Some(field)) => shape.get(field)
      case _                      => Some(\/-(shape))
    }

    def getAll: List[(BsonField, ExprOp)] = {
      val all = Reshape.getAll(shape)
      idExclusion match {
        case IncludeId => all.collectFirst {
          case (IdName, _) => all
        }.getOrElse((IdName, Include) :: all)
        case _         => all
      }
    }

    def setAll(fvs: Iterable[(BsonField, ExprOp \/ Reshape)]): $Project[A] =
      $Project(
        src,
        Reshape.setAll(shape, fvs),
        if (fvs.exists(_._1 == IdName)) IncludeId else idExclusion)

    def deleteAll(fields: List[BsonField]): $Project[A] =
      $Project(src,
        Reshape.setAll(Reshape.EmptyDoc,
          Reshape.getAll(this.shape)
            .filterNot(t => fields.exists(t._1.startsWith(_)))
            .map(t => t._1 -> -\/ (t._2))),
        if (fields.contains(IdName)) ExcludeId else idExclusion)

    def id: $Project[A] = {
      def loop(prefix: Option[BsonField], p: $Project[A]): $Project[A] = {
        def nest(child: BsonField): BsonField =
          prefix.map(_ \ child).getOrElse(child)

        $Project(
          p.src,
          Reshape(
            p.shape.value.transform {
              case (k, v) =>
                v.fold(
                  _ => -\/  (ExprOp.DocVar.ROOT(nest(k))),
                  r =>  \/- (loop(Some(nest(k)), $Project(p.src, r, p.idExclusion)).shape))
            }),
          p.idExclusion)
      }

      loop(None, this)
    }
  }
  object $Project {
    def make(shape: Reshape, id: IdHandling)(src: Workflow): Workflow =
      coalesce(Term($Project(src, shape, id)))

    def EmptyDoc[A](src: A) = $Project(src, Reshape.EmptyDoc, ExcludeId)
  }
  val $project = $Project.make _

  case class $Redact[A](src: A, value: ExprOp)
      extends PipelineF[A]("$redact") {
    def reparent[B](newSrc: B) = copy(src = newSrc)
    def rhs = value.bson
  }
  object $Redact {
    def make(value: ExprOp)(src: Workflow): Workflow =
      coalesce(Term($Redact(src, value)))

    val DESCEND = ExprOp.DocVar(ExprOp.DocVar.Name("DESCEND"),  None)
    val PRUNE   = ExprOp.DocVar(ExprOp.DocVar.Name("PRUNE"),    None)
    val KEEP    = ExprOp.DocVar(ExprOp.DocVar.Name("KEEP"),     None)
  }
  val $redact = $Redact.make _

  case class $Limit[A](src: A, count: Long)
      extends ShapePreservingF[A]("$limit") {
    // TODO: If the preceding is a $Match, and it or its source isn’t
    //       pipelineable, then return a FindQuery combining the match and this
    //       limit
    def reparent[B](newSrc: B) = copy(src = newSrc)
    def rhs = Bson.Int64(count)
  }
  object $Limit {
    def make(count: Long)(src: Workflow): Workflow =
      coalesce(Term($Limit(src, count)))
  }
  val $limit = $Limit.make _

  case class $Skip[A](src: A, count: Long)
      extends ShapePreservingF[A]("$skip") {
    // TODO: If the preceding is a $Match (or a limit preceded by a $Match),
    //       and it or its source isn’t pipelineable, then return a FindQuery
    //       combining the match and this skip
    def reparent[B](newSrc: B) = copy(src = newSrc)
    def rhs = Bson.Int64(count)
  }
  object $Skip {
    def make(count: Long)(src: Workflow): Workflow =
      coalesce(Term($Skip(src, count)))
  }
  val $skip = $Skip.make _

  case class $Unwind[A](src: A, field: ExprOp.DocVar)
      extends PipelineF[A]("$unwind") {
    lazy val flatmapop = $SimpleMap(src, JsMacro(identity), List(JsMacro(field.toJs(_))), ListMap())
    def reparent[B](newSrc: B) = copy(src = newSrc)
    def rhs = field.bson
  }
  object $Unwind {
    def make(field: ExprOp.DocVar)(src: Workflow): Workflow =
      coalesce(Term($Unwind(src, field)))
  }
  val $unwind = $Unwind.make _

  case class $Group[A](src: A, grouped: Grouped, by: ExprOp \/ Reshape)
      extends PipelineF[A]("$group") {

    def reparent[B](newSrc: B) = copy(src = newSrc)
    def rhs = {
      val Bson.Doc(m) = grouped.bson
      Bson.Doc(m + (Workflow.IdLabel -> by.fold(_.bson, _.bson)))
    }

    def empty = copy(grouped = Grouped(ListMap()))

    def getAll: List[(BsonField.Leaf, ExprOp.GroupOp)] =
      grouped.value.toList

    def deleteAll(fields: List[BsonField.Leaf]): Workflow.$Group[A] = {
      empty.setAll(getAll.filterNot(t => fields.exists(t._1 == _)))
    }

    def setAll(vs: Seq[(BsonField.Leaf, ExprOp.GroupOp)]) = copy(grouped = Grouped(ListMap(vs: _*)))
  }
  object $Group {
    def make(
      grouped: Grouped, by: ExprOp \/ Reshape)(
      src: Workflow):
        Workflow =
      coalesce(Term($Group(src, grouped, by)))
  }
  val $group = $Group.make _

  case class $Sort[A](src: A, value: NonEmptyList[(BsonField, SortType)])
      extends ShapePreservingF[A]("$sort") {
    def reparent[B](newSrc: B) = copy(src = newSrc)
    // Note: ListMap preserves the order of entries.
    def rhs = Bson.Doc(ListMap((value.map { case (k, t) => k.asText -> t.bson }).list: _*))
  }
  object $Sort {
    def make(value: NonEmptyList[(BsonField, SortType)])(src: Workflow):
        Workflow =
      coalesce(Term($Sort(src, value)))
  }
  val $sort = $Sort.make _

  /**
   * TODO: If an $Out has anything after it, we need to either do
   *   $seq($out(src, dst), after($read(dst), ...))
   * or
   *   $Fork(src, List($out(_, dst), after(_, ...)))
   * The latter seems preferable, but currently the forking semantics are not
   * clear.
   */
  case class $Out[A](src: A, collection: Collection)
      extends ShapePreservingF[A]("$out") {
    def reparent[B](newSrc: B) = copy(src = newSrc)
    def rhs = Bson.Text(collection.name)
  }
  object $Out {
    def make(collection: Collection)(src: Workflow): Workflow =
      coalesce(Term($Out(src, collection)))
  }
  val $out = $Out.make _

  case class $GeoNear[A](
    src: A,
    near: (Double, Double), distanceField: BsonField,
    limit: Option[Int], maxDistance: Option[Double],
    query: Option[FindQuery], spherical: Option[Boolean],
    distanceMultiplier: Option[Double], includeLocs: Option[BsonField],
    uniqueDocs: Option[Boolean])
      extends PipelineF[A]("$geonear") {
    def reparent[B](newSrc: B) = copy(src = newSrc)
    def rhs = Bson.Doc(List(
      List("near"           -> Bson.Arr(Bson.Dec(near._1) :: Bson.Dec(near._2) :: Nil)),
      List("distanceField"  -> distanceField.bson),
      limit.toList.map(limit => "limit" -> Bson.Int32(limit)),
      maxDistance.toList.map(maxDistance => "maxDistance" -> Bson.Dec(maxDistance)),
      query.toList.map(query => "query" -> query.bson),
      spherical.toList.map(spherical => "spherical" -> Bson.Bool(spherical)),
      distanceMultiplier.toList.map(distanceMultiplier => "distanceMultiplier" -> Bson.Dec(distanceMultiplier)),
      includeLocs.toList.map(includeLocs => "includeLocs" -> includeLocs.bson),
      uniqueDocs.toList.map(uniqueDocs => "uniqueDocs" -> Bson.Bool(uniqueDocs))
    ).flatten.toListMap)
  }
  object $GeoNear {
    def make(
      near: (Double, Double), distanceField: BsonField,
      limit: Option[Int], maxDistance: Option[Double],
      query: Option[FindQuery], spherical: Option[Boolean],
      distanceMultiplier: Option[Double], includeLocs: Option[BsonField],
      uniqueDocs: Option[Boolean])(
      src: Workflow):
        Workflow =
      coalesce(Term($GeoNear(src, near, distanceField, limit, maxDistance, query, spherical, distanceMultiplier, includeLocs, uniqueDocs)))
  }
  val $geoNear = $GeoNear.make _

  sealed trait MapReduceF[A] extends SingleSourceF[A] {
    def newMR(base: DocVar, src: WorkflowTask, sel: Option[Selector], sort: Option[NonEmptyList[(BsonField, SortType)]], count: Option[Long]): (ExprOp.DocVar, WorkflowTask)
  }

  /**
    Takes a function of two parameters. The first is the current key (which
    defaults to `this._id`, but may have been overridden by previous
    [Flat]$Maps) and the second is the document itself. The function must
    return a 2-element array containing the new key and new value.
    */
  case class $Map[A](src: A, fn: Js.AnonFunDecl, scope: Scope) extends MapReduceF[A] {
    import $Map._
    import Js._

    def newMR(base: DocVar, src: WorkflowTask, sel: Option[Selector], sort: Option[NonEmptyList[(BsonField, SortType)]], count: Option[Long]) =
      (ExprVar,
        MapReduceTask(src,
          MapReduce(
            mapFn(base match {
              case DocVar(DocVar.ROOT, None) => this.fn
              case _ => compose(this.fn, mapProject(base))
            }),
            $Reduce.reduceNOP,
            selection = sel, inputSort = sort, limit = count, scope = scope)))

    def reparent[B](newSrc: B) = copy(src = newSrc)
  }
  object $Map {
    import JsCore._

    def make(fn: Js.AnonFunDecl, scope: Scope)(src: Workflow):
        Workflow =
      coalesce(Term($Map(src, fn, scope)))

    def compose(g: Js.AnonFunDecl, f: Js.AnonFunDecl): Js.AnonFunDecl =
      Js.AnonFunDecl(List("key", "value"), List(
        Js.Return(Js.Call(Js.Select(g, "apply"),
          List(Js.Null, Js.Call(f, List(Js.Ident("key"), Js.Ident("value"))))))))

    def mapProject(base: DocVar) =
      Js.AnonFunDecl(List("key", "value"), List(
        Js.Return(Js.AnonElem(List(Js.Ident("key"), base.toJs(JsCore.Ident("value").fix).toJs)))))


    def mapKeyVal(idents: (String, String), key: Js.Expr, value: Js.Expr) =
      Js.AnonFunDecl(List(idents._1, idents._2),
        List(Js.Return(Js.AnonElem(List(key, value)))))
    def mapMap(ident: String, transform: Js.Expr) =
      mapKeyVal(("key", ident), Js.Ident("key"), transform)
    val mapNOP = mapMap("value", Js.Ident("value"))

    def finalizerFn(fn: Js.Expr) =
      Js.AnonFunDecl(List("key", "value"),
        List(Js.Return(Js.Access(
          Js.Call(fn, List(Js.Ident("key"), Js.Ident("value"))),
          Js.Num(1, false)))))

    def mapFn(fn: Js.Expr) =
      Js.AnonFunDecl(Nil,
        List(Js.Call(Js.Select(Js.Ident("emit"), "apply"),
          List(
            Js.Null,
            Js.Call(fn, List(Js.Select(Js.This, IdLabel), Js.This))))))
  }
  val $map = $Map.make _

  // FIXME: this one should become $Map, with the other one being replaced by
  // a new op that combines a map and reduce operation?
  case class $SimpleMap[A](src: A, expr: JsMacro, flatten: List[JsMacro], scope: Scope)
      extends MapReduceF[A] {
    def getAll: Option[List[BsonField]] = {
      def loop(x: Term[JsCore]): Option[List[BsonField]] = x.unFix match {
        case JsCore.Obj(values) => Some(values.toList.flatMap { case (k, v) =>
          val n = BsonField.Name(k)
          loop(v).map(_.map(n \ _)).getOrElse(List(n))
        })
        case _ => None
      }
      // Note: this is not safe if `expr` inspects the argument to decide what
      // JS to construct, but all we need here is names of fields that we may
      // be able to optimize away.
      loop(expr(JsCore.Ident("?").fix))
    }

    def deleteAll(fields: List[BsonField]): $SimpleMap[A] = {
      def loop(x: Term[JsCore], fields: List[List[BsonField.Leaf]]): Option[Term[JsCore]] = x.unFix match {
        case JsCore.Obj(values) => Some(JsCore.Obj(
          values.collect(Function.unlift[(String, Term[JsCore]), (String, Term[JsCore])] { t =>
            val (k, v) = t
            if (fields contains List(BsonField.Name(k))) None
            else {
              val v1 = loop(v, fields.collect {
                case BsonField.Name(k) :: tail => tail
              }).getOrElse(v)
              v1.unFix match {
                case JsCore.Obj(values) if values.isEmpty => None
                case _ => Some(k -> v1)
              }
            }
          })).fix)
        case _ => Some(x)
      }

      $SimpleMap(src,
        JsMacro(base => loop(expr(base), fields.map(_.flatten)).getOrElse(JsCore.Literal(Js.Null).fix)),
        flatten,
        scope)
    }


    private def fn: Js.AnonFunDecl = {
      import JsCore._

      def body(fs: List[(JsCore.Ident, JsMacro)]) =
        Js.AnonFunDecl(List("key", "value"),
          List(
            Js.VarDef(List("rez" -> Js.AnonElem(Nil))),
            fs.foldLeft[Js.Stmt](Js.Block(
                List(
                  Js.VarDef(List("each" -> Js.Call(Js.Ident("clone"), List(Js.Ident("value")))))) ++
                fs.map { case (n, x) =>
                  safeAssign(x(Ident("each").fix), Access(x(Ident("value").fix), n.fix).fix) } ++
                List(Call(Select(Ident("rez").fix, "push").fix,
                  List(
                    Arr(List(
                      Call(Ident("ObjectId").fix, Nil).fix,
                       expr(Ident("each").fix))).fix)).fix.toJs))) { case (inner, (n, m)) =>
                         Js.ForIn(Js.Ident(n.name), m(Ident("value").fix).toJs, inner)
                       },
            Js.Return(Js.Ident("rez"))))

      flatten match {
        case x :: Nil => body(List(Ident("elem") -> x))
        case _        => body(flatten.zipWithIndex.map { case (x, i) => Ident("elem" + i) -> x })
      }
    }

    def >>>(that: $SimpleMap[A]) =
      $SimpleMap(
        this.src,
        this.expr >>> that.expr,
        this.flatten ++ that.flatten.map(this.expr >>> _),
        this.scope ++ that.scope)

    def raw = {
      import JsCore._
      if (flatten.isEmpty)
        $Map(src,
          Js.AnonFunDecl(List("key", "value"), List(
            Js.Return(Arr(List(
              Ident("key").fix,
              expr(Ident("value").fix))).fix.toJs))),
          scope)
      else
        $FlatMap(src, fn, scope ++ ListMap("clone" -> Bson.JavaScript($SimpleMap.jsClone)))
    }

    def newMR(base: DocVar, src: WorkflowTask, sel: Option[Selector], sort: Option[NonEmptyList[(BsonField, SortType)]], count: Option[Long]) =
      raw.newMR(base, src, sel, sort, count)

    def reparent[B](newSrc: B) = copy(src = newSrc)
  }
  object $SimpleMap {
    def make(expr: JsMacro, flatten: List[JsMacro], scope: Scope)(src: Workflow): Workflow =
      coalesce(Term($SimpleMap(src, expr, flatten, scope)))

    val jsRemove =
      Js.AnonFunDecl(List("obj", "field"), List(
        Js.VarDef(List("dest" -> Js.AnonObjDecl(Nil))),
        Js.ForIn(Js.Ident("i"), Js.Ident("obj"),
          Js.If(Js.BinOp("!=", Js.Ident("i"), Js.Ident("field")),
            Js.BinOp("=",
              Js.Access(Js.Ident("dest"), Js.Ident("i")),
              Js.Access(Js.Ident("obj"), Js.Ident("i"))),
            None)),
        Js.Return(Js.Ident("dest"))))

    val jsClone =
      Js.AnonFunDecl(List("src"), List(
        Js.If(
          Js.BinOp("||",
            Js.BinOp("!=", Js.UnOp("typeof", Js.Ident("src")), Js.Str("object")),
            Js.BinOp("==", Js.Ident("src"), Js.Null)),
          Js.Return(Js.Ident("src")),
          None),
        Js.VarDef(List("dest" -> Js.New(Js.Select(Js.Ident("src"), "constructor")))),
        Js.ForIn(Js.Ident("i"), Js.Ident("src"),
          Js.BinOp ("=",
            Js.Access(Js.Ident("dest"), Js.Ident("i")),
            Js.Call(Js.Ident("clone"), List(
              Js.Access(Js.Ident("src"), Js.Ident("i")))))),
        Js.Return(Js.Ident("dest"))))
  }
  val $simpleMap = $SimpleMap.make _

  /**
    Takes a function of two parameters. The first is the current key (which
    defaults to `this._id`, but may have been overridden by previous
    [Flat]$Maps) and the second is the document itself. The function must
    return an array of 2-element arrays, each containing a new key and a new
    value.
    */
  case class $FlatMap[A](src: A, fn: Js.AnonFunDecl, scope: Scope)
      extends MapReduceF[A] {
    import $FlatMap._
    import Js._

    def newMR(base: DocVar, src: WorkflowTask, sel: Option[Selector], sort: Option[NonEmptyList[(BsonField, SortType)]], count: Option[Long]) =
      (ExprVar,
        MapReduceTask(src,
          MapReduce(
            mapFn(base match {
              case DocVar(DocVar.ROOT, None) => this.fn
              case _ => $Map.compose(this.fn, $Map.mapProject(base))
            }),
            $Reduce.reduceNOP,
            selection = sel, inputSort = sort, limit = count, scope = scope)))

    def reparent[B](newSrc: B) = copy(src = newSrc)
  }
  object $FlatMap {
    import Js._

    def make(fn: Js.AnonFunDecl, scope: Scope)(src: Workflow):
        Workflow =
      coalesce(Term($FlatMap(src, fn, scope)))

    private def composition(g: Js.AnonFunDecl, f: Js.AnonFunDecl) =
      Call(
        Select(Call(f, List(Ident("key"), Ident("value"))), "map"),
        List(AnonFunDecl(List("args"), List(
          Return(Call(Select(g, "apply"), List(Null, Ident("args"))))))))

    def kleisliCompose(g: Js.AnonFunDecl, f: Js.AnonFunDecl) =
      AnonFunDecl(List("key", "value"), List(
        Return(
          Call(
            Select(Select(AnonElem(Nil), "concat"), "apply"),
            List(AnonElem(Nil), composition(g, f))))))

    def mapCompose(g: Js.AnonFunDecl, f: Js.AnonFunDecl) =
      AnonFunDecl(List("key", "value"), List(Return(composition(g, f))))

    def mapFn(fn: Js.Expr) =
      AnonFunDecl(Nil,
        List(
          Call(
            Select(
              Call(fn, List(Select(This, IdLabel), This)),
              "map"),
            List(AnonFunDecl(List("__rez"),
              List(Call(Select(Ident("emit"), "apply"),
                List(Null, Ident("__rez")))))))))
  }
  val $flatMap = $FlatMap.make _

  /**
    Takes a function of two parameters – a key and an array of values. The
    function must return a single value.
    */
  case class $Reduce[A](src: A, fn: Js.AnonFunDecl, scope: Scope)
      extends MapReduceF[A] {
    import $Reduce._

    def newMR(base: DocVar, src: WorkflowTask, sel: Option[Selector], sort: Option[NonEmptyList[(BsonField, SortType)]], count: Option[Long]) =
      (ExprVar,
        MapReduceTask(src,
          MapReduce(
            $Map.mapFn(base match {
              case DocVar(DocVar.ROOT, None) => $Map.mapNOP
              case _                         => $Map.mapProject(base)
            }),
            this.fn,
            selection = sel, inputSort = sort, limit = count, scope = scope)))

    def reparent[B](newSrc: B) = copy(src = newSrc)
  }
  object $Reduce {
    import JsCore._

    def make(fn: Js.AnonFunDecl, scope: Scope)(src: Workflow):
        Workflow =
      coalesce(Term($Reduce(src, fn, scope)))

    val reduceNOP =
      Js.AnonFunDecl(List("key", "values"), List(
        Js.Return(Access(Ident("values").fix, Literal(Js.Num(0, false)).fix).fix.toJs)))

    val reduceFoldLeft =
      Js.AnonFunDecl(List("key", "values"), List(
        Js.VarDef(List("rez" -> Js.AnonObjDecl(Nil))),
        Js.Call(Select(Ident("values").fix, "forEach").fix.toJs,
          List(Js.AnonFunDecl(List("value"),
            List(copyAllFields(Ident("value").fix, Ident("rez").fix))))),
        Js.Return(Js.Ident("rez"))))
  }
  val $reduce = $Reduce.make _

  /**
    Performs a sequence of operations, sequentially, merging their results.
    */
  case class $FoldLeft[A](head: A, tail: NonEmptyList[A])
      extends WorkflowF[A]
  object $FoldLeft {
    def make(head: Workflow, tail: NonEmptyList[Workflow]):
        Workflow =
      coalesce(Term($FoldLeft(head, tail)))
  }
  def $foldLeft(first: Workflow, second: Workflow, rest: Workflow*) =
    $FoldLeft.make(first, NonEmptyList.nel(second, rest.toList))

  case class $Join[A](ssrcs: Set[A]) extends WorkflowF[A]
  object $Join {
    def make(srcs: Set[Workflow]): Workflow =
      coalesce(Term($Join(srcs)))
  }
  val $join = $Join.make _

  implicit def WorkflowFRenderTree(implicit RS: RenderTree[Selector], RE: RenderTree[ExprOp], RG: RenderTree[Grouped], RJ: RenderTree[Js], RJM: RenderTree[JsMacro]):
      RenderTree[WorkflowF[Unit]] =
    new RenderTree[WorkflowF[Unit]] {
      def nodeType(subType: String) = "Workflow" :: subType :: Nil

      def render(v: WorkflowF[Unit]) = v match {
        case $Pure(value)       => Terminal(value.toString, nodeType("$Pure"))
        case $Read(coll)        => Terminal(coll.name, nodeType("$Read"))
        case $Match(_, sel)     =>
          NonTerminal("", RS.render(sel) :: Nil, nodeType("$Match"))
        case $Project(_, shape, xId) =>
          NonTerminal("",
            Reshape.renderReshape(shape) :+
              Terminal("", nodeType(xId.toString)),
            nodeType("$Project"))
        case $Redact(_, value) => NonTerminal("",
          RE.render(value) ::
            Nil,
          nodeType("$Redact"))
        case $Limit(_, count)  => Terminal(count.toString, nodeType("$Limit"))
        case $Skip(_, count)   => Terminal(count.toString, nodeType("$Skip"))
        case $Unwind(_, field) => Terminal(field.toString, nodeType("$Unwind"))
        case $Group(_, grouped, -\/ (expr))
            => NonTerminal("",
              RG.render(grouped) ::
                Terminal(expr.toString, nodeType("By")) ::
                Nil,
              nodeType("$Group"))
        case $Group(_, grouped, \/- (by))
            => NonTerminal("",
              RG.render(grouped) ::
                NonTerminal("", Reshape.renderReshape(by), nodeType("By")) ::
                Nil,
              nodeType("$Group"))
        case $Sort(_, value)   => NonTerminal("",
          value.map { case (field, st) => Terminal(field.asText + " -> " + st, nodeType("SortKey")) }.toList,
          nodeType("$Sort"))
        case $GeoNear(_, near, distanceField, limit, maxDistance, query, spherical, distanceMultiplier, includeLocs, uniqueDocs)
            => NonTerminal("",
              Terminal(near.toString, nodeType("$GeoNear") :+ "Near") ::
                Terminal(distanceField.toString, nodeType("$GeoNear") :+ "DistanceField") ::
                Terminal(limit.toString, nodeType("$GeoNear") :+ "Limit") ::
                Terminal(maxDistance.toString, nodeType("$GeoNear") :+ "MaxDistance") ::
                Terminal(query.toString, nodeType("$GeoNear") :+ "Query") ::
                Terminal(spherical.toString, nodeType("$GeoNear") :+ "Spherical") ::
                Terminal(distanceMultiplier.toString, nodeType("$GeoNear") :+ "DistanceMultiplier") ::
                Terminal(includeLocs.toString, nodeType("$GeoNear") :+ "IncludeLocs") ::
                Terminal(uniqueDocs.toString, nodeType("$GeoNear") :+ "UniqueDocs") ::
                Nil,
              nodeType("$GeoNear"))

        case $Map(_, fn, scope) => NonTerminal("",
          RJ.render(fn) ::
            Terminal((scope ∘ (_.toJs.render(2))).toString, nodeType("$Map") :+ "Scope") ::
            Nil,
          nodeType("$Map"))
        case $FlatMap(_, fn, scope) => NonTerminal("",
          RJ.render(fn) ::
            Terminal((scope ∘ (_.toJs.render(2))).toString, nodeType("$Map") :+ "Scope") ::
            Nil,
          nodeType("$FlatMap"))
        case $SimpleMap(_, expr, flatten, scope) => NonTerminal("",
            RJM.render(expr).copy(nodeType = nodeType("$SimpleMap") :+ "Expr") ::
              (flatten.map(RJM.render(_).copy(nodeType = nodeType("$SimpleMap") :+ "Flatten")) :+
                Terminal((scope ∘ (_.toJs.render(2))).toString, nodeType("$SimpleMap") :+ "Scope")),
            nodeType("$SimpleMap"))
        case $Reduce(_, fn, scope) => NonTerminal("",
          RJ.render(fn) ::
            Terminal((scope ∘ (_.toJs.render(2))).toString, nodeType("$Map") :+ "Scope") ::
            Nil,
          nodeType("$Reduce"))
        case $Out(_, coll) => Terminal(coll.name, nodeType("$Out"))
        case $FoldLeft(_, _) => Terminal("", nodeType("$FoldLeft"))
        case $Join(_) => Terminal("", nodeType("$Join"))
      }
    }

  implicit def WorkflowRenderTree(implicit RW: RenderTree[WorkflowF[Unit]]):
      RenderTree[Workflow] =
    new RenderTree[Workflow] {
      def nodeType(subType: String) = "Workflow" :: subType :: Nil

      def chain(op: Workflow): List[RenderedTree] = op.unFix match {
        case ss: SingleSourceF[Workflow] =>
          chain(ss.src) :+ RW.render(Traverse[WorkflowF].void(ss))
        case ms => List(render(Term(ms)))
      }

      def render(v: Workflow) = v.unFix match {
        case op: SourceOp    => RW.render(op.void)
        case _: SingleSourceF[Workflow] =>
          NonTerminal("", chain(v), nodeType("Chain"))
        case $FoldLeft(_, _) =>
          NonTerminal("", v.children.map(render(_)), nodeType("$FoldLeft"))
        case $Join(srcs)     =>
          NonTerminal("", v.children.map(render(_)), nodeType("$Join"))
      }
    }
}
