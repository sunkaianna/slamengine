package slamdata.engine.physical.mongodb

import slamdata.engine.fp._
import slamdata.engine.javascript._

import org.threeten.bp.{Instant, ZoneOffset}
import org.threeten.bp.temporal.{ChronoUnit}

import com.mongodb._
import org.bson.types

import collection.immutable.ListMap
import scalaz._
import Scalaz._

/**
 * A type-safe ADT for Mongo's native data format. Note that this representation
 * is not suitable for efficiently storing large quantities of data.
 */
sealed trait Bson {
  def repr: AnyRef
  def toJs: Js.Expr
}

object Bson {
  def fromRepr(obj: DBObject): Bson = {
    import collection.JavaConversions._

    def loop(v: AnyRef): Bson = v match {
      case null                       => Null
      case x: String                  => Text(x)
      case x: java.lang.Boolean       => Bool(x)
      case x: java.lang.Integer       => Int32(x)
      case x: java.lang.Long          => Int64(x)
      case x: java.lang.Double        => Dec(x)
      case list: BasicDBList          => Arr(list.map(loop).toList)
      case obj: DBObject              => Doc(obj.keySet.toList.map(k => k -> loop(obj.get(k))).toListMap)
      case x: java.util.Date          => Date(Instant.ofEpochMilli(x.getTime))
      case x: types.ObjectId          => ObjectId(x.toByteArray)
      case x: types.Binary            => Binary(x.getData)
      case _: types.MinKey            => MinKey
      case _: types.MaxKey            => MaxKey
      case x: types.Symbol            => Symbol(x.getSymbol)
      case x: types.BSONTimestamp     => Timestamp(Instant.ofEpochSecond(x.getTime), x.getInc)
      case x: java.util.regex.Pattern => Regex(x.pattern)
      case x: Array[Byte]             => Binary(x)
      case x: java.util.UUID          =>
        val bos = new java.io.ByteArrayOutputStream
        val dos = new java.io.DataOutputStream(bos)
        dos.writeLong(x.getLeastSignificantBits)
        dos.writeLong(x.getMostSignificantBits)
        Binary(bos.toByteArray.reverse)

      // NB: the remaining types are not easily translated back to Bson,
      // and we don't expect them to appear anyway.
      // - JavaScript/JavaScriptScope: would require parsing a string to our Js type.
      // - Any other value that might be produced by MongoDB which is unknown to us.

      case _ => NA
    }

    loop(obj)
  }

  case class Dec(value: Double) extends Bson {
    def repr = value: java.lang.Double
    def toJs = Js.Num(value, true)
  }
  case class Text(value: String) extends Bson {
    def repr = value
    def toJs = Js.Str(value)
  }
  case class Binary(value: ImmutableArray[Byte]) extends Bson {
    def repr = value.toArray[Byte]
    def toJs = Js.Str(new sun.misc.BASE64Encoder().encode(value.toArray))

    override def toString = "Binary(Array[Byte](" + value.mkString(", ") + "))"

    override def equals(that: Any): Boolean = that match {
      case Binary(value2) => value === value2
      case _ => false
    }
    override def hashCode = java.util.Arrays.hashCode(value.toArray[Byte])
  }
  object Binary {
    def apply(array: Array[Byte]): Binary = Binary(ImmutableArray.fromArray(array))
  }
  case class Doc(value: ListMap[String, Bson]) extends Bson {
    def repr: DBObject = value.foldLeft(new BasicDBObject) {
      case (obj, (name, value)) =>
        obj.put(name, value.repr)
        obj
    }
    def toJs = Js.AnonObjDecl((value ∘ (_.toJs)).toList)
  }
  case class Arr(value: List[Bson]) extends Bson {
    def repr = value.foldLeft(new BasicDBList) {
      case (array, value) =>
        array.add(value.repr)
        array
    }
    def toJs = Js.AnonElem(value ∘ (_.toJs))
  }
  case class ObjectId(value: ImmutableArray[Byte]) extends Bson {
    def repr = new types.ObjectId(value.toArray[Byte])

    def str = repr.toHexString

    def toJs = Js.Call(Js.Ident("ObjectId"), List(Js.Str(str)))

    override def toString = "ObjectId(" + str + ")"

    override def equals(that: Any): Boolean = that match {
      case ObjectId(value2) => value === value2
      case _ => false
    }
    override def hashCode = java.util.Arrays.hashCode(value.toArray[Byte])
  }
  object ObjectId {
    def apply(array: Array[Byte]): ObjectId = ObjectId(ImmutableArray.fromArray(array))

