package io.computenode.cyfra.dsl

import io.computenode.cyfra.dsl.Algebra.{FromExpr, given_Conversion_Int_Int32}
import io.computenode.cyfra.dsl.Expression.*
import io.computenode.cyfra.dsl.Value.*
import io.computenode.cyfra.*
import io.computenode.cyfra.spirv.SpirvTypes.typeStride
import io.computenode.cyfra.spirv.compilers.DSLCompiler
import izumi.reflect.Tag

import scala.compiletime.*
import scala.deriving.Mirror

type SomeGStruct[T <: GStruct[T]] = GStruct[T]
abstract class GStruct[T <: GStruct[T] : Tag : GStructSchema] extends Value with Product:
  self: T =>
  private[cyfra] var _schema: GStructSchema[T] = summon[GStructSchema[T]] // a nasty hack
  def schema: GStructSchema[T] = _schema
  lazy val tree: E[T] = schema.tree(self)
  private[dsl] var _name = sourcecode.Name("Unknown")
  override def name: sourcecode.Name = _name

case class GStructSchema[T <: GStruct[T]: Tag](
  fields: List[(String, FromExpr[_], Tag[_])],
  dependsOn: Option[E[T]],
  fromTuple: (Tuple, sourcecode.Name) => T
):
  given GStructSchema[T] = this
  val structTag = summon[Tag[T]]

  def tree(t: T): E[T] =
    dependsOn match
      case Some(dep) => dep
      case None =>
        ComposeStruct[T](t.productIterator.toList.asInstanceOf[List[Value]], this)

  def create(values: List[Value], schema: GStructSchema[T])(using name: sourcecode.Name): T =
    val valuesTuple = Tuple.fromArray(values.toArray)
    val newStruct = fromTuple(valuesTuple, name)
    newStruct._schema = schema
    newStruct

  def fromTree(e: E[T])(using sourcecode.Name): T =
    create(fields.zipWithIndex.map {
      case ((_, fromExpr, tag), i) =>
        fromExpr.asInstanceOf[FromExpr[Value]]
          .fromExpr(GetField[T, Value](e, i)(
            this,
            tag.asInstanceOf[Tag[Value]]
          ).asInstanceOf[E[Value]])
    }, this.copy(dependsOn = Some(e)))

  val gStructTag = summon[Tag[GStruct[_]]]
  val totalStride: Int = fields.map {
    case (_, fromExpr, t) if t <:< gStructTag =>
      val constructor = fromExpr.asInstanceOf[GStructConstructor[_]]
      constructor.schema.totalStride
    case (_, _, t) =>
      typeStride(t)
  }.sum

trait GStructConstructor[T <: GStruct[T]] extends FromExpr[T]:
  def schema: GStructSchema[T]
  def fromExpr(expr: E[T])(using sourcecode.Name): T

given [T <: GStruct[T] : GStructSchema]: GStructConstructor[T] with
  def schema: GStructSchema[T] = summon[GStructSchema[T]]
  def fromExpr(expr: E[T])(using sourcecode.Name): T = schema.fromTree(expr)

case class ComposeStruct[T <: GStruct[T] : Tag](
  fields: List[Value],
  resultSchema: GStructSchema[T]
) extends Expression[T]

case class GetField[S <: GStruct[S] : GStructSchema, T <: Value : Tag](
  struct: E[S],
  fieldIndex: Int
) extends Expression[T]:
  val resultSchema: GStructSchema[S] = summon[GStructSchema[S]]

private inline def constValueTuple[T <: Tuple]: T =
    (inline erasedValue[T] match
        case _: EmptyTuple => EmptyTuple
        case _: (t *: ts) => constValue[t] *: constValueTuple[ts]
      ).asInstanceOf[T]


type TagOf[T] = Tag[T]
type FromExprOf[T] = T match
  case Value => FromExpr[T]
  case _ => Nothing

// todo quick solution for now, rewrite to iteration over Tuple / quotes macro
inline given derived[T <: GStruct[T] : Tag](using m: Mirror.Of[T]): GStructSchema[T] =
    inline m match
        case m: Mirror.ProductOf[T] =>
          // quick prove that all fields <:< value
          summonAll[Tuple.Map[m.MirroredElemTypes, [f] =>> f <:< Value]]
          // get (name, tag) pairs for all fields
          val elemTags: List[Tag[_]] = summonAll[Tuple.Map[m.MirroredElemTypes, TagOf]]
            .toList.asInstanceOf[List[Tag[_]]]
          val elemFromExpr: List[FromExpr[_]] = summonAll[Tuple.Map[m.MirroredElemTypes, [f] =>> FromExprOf[f]]]
            .toList.asInstanceOf[List[FromExpr[_]]]
          val elemNames: List[String] = constValueTuple[m.MirroredElemLabels].toList.asInstanceOf[List[String]]
          val elements = elemNames.lazyZip(elemFromExpr).lazyZip(elemTags).toList
          GStructSchema[T](elements, None, (tuple, name) => {
            val inst = m.fromTuple.asInstanceOf[Tuple => T].apply(tuple)
            inst._name = name
            inst
          })
        case _ => error("Only case classes are supported as GStructs")
