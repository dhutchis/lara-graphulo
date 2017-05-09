package edu.washington.cs.laragraphulo.api

import com.google.common.collect.Iterators
import com.google.common.collect.PeekingIterator
import edu.washington.cs.laragraphulo.Loggable
import edu.washington.cs.laragraphulo.logger
import edu.washington.cs.laragraphulo.warn
import org.apache.accumulo.core.client.lexicoder.Lexicoder
import org.apache.accumulo.core.client.lexicoder.impl.AbstractLexicoder
import org.slf4j.Logger
import java.util.*
import kotlin.NoSuchElementException
import kotlin.collections.ArrayList

typealias Table = String
typealias Name = String


// ======================= HELPER FUNCTIONS

fun <E> Collection<E>.disjoint(other: Collection<E>): Boolean {
  return this.none { other.contains(it) }
}

/**
 * Return a NameTuple with the same keys but the values set to the default values.
 */
fun NameTuple.copyDefault(ns: Schema): NameTuple {
  require(this.keys == (ns.keys + ns.vals).toSet())
  return this.mapValues { (attr, value) ->
    ns.getValue(attr)?.default ?: value
  }
}


// ======================= ATTRIBUTES
const val ZERO_BYTE: Byte = 0
val SINGLE_ZERO = byteArrayOf(ZERO_BYTE) // sort null values first

/** Would this come in handy? Uses an extra byte to flag null values. Probably not. */
class NullLexicoder<T>(
    private val lexicoder: Lexicoder<T>
) : AbstractLexicoder<T>() {
  override fun encode(v: T): ByteArray {
    return if (v == null) {
      SINGLE_ZERO
    } else {
      val e = lexicoder.encode(v)
      val r = ByteArray(e.size+1)
      r[0] = 1
      System.arraycopy(e,0,r,1,e.size)
      r
    }
  }

  override fun decodeUnchecked(b: ByteArray, offset: Int, len: Int): T? {
    return if (b.size == 1 && b[0] == ZERO_BYTE) null
    else decodeUnchecked(b, 1, b.size-1)
  }
}


interface Attribute<T> : Comparable<Attribute<T>> {
  val name: Name
  val type: LType<T>
  fun withNewName(name: Name) = Attribute(name, type)
  operator fun component1() = name
  operator fun component2() = type

  companion object {
    operator fun <T> invoke(name: Name, type: LType<T>): Attribute<T> = AttributeImpl(name, type)
  }

  open class AttributeImpl<T>(
      override val name: Name,
      override val type: LType<T>
  ) : Attribute<T> {

    override fun toString(): String {
      return "Attribute(name='$name', type=$type)"
    }

    override fun equals(other: Any?): Boolean {
      if (this === other) return true
      if (other?.javaClass != javaClass) return false

      other as Attribute<*>

      if (name != other.name) return false
      if (type != other.type) return false

      return true
    }

    override fun hashCode(): Int {
      var result = name.hashCode()
      result = 31 * result + type.hashCode()
      return result
    }

    /** Careful: this returns 0 on objects that are not equal */
    override fun compareTo(other: Attribute<T>): Int = name.compareTo(other.name)
  }
}

interface ValAttribute<T> : Attribute<T> {
  val default: T
  override fun withNewName(name: Name) = ValAttribute(name, type, default)
  operator fun component3() = default

  companion object {
    operator fun <T> invoke(name: Name, type: LType<T>, default: T): ValAttribute<T> = ValAttributeImpl(name, type, default)
  }

  open class ValAttributeImpl<T>(
      name: Name,
      type: LType<T>,
      override val default: T
  ) : Attribute.AttributeImpl<T>(name, type), ValAttribute<T> {

    override fun toString(): String {
      return "ValAttribute(name='$name', type=$type, default=$default)"
    }

    override fun equals(other: Any?): Boolean {
      if (this === other) return true
      if (other?.javaClass != javaClass) return false
      if (!super.equals(other)) return false

      other as ValAttribute<*>

      if (default != other.default) return false

      return true
    }

    override fun hashCode(): Int {
      var result = super.hashCode()
      result = 31 * result + (default?.hashCode() ?: 0)
      return result
    }
  }
}