    def apply(str: String): Option[ObjectId] = {
      \/.fromTryCatchNonFatal(new types.ObjectId(str)).toOption.map(oid => ObjectId(oid.toByteArray))
    }
  }
  case class Bool(value: Boolean) extends Bson {
    def repr = value: java.lang.Boolean
    def toJs = Js.Bool(value)
  }
  case class Date(value: Instant) extends Bson {
    def repr = new java.util.Date(value.toEpochMilli)
    def toJs =
      Js.Call(Js.Ident("ISODate"), List(Js.Num(value.toEpochMilli, false)))
  }
  case object Null extends Bson {
    def repr = null
    override def toJs = Js.Null
  }
  case class Regex(value: String) extends Bson {
    def repr = java.util.regex.Pattern.compile(value)
    def toJs = Js.New(Js.Call(Js.Ident("RegExp"), List(Js.Str(value))))
  }
  case class JavaScript(value: Js.Expr) extends Bson {
    def repr = new types.Code(value.render(2))
    def toJs = value
  }
  case class JavaScriptScope(code: Js.Expr, doc: Doc) extends Bson {
    def repr = new types.CodeWScope(code.render(2), doc.repr)
    // FIXME: this loses scope, but I don’t know what it should look like
    def toJs = code
  }
  case class Symbol(value: String) extends Bson {
    def repr = new types.Symbol(value)
    def toJs = Js.Ident(value)
  }
  case class Int32(value: Int) extends Bson {
    def repr = value: java.lang.Integer
    def toJs = Js.Call(Js.Ident("NumberInt"), List(Js.Num(value, false)))
  }
  case class Int64(value: Long) extends Bson {
    def repr = value: java.lang.Long
    def toJs = Js.Call(Js.Ident("NumberLong"), List(Js.Num(value, false)))
  }
  case class Timestamp private (epochSecond: Int, ordinal: Int) extends Bson {
    def repr = new types.BSONTimestamp(epochSecond, ordinal)
    def toJs = Js.Call(Js.Ident("Timestamp"),
      List(Js.Num(epochSecond, false), Js.Num(ordinal, false)))
    override def toString = "Timestamp(" + Instant.ofEpochSecond(epochSecond) + ", " + ordinal + ")"
  }
  object Timestamp {
    def apply(instant: Instant, ordinal: Int): Timestamp =
      Timestamp((instant.toEpochMilli/1000).toInt, ordinal)
  }
  case object MinKey extends Bson {
    def repr = new types.MinKey
    def toJs = Js.Ident("MinKey")
  }
  case object MaxKey extends Bson {
    def repr = new types.MaxKey
    def toJs = Js.Ident("MaxKey")
  }
  /**
   An object to represent any value that might be produced by MongoDB, but that
   we either don't know about or can't represent in this ADT. We choose a
   JavaScript value to represent it, so it is (semi) isomorphic with respect to
   translation to/from the native types.
   */
  case object NA extends Bson {
    def repr = JavaScript(Js.Undefined).repr
    def toJs = Js.Undefined
  }
}

sealed trait BsonType {
  def ordinal: Int
}

object BsonType {
  private[BsonType] abstract class AbstractType(val ordinal: Int) extends BsonType
  case object Dec extends AbstractType(1)
  case object Text extends AbstractType(2)
  case object Doc extends AbstractType(3)
  case object Arr extends AbstractType(4)
  case object Binary extends AbstractType(5)
  case object ObjectId extends AbstractType(7)
  case object Bool extends AbstractType(8)
  case object Date extends AbstractType(9)
  case object Null extends AbstractType(10)
  case object Regex extends AbstractType(11)
  case object JavaScript extends AbstractType(13)
  case object JavaScriptScope extends AbstractType(15)
  case object Symbol extends AbstractType(14)
  case object Int32 extends AbstractType(16)
  case object Int64 extends AbstractType(18)
  case object Timestamp extends AbstractType(17)
  case object MinKey extends AbstractType(255)
  case object MaxKey extends AbstractType(127)
}

sealed trait BsonField {
  def asText  : String
  def asField : String = "$" + asText
  def asVar   : String = "$$" + asText

  def bson      = Bson.Text(asText)
  def bsonField = Bson.Text(asField)
  def bsonVar   = Bson.Text(asVar)

  import BsonField._

