package scala.slick.lifted

import scala.language.higherKinds
import scala.language.experimental.macros
import scala.annotation.implicitNotFound
import scala.reflect.macros.Context
import scala.slick.ast.{Join => AJoin, _}
import FunctionSymbolExtensionMethods._
import ScalaBaseType._

/** An instance of Query represents a query or view, i.e. a computation of a
  * collection type (Rep[Seq[T]]). It is parameterized with both, the mixed
  * type (the type of values you see e.g. when you call map()) and the unpacked
  * type (the type of values that you get back when you run the query).  */
abstract class Query[+E, U, C[_]] extends Rep[C[U]] { self =>

  def unpackable: ShapedValue[_ <: E, U]
  final lazy val packed = unpackable.toNode

  /** Build a new query by applying a function to all elements of this query
    * and using the elements of the resulting queries. This corresponds to an
    * implicit inner join in SQL. */
  def flatMap[F, T, D[_]](f: E => Query[F, T, D]): Query[F, T, C] = {
    val generator = new AnonSymbol
    val aliased = unpackable.encodeRef(generator :: Nil).value
    val fv = f(aliased)
    new WrappingQuery[F, T, C](new Bind(generator, toNode, fv.toNode), fv.unpackable)
  }

  /** Build a new query by applying a function to all elements of this query. */
  def map[F, G, T](f: E => F)(implicit shape: Shape[_ <: ShapeLevel.Flat, F, T, G]): Query[G, T, C] =
    flatMap(v => Query.pure[F, T, G](f(v)))

  @deprecated("Use flatMap instead", "2.0")
  def >>[F, T, D[_]](q: Query[F, T, D]): Query[F, T, C] = flatMap(_ => q)

  /** Select all elements of this query which satisfy a predicate. */
  def filter[T](f: E => T)(implicit wt: CanBeQueryCondition[T]): Query[E, U, C] = {
    val generator = new AnonSymbol
    val aliased = unpackable.encodeRef(generator :: Nil)
    val fv = f(aliased.value)
    new WrappingQuery[E, U, C](Filter.ifRefutable(generator, toNode, wt(fv).toNode), unpackable)
  }

  /** Select all elements of this query which satisfy a predicate. This method
    * is used when desugaring for-comprehensions over queries. There is no
    * reason to call it directly because it is the same as `filter`. */
  def withFilter[T : CanBeQueryCondition](f: E => T) = filter(f)

  /** Select all elements of this query which satisfy a predicate. Unlike
    * `filter`, this method only allows `Column`-valued predicates, so it
    * guards against the accidental use use plain Booleans. */
  def where[T <: Column[_] : CanBeQueryCondition](f: E => T) = filter(f)

  /** Join two collections.
    * An optional join predicate can be specified later by calling `on`. */
  def join[E2, U2, D[_]](q2: Query[E2, U2, D], jt: JoinType = JoinType.Inner) = {
    val leftGen, rightGen = new AnonSymbol
    val aliased1 = unpackable.encodeRef(leftGen :: Nil)
    val aliased2 = q2.unpackable.encodeRef(rightGen :: Nil)
    new BaseJoinQuery[E, E2, U, U2, C](leftGen, rightGen, toNode, q2.toNode, jt, aliased1.zip(aliased2))
  }
  /** Join two collections with an inner join.
    * An optional join predicate can be specified later by calling `on`. */
  def innerJoin[E2, U2, D[_]](q2: Query[E2, U2, D]) = join(q2, JoinType.Inner)
  /** Join two collections with a left outer join.
    * An optional join predicate can be specified later by calling `on`. */
  def leftJoin[E2, U2, D[_]](q2: Query[E2, U2, D]) = join(q2, JoinType.Left)
  /** Join two collections with a right outer join.
    * An optional join predicate can be specified later by calling `on`. */
  def rightJoin[E2, U2, D[_]](q2: Query[E2, U2, D]) = join(q2, JoinType.Right)
  /** Join two collections with a full outer join.
    * An optional join predicate can be specified later by calling `on`. */
  def outerJoin[E2, U2, D[_]](q2: Query[E2, U2, D]) = join(q2, JoinType.Outer)
  /** Return a query formed from this query and another query by combining
    * corresponding elements in pairs. */
  def zip[E2, U2, D[_]](q2: Query[E2, U2, D]): Query[(E, E2), (U, U2), C] = join(q2, JoinType.Zip)
  /** Return a query formed from this query and another query by combining
    * corresponding elements with the specified function. */
  def zipWith[E2, U2, F, G, T, D[_]](q2: Query[E2, U2, D], f: (E, E2) => F)(implicit shape: Shape[_ <: ShapeLevel.Flat, F, T, G]): Query[G, T, C] =
    join(q2, JoinType.Zip).map[F, G, T](x => f(x._1, x._2))
  /** Zip this query with its indices (starting at 0). */
  def zipWithIndex = {
    val leftGen, rightGen = new AnonSymbol
    val aliased1 = unpackable.encodeRef(leftGen :: Nil)
    val aliased2 = ShapedValue(Column.forNode[Long](Ref(rightGen)), Shape.columnShape[Long, ShapeLevel.Flat])
    new BaseJoinQuery[E, Column[Long], U, Long, C](leftGen, rightGen, toNode, RangeFrom(0L), JoinType.Zip, aliased1.zip(aliased2))
  }