// ======================= SCHEMA

open class Schema(
  val keys: List<Attribute<*>>,
  val vals: List<ValAttribute<*>>
) {
  init {
    val kns = keys.map(Attribute<*>::name)
    val vns = vals.map(ValAttribute<*>::name)
    require(kns.let { it.size == it.toSet().size }) {"there is a duplicate key attribute name: $keys"}
    require(vns.let { it.size == it.toSet().size }) {"there is a duplicate value attribute name: $vals"}
    require(kns.disjoint(vns)) { "keys and vals overlap: $keys, $vals" }
  }

  open operator fun get(n: Name): Attribute<*>? =
      keys.find { it.name == n } ?: vals.find { it.name == n }

  open fun getValue(n: Name): ValAttribute<*>? =
      vals.find { it.name == n }

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other?.javaClass != javaClass) return false

    other as Schema

    if (keys != other.keys) return false
    if (vals != other.vals) return false

    return true
  }

  override fun hashCode(): Int {
    var result = keys.hashCode()
    result = 31 * result + vals.hashCode()
    return result
  }

  open operator fun component1() = keys
  open operator fun component2() = vals

  override fun toString(): String {
    return "Schema(keys=$keys, vals=$vals)"
  }

}

// ======================= TUPLE

typealias NameTuple = Map<Name,*>



// ======================= UDFs

open class ExtFun(
    /** The (to be appended) new key attributes and value attributes that the extFun produces */
    val extSchema: Schema,
    val extFun: (NameTuple) -> List<NameTuple>
) {
  override fun toString(): String {
    return "ExtFun(extSchema=$extSchema, extFun=$extFun)"
  }
}

/**
 * Must return default values when passed default values, for any key.
 */
class MapFun(
    /** The value attributes that the mapFun produces */
    val mapValues: List<ValAttribute<*>>,
    val mapFun: (NameTuple) -> NameTuple
) : ExtFun(extSchema = Schema(listOf(), mapValues),
               extFun = { tuple -> listOf(mapFun(tuple)) }) {
  override fun toString(): String {
    return "MapFun(mapValues=$mapValues, mapFun=$mapFun)"
  }
}



data class PlusFun<T>(
    val identity: T,
    val plus: (T, T) -> T
) {
  fun verifyIdentity(a: T = identity) {
    check(plus(a,identity) == a && plus(identity,a) == a) {"Value $a violates the identity requirement of plus for identity $identity"}
  }

  companion object {
    /** Wraps a function to have an identity. */
    inline fun <T> withIdentity(id: T, crossinline plusFun: (T,T) -> T) = PlusFun(id) { a, b ->
      when {
        a == id -> b
        b == id -> a
        else -> plusFun(a,b)
      }
    }

    /** Wraps a function to have identity null (that is zero-sum-free). */
    inline fun <T : Any> withNullIdentity(crossinline plusFun: (T, T) -> T): PlusFun<T?> {
      return PlusFun<T?>(null) { a, b ->
        when {
          a == null -> b
          b == null -> a
          else -> plusFun(a,b)
        }
      }
    }

    /** Use this when you know that summation will never occur. Throws an error when summing two non-identities. */
    fun <T> plusErrorFun(id: T) = PlusFun(id) { a, b ->
      when {
        a == id -> b
        b == id -> a
        else -> throw IllegalStateException("no plus function defined for this attribute, yet non-identity ($id) values $a and $b are to be added")
      }
    }
  }
}