  def \ (that: BsonField): BsonField = (this, that) match {
    case (Path(x), Path(y)) => Path(NonEmptyList.nel(x.head, x.tail ++ y.list))
    case (Path(x), y: Leaf) => Path(NonEmptyList.nel(x.head, x.tail :+ y))
    case (y: Leaf, Path(x)) => Path(NonEmptyList.nel(y, x.list))
    case (x: Leaf, y: Leaf) => Path(NonEmptyList.nels(x, y))
  }

  def \\ (tail: List[BsonField]): BsonField = if (tail.isEmpty) this else this match {
    case Path(p) => Path(NonEmptyList.nel(p.head, p.tail ::: tail.flatMap(_.flatten)))
    case l: Leaf => Path(NonEmptyList.nel(l, tail.flatMap(_.flatten)))
  }

  def flatten: List[Leaf]

  def parent: Option[BsonField] = BsonField(flatten.reverse.drop(1).reverse)

  def startsWith(that: BsonField) = this.flatten.startsWith(that.flatten)

  def toJs: JsMacro =
    this.flatten.foldLeft(JsMacro(identity))((acc, leaf) =>
      leaf match {
        case Name(v)  => JsMacro(arg => JsCore.Access(acc(arg), JsCore.Literal(Js.Str(v)).fix).fix)
        case Index(v) => JsMacro(arg => JsCore.Access(acc(arg), JsCore.Literal(Js.Num(v, false)).fix).fix)
      })

  override def hashCode = this match {
    case Name(v) => v.hashCode
    case Index(v) => v.hashCode
    case Path(v) if (v.tail.length == 0) => v.head.hashCode
    case p @ Path(_) => p.flatten.hashCode
  }

  override def equals(that: Any): Boolean = (this, that) match {
    case (Name(v1),      Name(v2))      => v1 == v2
    case (Name(_),       Index(_))      => false
    case (Index(v1),     Index(v2))     => v1 == v2
    case (Index(_),      Name(_))       => false
    case (v1: BsonField, v2: BsonField) => v1.flatten.equals(v2.flatten)
    case _                              => false
  }
}

object BsonField {
  sealed trait Root
  final case object Root extends Root {
    override def toString = "BsonField.Root"
  }

  def apply(v: List[BsonField.Leaf]): Option[BsonField] = v match {
    case Nil => None
    case head :: Nil => Some(head)
    case head :: tail => Some(Path(NonEmptyList.nel(head, tail)))
  }

  sealed trait Leaf extends BsonField {
    def asText = Path(NonEmptyList(this)).asText

    def flatten: List[Leaf] = this :: Nil

    // Distinction between these is artificial as far as BSON concerned so you
    // can always translate a leaf to a Name (but not an Index since the key might
    // not be numeric).
    def toName: Name = this match {
      case n @ Name(_) => n
      case Index(idx) => Name(idx.toString)
    }
  }

  case class Name(value: String) extends Leaf {
    override def toString = s"""BsonField.Name("$value")"""
  }
  case class Index(value: Int) extends Leaf {
    override def toString = s"BsonField.Index($value)"
  }

  private case class Path(values: NonEmptyList[Leaf]) extends BsonField {
    def flatten: List[Leaf] = values.list

    def asText = (values.list.zipWithIndex.map {
      case (Name(value), 0) => value
      case (Name(value), _) => "." + value
      case (Index(value), 0) => value.toString
      case (Index(value), _) => "." + value.toString
    }).mkString("")

    override def toString = values.list.mkString(" \\ ")
  }

  private lazy val TempNames:   EphemeralStream[BsonField.Name]  = EphemeralStream.iterate(0)(_ + 1).map(i => BsonField.Name("__sd_tmp_" + i.toString))
  private lazy val TempIndices: EphemeralStream[BsonField.Index] = EphemeralStream.iterate(0)(_ + 1).map(i => BsonField.Index(i))

  def genUniqName(v: Iterable[BsonField.Name]): BsonField.Name =
    genUniqNames(1, v).head

  def genUniqNames(n: Int, v: Iterable[BsonField.Name]): List[BsonField.Name] =
    TempNames.filter(n => !v.toSet.contains(n)).take(n).toList

  def genUniqIndex(v: Iterable[BsonField.Index]): BsonField.Index =
    genUniqIndices(1, v).head

  def genUniqIndices(n: Int, v: Iterable[BsonField.Index]):
      List[BsonField.Index] =
    TempIndices.filter(n => !v.toSet.contains(n)).take(n).toList
}