  /** Sort this query according to a function which extracts the ordering
    * criteria from the query's elements. */
  def sortBy[T <% Ordered](f: E => T): Query[E, U, C] = {
    val generator = new AnonSymbol
    val aliased = unpackable.encodeRef(generator :: Nil)
    new WrappingQuery[E, U, C](SortBy(generator, toNode, f(aliased.value).columns), unpackable)
  }

  /** Sort this query according to a the ordering of its elements. */
  def sorted(implicit ev: (E => Ordered)): Query[E, U, C] = sortBy(identity)

  /** Partition this query into a query of pairs of a key and a nested query
    * containing the elements for the key, according to some discriminator
    * function. */
  def groupBy[K, T, G, P](f: E => K)(implicit kshape: Shape[_ <: ShapeLevel.Flat, K, T, G], vshape: Shape[_ <: ShapeLevel.Flat, E, _, P]): Query[(G, Query[P, U, Seq]), (T, Query[P, U, Seq]), C] = {
    val sym = new AnonSymbol
    val key = ShapedValue(f(unpackable.encodeRef(sym :: Nil).value), kshape).packedValue
    val value = ShapedValue(pack.as[Seq], Shape.repShape.asInstanceOf[Shape[ShapeLevel.Flat, Query[P, U, Seq], Query[P, U, Seq], Query[P, U, Seq]]])
    val group = GroupBy(sym, toNode, key.toNode)
    new WrappingQuery[(G, Query[P, U, Seq]), (T, Query[P, U, Seq]), C](group, key.zip(value))
  }

  def encodeRef(path: List[Symbol]): Query[E, U, C] = new Query[E, U, C] {
    val unpackable = self.unpackable.encodeRef(path)
    lazy val toNode = Path(path)
  }

  /** Return a new query containing the elements from both operands. Duplicate
    * elements are eliminated from the result. */
  def union[O >: E, R, D[_]](other: Query[O, U, D]) =
    new WrappingQuery[O, U, C](Union(toNode, other.toNode, false), unpackable)

  /** Return a new query containing the elements from both operands. Duplicate
    * elements are preserved. */
  def unionAll[O >: E, R, D[_]](other: Query[O, U, D]) =
    new WrappingQuery[O, U, C](Union(toNode, other.toNode, true), unpackable)

  /** Return a new query containing the elements from both operands. Duplicate
    * elements are preserved. */
  def ++[O >: E, R, D[_]](other: Query[O, U, D]) = unionAll(other)

  /** The total number of elements of the query. */
  def length: Column[Int] = Library.CountAll.column(toNode)

  /** The number of distinct elements of the query. */
  def countDistinct: Column[Int] = Library.CountDistinct.column(toNode)

  /** Test whether this query is non-empty. */
  def exists = Library.Exists.column[Boolean](toNode)

  def pack[R](implicit packing: Shape[_ <: ShapeLevel.Flat, E, _, R]): Query[R, U, C] =
    new Query[R, U, C] {
      val unpackable: ShapedValue[_ <: R, U] = self.unpackable.packedValue(packing)
      def toNode = self.toNode
    }

  /** Select the first `num` elements. */
  def take(num: Int): Query[E, U, C] = new WrappingQuery[E, U, C](Take(toNode, num), unpackable)
  /** Select all elements except the first `num` ones. */
  def drop(num: Int): Query[E, U, C] = new WrappingQuery[E, U, C](Drop(toNode, num), unpackable)

  def as[D[_]]: Query[E, U, D] = new Query[E, U, D] {
    val unpackable = self.unpackable
    def toNode = self.toNode
  }
}

/** The companion object for Query contains factory methods for creating
  * queries and also acts as an empty Query. */
object Query extends Query[Unit, Unit, Seq] {
  def toNode = packed
  def unpackable = ShapedValue((), Shape.unitShape[ShapeLevel.Flat])

  /** Lift a scalar value to a Query. */
  def apply[E, U, R](value: E)(implicit unpack: Shape[_ <: ShapeLevel.Flat, E, U, R]): Query[R, U, Seq] = {
    val unpackable = ShapedValue(value, unpack).packedValue
    if(unpackable.toNode.isInstanceOf[TableExpansion])
      new NonWrappingQuery[R, U, Seq](unpackable.toNode, unpackable)
    else new WrappingQuery[R, U, Seq](Pure(unpackable.toNode), unpackable)
  }