data class TimesFun<T1,T2,T3>(
    val leftAnnihilator: T1,
    val rightAnnihilator: T2,
    val resultType: LType<T3>, // (PType<T1>, PType<T2>) -> PType<T3>
    val times: (T1, T2) -> T3
) {
  val resultZero: T3 = times(leftAnnihilator, rightAnnihilator)
  fun verifyAnnihilator(a: T1 = leftAnnihilator, b: T2 = rightAnnihilator) {
    check(times(a,rightAnnihilator) == resultZero && times(leftAnnihilator,b) == resultZero)
    { "Value $a and $b violate the annihilator requirement of times for annihilators $leftAnnihilator and $rightAnnihilator" }
  }

  companion object {
    /** Wraps a function to have these annihilators. */
    inline fun <T1, T2, T3> withAnnihilators(
        leftAnnihilator: T1, rightAnnihilator: T2,
        resultType: LType<T3>,
        crossinline timesFun: (T1, T2) -> T3
    ): TimesFun<T1, T2, T3> {
      val resultZero = timesFun(leftAnnihilator, rightAnnihilator)
      return TimesFun(leftAnnihilator, rightAnnihilator, resultType) { a, b ->
        if (a == leftAnnihilator || b == rightAnnihilator) resultZero else timesFun(a, b)
      }
    }

    /** Wraps a function to have null annihilators (with zero product property). */
    inline fun <T1, T2, T3> withNullAnnihilators(
        resultType: LType<T3?>,
        crossinline timesFun: (T1, T2) -> T3
    ): TimesFun<T1?, T2?, T3?> = TimesFun<T1?, T2?, T3?>(null, null, resultType) { a, b ->
      if (a == null || b == null) null else timesFun(a, b)
    }
  }
}

// use a map from Java class to most common PType for that class


// ======================= OPERATORS