  def pure[E, U, R](value: E)(implicit unpack: Shape[_ <: ShapeLevel.Flat, E, U, R]): Query[R, U, Seq] = {
    val unpackable = ShapedValue(value, unpack).packedValue
    new WrappingQuery[R, U, Seq](Pure(unpackable.toNode), unpackable)
  }
}

/** A typeclass for types that can be used as predicates in `filter` calls. */
@implicitNotFound("Type ${T} cannot be a query condition (only Boolean, Column[Boolean] and Column[Option[Boolean]] are allowed")
trait CanBeQueryCondition[-T] extends (T => Column[_])

object CanBeQueryCondition {
  implicit object BooleanColumnCanBeQueryCondition extends CanBeQueryCondition[Column[Boolean]] {
    def apply(value: Column[Boolean]) = value
  }
  implicit object BooleanOptionColumnCanBeQueryCondition extends CanBeQueryCondition[Column[Option[Boolean]]] {
    def apply(value: Column[Option[Boolean]]) = value
  }
  implicit object BooleanCanBeQueryCondition extends CanBeQueryCondition[Boolean] {
    def apply(value: Boolean) = new LiteralColumn(value)
  }
}

class WrappingQuery[+E, U, C[_]](val toNode: Node, val base: ShapedValue[_ <: E, U]) extends Query[E, U, C] {
  lazy val unpackable = base.encodeRef(toNode.nodeIntrinsicSymbol :: Nil)
}

class NonWrappingQuery[+E, U, C[_]](val toNode: Node, val unpackable: ShapedValue[_ <: E, U]) extends Query[E, U, C]

final class BaseJoinQuery[+E1, +E2, U1, U2, C[_]](leftGen: Symbol, rightGen: Symbol, left: Node, right: Node, jt: JoinType, base: ShapedValue[_ <: (E1, E2), (U1, U2)])
    extends WrappingQuery[(E1, E2), (U1,  U2), C](AJoin(leftGen, rightGen, left, right, jt, LiteralNode(true)), base) {
  /** Add a join condition to a join operation. */
  def on[T <: Column[_]](pred: (E1, E2) => T)(implicit wt: CanBeQueryCondition[T]) =
    new WrappingQuery[(E1, E2), (U1, U2), C](AJoin(leftGen, rightGen, left, right, jt, wt(pred(base.value._1, base.value._2)).toNode), base)
}

/** Represents a database table. Profiles add extension methods to TableQuery
  * for operations that can be performed on tables but not on arbitrary
  * queries, e.g. getting the table DDL. */
final class TableQuery[+E <: AbstractTable[_], U](shaped: ShapedValue[_ <: E, U])
  extends NonWrappingQuery[E, U, Seq](shaped.toNode, shaped) {

  /** Get the "raw" table row that represents the table itself, as opposed to
    * a Path for a variable of the table's type. This method should generally
    * not be called from user code. */
  def baseTableRow: E = unpackable.value
}

object TableQuery {
  /** Create a TableQuery for a table row class using an arbitrary constructor function. */
  def apply[E <: AbstractTable[_]](cons: Tag => E): TableQuery[E, E#TableElementType] = {
    val baseTable = cons(new BaseTag { base =>
      def taggedAs(path: List[Symbol]): AbstractTable[_] = cons(new RefTag(path) {
        def taggedAs(path: List[Symbol]) = base.taggedAs(path)
      })
    })
    new TableQuery[E, E#TableElementType](ShapedValue(baseTable, Shape.repShape.asInstanceOf[Shape[ShapeLevel.Flat, E, E#TableElementType, E]]))
  }

  /** Create a TableQuery for a table row class which has a constructor of type (Tag). */
  def apply[E <: AbstractTable[_]]: TableQuery[E, E#TableElementType] =
    macro TableQueryMacroImpl.apply[E]
}

object TableQueryMacroImpl {

  def apply[E <: AbstractTable[_]](c: Context)(implicit e: c.WeakTypeTag[E]): c.Expr[TableQuery[E, E#TableElementType]] = {
    import c.universe._
    val cons = c.Expr[Tag => E](Function(
      List(ValDef(Modifiers(Flag.PARAM), newTermName("tag"), Ident(typeOf[Tag].typeSymbol), EmptyTree)),
      Apply(
        Select(New(TypeTree(e.tpe)), nme.CONSTRUCTOR),
        List(Ident(newTermName("tag")))
      )
    ))
    reify { TableQuery.apply[E](cons.splice) }
  }
}