sealed class TupleOp(

) {
  abstract val resultSchema: Schema
  abstract fun run(): Iterator<NameTuple>
  /** Transform this TupleOp stack. The [TupleOp] passed to [f] is after its parents are transformed. */
  abstract fun transform(f: (TupleOp) -> TupleOp): TupleOp
  /** Visit each op and run a function on it without altering it */
  fun visit(f: (TupleOp) -> Unit) = transform { f(it); it }
  /** Do a structural fold over this TupleOp stack. [combine] should be **commutative**. */
  inline fun <T> fold(init: T, crossinline combine: (T, T) -> T, crossinline f: (TupleOp) -> T): T {
    var t: T = init
    visit { t = combine(t, f(it)) }
    return t
  }

  fun ext(extFun: ExtFun): TupleOp = Ext(this, extFun)
  data class Ext(
      val parent: TupleOp,
      /** This can also be a [MapFun] */
      val extFun: ExtFun
  ): TupleOp() {
    override val resultSchema = Schema(
        keys = parent.resultSchema.keys + extFun.extSchema.keys,
        vals = extFun.extSchema.vals
    )
    override fun transform(f: (TupleOp) -> TupleOp) = f(parent.transform(f)).let { if (it == parent) this else copy(it) }

    /*    companion object {
//      fun runExtFunctionOnDefaultValues(ps: Schema, f: ExtFun): List<ValAttribute<*>> {
//        val tuple = (ps.keys.map { it.name to it.type.examples.first() } +
//            ps.vals.map { it.name to it.default }).toMap()
//        val result = f.extFun(tuple)
//        if (result.isEmpty()) {
//          require()
//        }
//        f.extVals.map { va ->
//          require(va.name in result)
//        }
//      }
//    } */
    val parentKeyNames = parent.resultSchema.keys.map { it.name }

    override fun run(): Iterator<NameTuple> {
      return ExtIterator()
    }

    inner class ExtIterator : Iterator<NameTuple> {
      val iter = parent.run()
      var top = findTop()


      fun findTop(): Iterator<NameTuple> {
        if (!iter.hasNext())
          return Collections.emptyIterator()
        var topIter: Iterator<NameTuple>
        var topParent: NameTuple
        do {
          topParent = iter.next()
          topIter = extFun.extFun(topParent).iterator()
        } while (iter.hasNext() && !topIter.hasNext())
        return PrependKeysIteraor(parentKeyNames, topParent, topIter)
      }

      override fun hasNext(): Boolean = top.hasNext()
      override fun next(): NameTuple {
        val r = top.next()
        if (!top.hasNext()) top = findTop()
        return r
      }

    }

    class PrependKeysIteraor(
        keysToPrepend: List<String>,
        parent: NameTuple,
        val iter: Iterator<NameTuple>
    ) : Iterator<NameTuple> {
      val parentKeys = parent.filterKeys { it in keysToPrepend }
      override fun hasNext(): Boolean = iter.hasNext()
      override fun next(): NameTuple {
        val n = iter.next().filterKeys { it !in parentKeys }
//        check(parentKeys.keys.all { it !in n }) {"the tuple resulting from this ext emitted a key that is present in the parent keys. Tuple: $n. ParentKeys: $parentKeys"}
        return parentKeys + n
      }
    }
  }

  data class Load(
      val table: Table,
      override val resultSchema: Schema
  ): TupleOp() {
//    constructor(table: String, schema: Schema, iter: Iterator<NameTuple>): this(table, schema, Collections.emptyIterator())
    override fun run(): Iterator<NameTuple> = throw UnsupportedOperationException("Cannot run a Load() Op; need to provide a data source for this: $this")
    override fun transform(f: (TupleOp) -> TupleOp) = f(this)
  }

  data class Empty(
      override val resultSchema: Schema
  ) : TupleOp() {
    override fun run(): Iterator<NameTuple> = Collections.emptyIterator()
    override fun transform(f: (TupleOp) -> TupleOp) = f(this)
  }


  /**
   * Restricted to two parents. Future work could extend this to any number of parents.
   */
  sealed class MergeUnion0(
      val p1: TupleOp,
      val p2: TupleOp,
      plusFuns0: Map<Name, PlusFun<*>>
  ): TupleOp() {
    override final val resultSchema = Schema(
        keys = intersectKeys(p1.resultSchema.keys,p2.resultSchema.keys),
        vals = unionValues(p1.resultSchema.vals,p2.resultSchema.vals)
    )

    init {
      require(resultSchema.vals.map(ValAttribute<*>::name).containsAll(plusFuns0.keys)) {"plus functions provided for values that do not exist"}
      plusFuns0.forEach { name, pf ->
        val d = resultSchema.vals.find { it.name == name }!!.default
        pf.verifyIdentity()
        require(pf.identity == d) {"plus function for $name does not match identity of parent: $d"}
      }
    }

    val plusFuns: Map<Name, PlusFun<*>> = resultSchema.vals.map { va ->
      val pf = plusFuns0[va.name] ?: PlusFun.plusErrorFun(va.default)
      va.name to pf
    }.toMap()

    override fun toString(): String {
      return "MergeUnion(p1=$p1, p2=$p2, plusFuns=$plusFuns)"
    }

    override fun equals(other: Any?): Boolean {
      if (this === other) return true
      if (other?.javaClass != javaClass) return false

      other as MergeUnion0

      if (p1 != other.p1) return false
      if (p2 != other.p2) return false
      if (plusFuns != other.plusFuns) return false

      return true
    }

    override fun hashCode(): Int {
      var result = p1.hashCode()
      result = 31 * result + p2.hashCode()
      result = 31 * result + plusFuns.hashCode()
      return result
    }

    companion object {
      /**
       * If A has access path (c,a) and B has access path (c,b),
       * then MergeUnion(A,B) has access path (c).
       */
      private fun intersectKeys(a: List<Attribute<*>>, b: List<Attribute<*>>): List<Attribute<*>> {
        var i = 0
        val minSize = Math.min(a.size,b.size)
        val c: MutableList<Attribute<*>> = ArrayList(minSize)
        while (i < minSize && a[i].name == b[i].name) {
          require(a[i] == b[i]) {"MergeUnion: matching keys ${a[i].name} has different types in parents: ${a[i].type} and ${b[i].type}"}
          c += a[i]
          i++
        }
        // make sure no more keys match
        require((a.subList(i,a.size) + b.subList(i,b.size)).map(Attribute<*>::name).let { it.size == it.toSet().size })
          {"MergeUnion: key attributes $a and $b have matching keys that are not in their common prefix"}
        return c
      }
      /**
       * Union maps by key. Check that entries with the same key have the same value.
       */
      private fun unionValues(a: List<ValAttribute<*>>, b: List<ValAttribute<*>>): List<ValAttribute<*>> {
        return a + b.filter { bv ->
          val av = a.find { it.name == bv.name }
          if (av != null) {
            require(av == bv) // calls equals() method
            {"MergeUnion: value attributes $a and $b have an attribute with the same name but different types"}
            false
          } else true
        }
      }
    }

    override fun run(): Iterator<NameTuple> {
      return MergeUnionIterator(resultSchema.keys, Iterators.peekingIterator(p1.run()),
          Iterators.peekingIterator(p2.run()), plusFuns)
    }

    class MergeUnionIterator(
        val keys: List<Attribute<*>>,
        val i1: PeekingIterator<NameTuple>,
        val i2: PeekingIterator<NameTuple>,
        val plusFuns: Map<Name, PlusFun<*>>
    ) : Iterator<NameTuple> {
      val comparator = KeyComparator(keys)
      val keysAndValues = keys.map { it.name } + plusFuns.keys
      val keyNames = keys.map { it.name }
      var old: NameTuple = keys.map { it.name to it.type.examples.first() }.toMap()

      override fun hasNext(): Boolean {
        return i1.hasNext() || i2.hasNext()
      }

      fun getCompare(): Int = when {
        i1.hasNext() && i2.hasNext() -> comparator.compare(i1.peek(), i2.peek())
        i1.hasNext() -> -1
        i2.hasNext() -> 1
        else -> throw NoSuchElementException()
      }.let { Integer.signum(it) }

      override fun next(): NameTuple {
        var c = getCompare()
        val old = if (c == 1) i2.peek() else i1.peek()
        var cur = old

        // first iteration: set result to the values from i1 or i2
        var result = when (c) {
          -1 -> putDefault(i1.next())
          1 -> putDefault(i2.next())
          else -> addValues(i1.next(), i2.next())
        }

        if (hasNext()) {
          c = getCompare()
          cur = if (c == 1) i2.peek() else i1.peek()

          while (comparator.compare(old, cur) == 0) {
            // add the current matching values into the result
            result = addValues(result,
                when (c) {
                  -1 -> putDefault(i1.next())
                  1 -> putDefault(i2.next())
                  else -> addValues(i1.next(), i2.next())
                })
            if (!hasNext()) break
            c = getCompare()
            cur = if (c == 1) i2.peek() else i1.peek()
          }
        }
        return result + old.filterKeys { it in keyNames }
      }

      private fun putDefault(t: NameTuple): NameTuple {
        return plusFuns.mapValues { (name,f) ->
          if (name in t) t[name]!!
          else f.identity
        }
      }

      private fun addValues(t1: NameTuple, t2: NameTuple): NameTuple {
        return plusFuns.mapValues { (name,f) ->
          @Suppress("UNCHECKED_CAST")
          when {
            name in t1 && name in t2 -> (f.plus as (Any?,Any?) -> Any?)(t1[name], t2[name])
            name in t1 -> t1[name]
            name in t2 -> t2[name]
            else -> f.identity
          }
        }
      }
    }


    class MergeUnion(
        p1: TupleOp,
        p2: TupleOp,
        plusFuns0: Map<Name, PlusFun<*>>
    ) : MergeUnion0(p1,p2,plusFuns0) {
      override fun transform(f: (TupleOp) -> TupleOp): MergeUnion {
        val np1 = f(p1.transform(f))
        val np2 = f(p2.transform(f))
        return if (np1 == p1 && np2 == p2) this else MergeUnion(np1, np2, plusFuns)
      }
    }

    class MergeAgg(
        p: TupleOp,
        val keysKept: Collection<Name>,
        plusFuns0: Map<Name, PlusFun<*>>
    ) : MergeUnion0(p,
        p2 = Empty(Schema(p.resultSchema.keys.filter { it.name in keysKept }, listOf())),
        plusFuns0 = plusFuns0) {
      override fun transform(f: (TupleOp) -> TupleOp) = f(p1.transform(f)).let { if (it == p1) this else MergeAgg(it, keysKept, plusFuns) }
      override fun toString(): String {
        return "MergeAgg(p=$p1, keysKept=$keysKept, plusFuns=$plusFuns)"
      }
    }
  }
  fun union(p2: TupleOp, plusFuns0: Map<Name, PlusFun<*>>) = when (p2) {
    is Empty -> MergeUnion0.MergeAgg(this, p2.resultSchema.keys.map { it.name }, plusFuns0) // optimization when unioning with empty table
    else -> MergeUnion0.MergeUnion(this, p2, plusFuns0)
  }
  fun agg(keysKept: Collection<Name>, plusFuns0: Map<Name, PlusFun<*>>) = MergeUnion0.MergeAgg(this, keysKept, plusFuns0)

  fun rename(renameMap: Map<Name,Name>): TupleOp = Rename(this, renameMap)
  data class Rename(
      val p: TupleOp,
      val renameMap: Map<Name,Name>
  ) : TupleOp() {
    override val resultSchema = p.resultSchema.let { Schema(
        it.keys.map { attr -> renameMap[attr.name]?.let { attr.withNewName(it) } ?: attr },
        it.vals.map { attr -> renameMap[attr.name]?.let { attr.withNewName(it) } ?: attr }
    ) }
    override fun transform(f: (TupleOp) -> TupleOp) = f(p.transform(f)).let { if (it == p) this else copy(it) }

    override fun run(): Iterator<NameTuple> {
      val iter = p.run()
      return object : AbstractIterator<NameTuple>() {
        override fun computeNext() {
          if (!iter.hasNext()) {
            done()
          } else {
            val n = iter.next().mapKeys { (k,_) ->
              if (k in renameMap) renameMap[k]!! else k
            }
            setNext(n)
          }
        }
      }

    }
  }

  fun sort(newSort: List<Name>): TupleOp = Sort(this, newSort)
//  fun sort(vararg newSort: Name): TupleOp = Sort(this, newSort.toList())
  data class Sort(
    val p: TupleOp,
    val newSort: List<Name>
  ) : TupleOp() {
  override val resultSchema = Schema(
      newSort.apply { require(this.toSet() == p.resultSchema.keys.map { it.name }.toSet()) {"not all names re-sorted: $newSort on ${p.resultSchema}"} }
          .map { name -> p.resultSchema.keys.find{it.name == name}!! },
      p.resultSchema.vals
  )
  override fun transform(f: (TupleOp) -> TupleOp) = f(p.transform(f)).let { if (it == p) this else copy(it) }

  override fun run(): Iterator<NameTuple> {
      val l: MutableList<NameTuple> = ArrayList()
      p.run().forEach { l += it }
      l.sortWith(KeyComparator(resultSchema.keys))
      return l.iterator()
    }
  }


  class KeyComparator(
      val keys: List<Attribute<*>>
  ) : Comparator<NameTuple> {
    override fun compare(p1: NameTuple, p2: NameTuple): Int {
      var c: Int = 0
      for (ka in keys) {
        @Suppress("UNCHECKED_CAST")
        c = (ka.type as Comparator<Any?>).compare(p1[ka.name], p2[ka.name])
        if (c != 0) return c
      }
      return c
    }
  }

  fun join(p2: TupleOp, timesFuns: Map<Name,TimesFun<*,*,*>>): TupleOp = MergeJoin(this, p2, timesFuns)
  data class MergeJoin(
      val p1: TupleOp,
      val p2: TupleOp,
      val timesFuns: Map<Name,TimesFun<*,*,*>>
  ): TupleOp() {
    override val resultSchema = Schema(
        keys = unionKeys(p1.resultSchema.keys,p2.resultSchema.keys),
        vals = intersectValues(p1.resultSchema.vals,p2.resultSchema.vals, timesFuns)
    )
    override fun transform(f: (TupleOp) -> TupleOp): MergeJoin {
      val np1 = f(p1.transform(f))
      val np2 = f(p2.transform(f))
      return if (np1 == p1 && np2 == p2) this else copy(np1, np2)
    }

    companion object {

      // similar to unionValues() in MergeUnion
      private fun unionKeys(a: List<Attribute<*>>, b: List<Attribute<*>>): List<Attribute<*>> {
        val commonIdxs = ArrayList<Int>(Math.min(a.size,b.size))
        val r = a + b.filter { bv ->
          val avidx = a.indexOfFirst { it.name == bv.name }
          if (avidx != -1) {
            require(a[avidx] == bv) // calls equals() method
            {"MergeJoin: key attributes $a and $b have an attribute with the same name but different types"}
            commonIdxs.add(avidx)
            false
          } else true
        }
        commonIdxs.sort()
        val x = Array(commonIdxs.size) {it}.toList()
        require(commonIdxs == x) {"some common key attributes of this MergeJoin are not in the prefix: $commonIdxs, $x, $a, $b"}
        return r
      }

      private fun intersectValues(a: List<ValAttribute<*>>, b: List<ValAttribute<*>>,
                                  timesFuns: Map<Name, TimesFun<*, *, *>>): List<ValAttribute<*>> {
        val res = a.filter { attr -> b.any { it.name == attr.name } }
            .map { attr ->
              require(attr.name in timesFuns) {"no times operator for matching value attributes $attr"}
              val battr = b.find { it.name == attr.name }!!
              val times: TimesFun<*, *, *> = timesFuns[attr.name]!!
              require(attr.default == times.leftAnnihilator)
              {"for attribute ${attr.name}, left default value ${attr.default} != times fun left annihilator ${times.leftAnnihilator}"}
              require(battr.default == times.rightAnnihilator)
              {"for attribute ${attr.name}, right default value ${battr.default} != times fun right annihilator ${times.rightAnnihilator}. LeftAttr $a, RightAttr $b, timesFun $times"}
//              ValAttribute(attr.name, times.resultType, times.resultZero)
              multiplyTypeGet(attr.name, times)
            }
        require(timesFuns.size == res.size) {"mismatched number of times functions provided, $timesFuns for result value attributes $res"}
        return res
      }
      private fun <T1,T2,T3> multiplyTypeGet(name: Name, times: TimesFun<T1,T2,T3>) = ValAttribute<T3>(
          name,
          times.resultType,
          times.resultZero
      )

    }

    override fun run(): Iterator<NameTuple> {
      return MergeJoinIterator(p1.resultSchema.keys.intersect(p2.resultSchema.keys).toList(),
          p1.resultSchema.keys.map { it.name }, p2.resultSchema.keys.map { it.name },
          Iterators.peekingIterator(p1.run()),
          Iterators.peekingIterator(p2.run()), timesFuns)
    }

    data class MergeJoinIterator(
        val keys: List<Attribute<*>>, // common keys
        val p1keys: List<Name>,
        val p2keys: List<Name>,
        val i1: PeekingIterator<NameTuple>,
        val i2: PeekingIterator<NameTuple>,
        val timesFuns: Map<Name, TimesFun<*,*,*>>
    ) : Iterator<NameTuple> {

      val comparator = KeyComparator(keys)
      var topIter: PeekingIterator<NameTuple> = findTop()

      class OneRowIterator<T>(val rowComparator: Comparator<T>,
                              private val iter: PeekingIterator<T>) : PeekingIterator<T> by iter {
        val firstTuple: T? = if (iter.hasNext()) iter.peek() else null

        override fun next(): T = if (hasNext()) iter.next() else throw NoSuchElementException("the iterator is past the original row $firstTuple")

        override fun hasNext(): Boolean = iter.hasNext() && rowComparator.compare(firstTuple, iter.peek()) == 0

        override fun peek(): T = if (hasNext()) iter.peek() else throw NoSuchElementException("the iterator is past the original row $firstTuple")
      }
      fun readRow(
          /** See [TupleComparatorByKeyPrefix] */
          rowComparator: Comparator<NameTuple>,
          iter: PeekingIterator<NameTuple>
      ): List<NameTuple> {
        check(iter.hasNext()) {"$iter should hasNext()"}
        val first = iter.peek()
        val list = LinkedList<NameTuple>()
        do {
          list.add(iter.next())
        } while (iter.hasNext() && rowComparator.compare(first, iter.peek()) == 0)
        return list
      }

      fun findTop(): PeekingIterator<NameTuple> {
        loop@while (i1.hasNext() && i2.hasNext()) {
          val c = comparator.compare(i1.peek(), i2.peek())
          when (Integer.signum(c)) {
            -1 -> i1.next()
            1 -> i2.next()
            else -> break@loop
          }
        }
        if (!i1.hasNext() || !i2.hasNext()) return Iterators.peekingIterator(Collections.emptyIterator())
        // We are either aligned or out of data on at least one iterator
        val one1 = OneRowIterator(comparator, i1)
        val one2 = readRow(comparator, i2)
        return Iterators.peekingIterator(CartesianIterator(one1, one2, this::times)) // must have at least one entry, but maybe it is the default entry
      }




      override fun hasNext(): Boolean {
        return topIter.hasNext()
      }

      override fun next(): NameTuple {
        val r: NameTuple = topIter.next()
        if (!topIter.hasNext())
          topIter = findTop()
        return r
      }

      private fun times(t1: NameTuple, t2: NameTuple): NameTuple {
        return timesFuns.mapValues { (name,f) ->
          @Suppress("UNCHECKED_CAST")
          when {
            name in t1 && name in t2 -> (f.times as (Any?,Any?) -> Any?)(t1[name], t2[name]) // we should always have this case
            name in t1 -> t1[name]
            name in t2 -> t2[name]
            else -> f.resultZero
          }
        } + t1.filterKeys { it in p1keys } + t2.filterKeys { it in p2keys }
      }

      class CartesianIterator(
          private val firstIter: PeekingIterator<NameTuple>,
          private val secondIterable: Iterable<NameTuple>,
          private val multiplyOp: (NameTuple, NameTuple) -> NameTuple
      ) : Iterator<NameTuple> {
        private var secondIter: PeekingIterator<NameTuple> = Iterators.peekingIterator(secondIterable.iterator())

        init {
          if (!firstIter.hasNext() || !secondIter.hasNext()) {
            while (firstIter.hasNext()) firstIter.next()
          }
        }

        /*
        1. scan left until we find a position where hasNext() is true. If all are false then terminate.
        2. advance that iterator at the position and fill in curTuples
        3. reset all iterators to the right and fill in curTuples
         */

        override fun hasNext(): Boolean {
          return firstIter.hasNext() && secondIter.hasNext()
        }

        override fun next(): NameTuple {
          val ret = multiplyOp(firstIter.peek(), secondIter.next())
          prepNext()
          return ret
        }

        private fun prepNext() {
          if (!secondIter.hasNext()) {
            firstIter.next()
            if (!firstIter.hasNext())
              return
            secondIter = Iterators.peekingIterator(secondIterable.iterator())
          }
        }
      }


    }
  }


  data class LoadData(
      override val resultSchema: Schema,
      val iter: Iterable<NameTuple>
  ) : TupleOp() {
    override fun run(): Iterator<NameTuple> = iter.iterator()
    override fun transform(f: (TupleOp) -> TupleOp) = f(this)
    override fun toString(): String {
      return "LoadData(resultSchema=$resultSchema)"
    }
  }

  data class LoadOnce(
      override val resultSchema: Schema,
      val iter: Iterator<NameTuple>
  ) : TupleOp() {
    private var ran = false
    override fun run(): Iterator<NameTuple> {
      if (ran) logger.warn{"$this ran more than once"}
      ran = true
      return iter
    }
    override fun transform(f: (TupleOp) -> TupleOp) = f(this)
    override fun toString(): String {
      return "LoadOnce(resultSchema=$resultSchema, ran=$ran)"
    }

    companion object : Loggable {
      override val logger: Logger = logger<LoadOnce>()
    }

  }

}




///* First lower to keep the names with the scheams. Then erase the names.
// */
//
//interface PosSchema {
//  val names: List<Name>
//  val types: List<Attribute<*>>
//}
//interface PosTuple {
//  val attrs: List<*>
//}
