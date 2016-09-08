package edu.washington.cs.laragraphulo.opt

import com.google.common.base.Preconditions
import com.google.common.collect.*
import org.apache.accumulo.core.data.*
import org.apache.hadoop.io.WritableComparator
import java.util.*
import java.util.function.Function
import java.util.regex.Pattern
import kotlin.comparisons.compareBy
import kotlin.comparisons.nullsLast

data class KeyValue(val key: Key, val value: Value)

/**
 * `>= 0` means fixed width.
 * `-1` means variable width.
 */
typealias Width = Int

/** Attribute/column position */
typealias Position = Int

/**
 * Interpretation for missing values.
 */
typealias Default = ArrayByteSequence

/*
These interfaces are capability interfaces.
They do not enforce any error checking. That is left to implementing classes.
 */

//interface NameSchema {
//  val names: List<Name>
//}
interface WidthSchema {
  /**
   * A width for each key attribute.
   *
   * `>= 0` means fixed width.
   * `-1` means variable width.
   *
   * The last attribute in the dap may be variable width.
   * Todo: Support other variable-width encoding.
   */
  val widths: List<Width>
//    require(widths.size == names.size) {"widths and names sizes differ: $widths ; $names"}
//    require(widths.all { it == -1 || it >= 0 }) {"There is a bad width: $widths"}
}
interface DefaultSchema {
  /**
   * Interpretation for missing values.
   */
  val defaults: List<Default>
//    require(defaults.size == names.size) {"defaults and names sizes differ: $defaults ; $names"}
}
interface KeySchema {
  val keyNames: List<Name>
//  val valNames: List<Name>
}
interface APSchema : KeySchema {
  /** Length of the distributed access path. Partitions the key values into the dap and lap. */
  val dapLen: Int
  /** distributed access path; the first portion of [keyNames] */
  val dap: List<Name>
      get() = lazy { keyNames.subList(0,dapLen) }.value
  /** local access path; the second portion of [keyNames] */
  val lap: List<Name>
      get() = lazy { keyNames.subList(dapLen, keyNames.size) }.value
  val dapRange: IntRange
      get() = 0..dapLen-1
  val lapRange: IntRange
      get() = dapLen..lap.size-1
//  val lapOff: Int
//      get() = dapLen
//  val lapLen: Int
//      get() = keyNames.size - dapLen
//  /**
//   * column access path; defines the [valNames]
//   *
//   * A list of the attribute groups. Each group is potentially stored in a different file.
//   * The ordering of attributes within groups is lexicographic.
//   */
//  val cap: List<ColumnFamily>
}
interface SortedSchema : APSchema {
  /** An int such that all [KeySchema.keyNames] whose index is less than sortedUpto are sorted.
   * 0 means nothing is sorted. Valid up to and including [KeySchema.keyNames].size. */
  val sortedUpto: Int
  /** Whether there are multiple tuples with the same key attribute values. */
  val duplicates: Boolean
}

sealed class ImmutableKeySchema(
    keyNames: List<Name>
): KeySchema { // List<Name> by keyNames,
  final override val keyNames: List<Name> = ImmutableList.copyOf(keyNames)
      init {
    // check for duplicate names
    val set = this.keyNames.toSet()
    require(set.size == this.keyNames.size) {"There is a duplicate attribute name: ${this.keyNames}"}
    // check for invalid names
    set.forEach { checkName(it) }
  }

  companion object {
    /** The regular expression specifying what names are valid.  */
    val VALID_NAME_REGEX = "^[a-zA-Z_]\\w*$"
    /** The regular expression matcher for [.VALID_NAME_REGEX].  */
    private val VALID_NAME_PATTERN = Pattern.compile(VALID_NAME_REGEX)
    /**
     * Validate a potential column name for use in a Schema. Valid names are given by [.VALID_NAME_REGEX].
     * @param name the candidate column name.
     * @return passes through the argument name
     * @throws IllegalArgumentException if the name does not match the regex [.VALID_NAME_REGEX].
     */
    fun checkName(name: String): String {
      require(VALID_NAME_PATTERN.matcher(name).matches()) {"supplied column name $name does not match the valid name regex $VALID_NAME_REGEX"}
      return name
    }

    fun of(attrs: List<Name>): ImmutableKeySchema = ImmutableKeySchemaImpl(attrs)
  }

//  /**
//   * Return true if the two schema are "compatible": they have the same size and column types; column names are ignored.
//   */
//  fun compatible(a2: Attributes): Boolean {
//    return names.size == a2.names.size &&
//        names.zip(a2.names).all { it.first.encoder == it.second.encoder }
//  }


  // todo: test this to see if it improves performance
////  @Transient
//  private val nameToIndex: Map<Name, Int> by lazy {
//    ImmutableMap.copyOf(names.mapIndexed { i, attribute -> attribute to i }.toMap())
//  }

//  /**
//   * Find the index of the column with a given name.
//   * @throws NoSuchElementException if no column with a matching name is found.
//   */
//  fun columnNameToIndex(name: String): Int =
//      this.indexOf(name)
//      nameToIndex[name] ?: throw NoSuchElementException("No column named $name found; names are ${nameToIndex.keys}")

//  /**
//   * Return a subset of the current schema.
//   * @param idxs indices to be selected.
//   */
//  open fun getSubAttribtues(idxs: IntArray): ImmutableKeySchema =
//      ImmutableKeySchemaImpl(names.slice(idxs.asIterable()))


//  operator fun contains(name: Name): Boolean = name in nameToIndex

  // consider overriding +, -

  override fun toString(): String = "ImmutableKeySchema$keyNames"
  override fun equals(other: Any?): Boolean{
    if (this === other) return true
    if (other?.javaClass != javaClass) return false

    other as ImmutableKeySchema

    if (keyNames != other.keyNames) return false

    return true
  }

  override fun hashCode(): Int{
    return keyNames.hashCode()
  }


  private class ImmutableKeySchemaImpl(attrs: List<Name>)
  : ImmutableKeySchema(ImmutableList.copyOf(attrs))
}


sealed class ImmutableAccessPath(
    dap: List<Name>,
    lap: List<Name>
) : ImmutableKeySchema(
    ImmutableList.builder<Name>().addAll(dap).addAll(lap).build()
), APSchema {
  final override val dapLen = dap.size
  final override val dap = super.dap
  final override val lap = super.lap
//  init {
//    require(cap.sumBy { it.attributes.count() } == valNames.size) {
//      "one of the attributes was mentioned twice in two separate column families $cap"
//    }
//  }

  companion object {
    fun of( dap: Collection<Name>,
            lap: Collection<Name>): ImmutableAccessPath = ImmutableAccessPathImpl(dap,lap)
  }

  private class ImmutableAccessPathImpl(
      dap: Collection<Name>,
      lap: Collection<Name>
  ) : ImmutableAccessPath(ImmutableList.copyOf(dap), ImmutableList.copyOf(lap))

  override fun toString(): String = "ImmutableAccessPath(dap=$dap, lap=$lap)"

  override fun equals(other: Any?): Boolean{
    if (this === other) return true
    if (other?.javaClass != javaClass) return false

    other as ImmutableAccessPath

    if (dap != other.dap) return false
    if (lap != other.lap) return false

    return true
  }

  override fun hashCode(): Int{
    var result = dap.hashCode()
    result = 31 * result + lap.hashCode()
    return result
  }


}


// need to subclass ImmutableAccessPath because this tells us how to interpret each part of the Key/Value
sealed class ImmutableBagAccessPath(
    /** @see [APSchema.dap] */
    dap: List<Name>,
    /** @see [APSchema.lap] */
    lap: List<Name>,
    final override val sortedUpto: Int,
    final override val duplicates: Boolean
) : ImmutableAccessPath(dap, lap), SortedSchema {
  init {
    Preconditions.checkPositionIndex(sortedUpto, dap.size+lap.size, "sortedUpto is an int such that all keyNames $keyNames " +
        "whose index is less than sortedUpto are sorted. 0 means nothing is sorted. Valid up to and including ${dap.size+lap.size}. Given: $sortedUpto")
  }

  companion object {
    fun of( dap: List<Name>,
            lap: List<Name>,
            sortedUpto: Int = -1,
            duplicates: Boolean = false): ImmutableBagAccessPath = ImmutableBagAccessPathImpl(dap, lap,
        if (sortedUpto == -1) dap.size+lap.size else sortedUpto, duplicates)
  }

  private class ImmutableBagAccessPathImpl(
      dap: List<Name>,
      lap: List<Name>,
      sortedUpto: Int,
      duplicates: Boolean
  ) : ImmutableBagAccessPath(ImmutableList.copyOf(dap), ImmutableList.copyOf(lap), sortedUpto, duplicates)

  override fun toString(): String{
    val s = StringBuilder("ImmutableBagAccessPath(dap=$dap, lap=$lap")
    if (sortedUpto != dap.size+lap.size)
      s.append(", sortedUpto=$sortedUpto")
    if (duplicates)
      s.append(", dups")
    s.append(")")
    return s.toString()
  }

  override fun equals(other: Any?): Boolean{
    if (this === other) return true
    if (other?.javaClass != javaClass) return false
    if (!super.equals(other)) return false

    other as ImmutableBagAccessPath

    if (sortedUpto != other.sortedUpto) return false
    if (duplicates != other.duplicates) return false

    return true
  }

  override fun hashCode(): Int{
    var result = super.hashCode()
    result = 31 * result + sortedUpto
    result = 31 * result + duplicates.hashCode()
    return result
  }


}







//interface RelaxedTuple {
////  operator fun get(name: Name): Any?
//  operator fun get(idx: Int): Any?
////  operator fun set(name: Name, v: Any?)
//  /** @return the element previously at this location */
//  operator fun set(idx: Int, v: Any?): Any?
//}

data class FullValue(
    val value: ArrayByteSequence,
    val visibility: ArrayByteSequence,
    val timestamp: Long
)

/** Be wary of mutating anything. Try to keep it immutable. */
interface Tuple {
  val keys: List<ArrayByteSequence>
  val family: ArrayByteSequence
  /** At a minimum, this should contain a mapping from the empty string to a FullValue. */
  val vals: ListMultimap<ArrayByteSequence, FullValue>
}

class TupleImpl(
    keys: List<ArrayByteSequence>,
    override val family: ArrayByteSequence,
    vals: ListMultimap<ArrayByteSequence, FullValue>
) : Tuple {
  override val keys: List<ArrayByteSequence> = ImmutableList.copyOf(keys)
  override val vals: ImmutableListMultimap<ArrayByteSequence, FullValue> = ImmutableListMultimap.copyOf(vals)



  /**
   * Convert this Tuple to a list of KeyValues.
   * To guarantee sortedness, either change the implementation here or use Collections.sort afterward, with a key comparator
   */
  fun toKeyValues(apSchema: APSchema): List<KeyValue> {

    /** Only when [IntRange.step] is 0 */
    fun IntRange.size(): Int = this.endInclusive - this.first + 1

    /** @return Array, offset, length */
    fun arrayFromParts(keyRange: IntRange): Triple<ByteArray,Int,Int> =
        when {
          keyRange.isEmpty() -> Triple(ByteArray(0),0,0)
          keyRange.size() == 1 -> keys[keyRange.first].let { Triple(it.backingArray, it.offset(), it.length()) }
          // general case: copy
          else -> {
            val dapList = keys.slice(keyRange)
            val rowLen = dapList.sumBy { it.length() }
            val rowArr = ByteArray(rowLen)
            var p = 0
            for (seq in dapList) {
              val len = seq.length()
              System.arraycopy(seq.backingArray, seq.offset(), rowArr, p, len)
              p += len
            }
            Triple(rowArr, 0, rowLen)
          }
        }

    // an optimization is possible whereby we check to see if the dapList's ArrayByteSequences
    // all reference the same array and form a contiguous block of the array
    val (rowArr, rowOff, rowLen) = arrayFromParts(apSchema.dapRange)

    // column qualifier prefix
    val (lapArr, lapOff, lapLen) = arrayFromParts(apSchema.lapRange)
    // this could be optimized in the case of singleton vals

    val retList = ArrayList<KeyValue>(vals.size())
    for ((valName, fullValueList) in vals.asMap().entries) {
      val cqArr = ByteArray(lapLen + valName.length())
      System.arraycopy(lapArr, lapOff, cqArr, 0, lapLen)
      System.arraycopy(valName.backingArray, valName.offset(), lapArr, lapLen, valName.length())

      for ((valueArr, visibility, timestamp) in fullValueList) {
        val key =
            if (rowOff == 0 && rowLen == rowArr.size &&
                family.isContiguousArray() && visibility.isContiguousArray())
              Key(rowArr, family.backingArray, cqArr, visibility.backingArray, timestamp, false, false) // no copy
            else
              Key(rowArr, rowOff, rowLen,
                  family.backingArray, family.offset(), family.length(),
                  cqArr, 0, cqArr.size,
                  visibility.backingArray, visibility.offset(), visibility.length(),
                  timestamp
              ) // copies data, sadly. 2.0 api may get around this
        val value = valueArr.backingArray.let {
          if (valueArr.offset() == 0 && valueArr.length() == it.size) Value(it, false) // no copy
          else Value(it, valueArr.offset(), valueArr.length()) // copy
        }
        retList.add(KeyValue(key, value))
      }
    }
    return retList
  }

  override fun toString(): String{
    return "Tuple(keys=$keys, family=$family, vals=$vals)"
  }

  override fun equals(other: Any?): Boolean{
    if (this === other) return true
    if (other?.javaClass != javaClass) return false

    other as TupleImpl

    if (family != other.family) return false
    if (keys != other.keys) return false
    if (vals != other.vals) return false

    return true
  }

  override fun hashCode(): Int{
    var result = family.hashCode()
    result = 31 * result + keys.hashCode()
    result = 31 * result + vals.hashCode()
    return result
  }


}


fun ArrayByteSequence.isContiguousArray() = this.offset() == 0 && this.length() == this.backingArray.size

object EMPTY : ArrayByteSequence(ByteArray(0),0,0)


object TupleComparatorOnKeys : Comparator<Tuple> {
  /** Comparison on all attributes */
  override fun compare(o1: Tuple, o2: Tuple): Int {
    val i1 = o1.keys.iterator()
    val i2 = o2.keys.iterator()
    while (i1.hasNext() && i2.hasNext()) {
      val c = i1.next().compareTo(i2.next())
      if (c != 0) return c
    }
    return o1.keys.size - o2.keys.size
  }
}


////  override operator fun get(name: Name): ArrayByteSequence
//  override operator fun get(idx: Int): ArrayByteSequence
////  @Deprecated("Use the ArrayByteSequence version", ReplaceWith("if (v is ArrayByteSequence) set(name, v) else throw IllegalArgumentException(\"\$v is not a ArrayByteSequence\")", "org.apache.accumulo.core.data.ArrayByteSequence"), DeprecationLevel.ERROR)
////  override operator fun set(name: String, v: Any?) {
////    if (v is ArrayByteSequence) set(name, v)
////    else throw IllegalArgumentException("$v is not a ArrayByteSequence")
////  }
//  @Deprecated("Use the ArrayByteSequence version", ReplaceWith("if (v is ArrayByteSequence) set(idx, v) else throw IllegalArgumentException(\"\$v is not a ArrayByteSequence\")", "org.apache.accumulo.core.data.ArrayByteSequence"), DeprecationLevel.ERROR)
//  override operator fun set(idx: Int, v: Any?): ArrayByteSequence {
//    if (v is ArrayByteSequence) return set(idx, v)
//    else throw IllegalArgumentException("$v is not a ArrayByteSequence")
//  }
////  operator fun set(name: Name, v: ArrayByteSequence)
//  operator fun set(idx: Int, v: ArrayByteSequence): ArrayByteSequence
//}

// consider renaming to ByteTuple for the immutable version
//class ByteTuple(
////    val ap: ImmutableAccessPath,
//    /** The order of the buffers must match the order of the attributes in [ap] */
//    private val buffers: List<ArrayByteSequence>
//) : TupleImpl(buffers) {
////  constructor(buffers: MutableCollection<ByteArray>): this(buffers.map { ArrayByteSequence(it) })
//
////  init {
////    // there is a ArrayByteSequence for every attribute
////    Preconditions.checkArgument(buffers.size == ap.attributes.size,
////        "expected %s data buffers but was given %s", ap.attributes.size, buffers.size)
////  }
//
////  override operator fun get(idx: Int): ArrayByteSequence = buffers[idx]
//////  override operator fun get(name: String): ArrayByteSequence = get(ap.indexOf(name))
////  override operator fun set(idx: Int, v: ArrayByteSequence): ArrayByteSequence {
////    val p = buffers[idx]
////    buffers[idx] = v
////    return p
////  }
//////  override operator fun set(name: String, v: ArrayByteSequence) = set(ap.indexOf(name), v)
//
//  // could define a constructor that takes a map of names to ArrayByteSequences
//  // use the AP to put the buffers in the right order
//
//
//
//  override fun toString(): String = buffers.toString()
//  override fun equals(other: Any?): Boolean{
//    if (this === other) return true
//    if (other?.javaClass != javaClass) return false
//
//    other as ByteTuple
//
//    if (buffers != other.buffers) return false
//
//    return true
//  }
//
//  override fun hashCode(): Int{
//    return buffers.hashCode()
//  }
//
//
//}


typealias ExtFun = (TupleImpl) -> Iterator<TupleImpl>

/**
 * Assumes that the iterator is in the order specified by a correct ImmutableAccessPath
 */
@Suppress("UNCHECKED_CAST")
fun Iterator<TupleImpl>.ext(f: ExtFun): Iterator<TupleImpl> {
  return Iterators.concat(Iterators.transform(this, f as (TupleImpl?) -> Iterator<TupleImpl>))
}


// todo - consider caching these objects
data class TupleComparatorByKeyPrefix(val size: Int) : Comparator<Tuple> {
  override fun compare(o1: Tuple, o2: Tuple): Int {
    require(o1.keys.size >= size && o2.keys.size >= size) {"Tuples do not have enough keys to compare by $size keys: $o1; $o2"}
    for (i in 0..size-1) {
      val c = o1.keys[i].compareTo(o2.keys[i])
      if (c != 0) return c
    }
    return 0
  }
}

data class TupleIteratorComparatorByPrefix(val size: Int) : Comparator<PeekingIterator<out Tuple>> {
  val tcomp = nullsLast(TupleComparatorByKeyPrefix(size)) // nulls always last
  override fun compare(o1: PeekingIterator<out Tuple>, o2: PeekingIterator<out Tuple>): Int =
      tcomp.compare(if (o1.hasNext()) o1.peek() else null, if (o2.hasNext()) o2.peek() else null)
}


/** Todo: revise this for the SKVI version of seek that takes a range, column families, inclusive */
interface SeekableIterator<T> : Iterator<T> {
  fun seek(seekKey: T)
}


// later this will need to be a full interface, so that subclasses can maintain state
typealias MultiplyOp = (Array<Tuple>) -> Iterator<Tuple>

interface Collider {
  fun schema(inputs: List<SortedSchema>): SortedSchema
  /** Do NOT modify the contents of [actives]. */
  fun collide(inputs: List<PeekingIterator<Tuple>>, actives: BooleanArray): Iterator<Tuple>
}

/**
 * This function assumes that the [commonKeys] are at the prefix of ever input.
 * I assume no inputs are aliased, and the commonKeys do not contain duplicates.
 *
 * This iterator eagerly reads the input iterators in order to cache the first element.
 */
class Merger(
    inputs: List<Iterator<Tuple>>,
    prefixSize: Int,
    val collider: Collider,
    emitNoMatches: Set<Int> = setOf() // this could be a BooleanArray
): Iterator<Tuple> {
  private val inputs: ImmutableList<PeekingIterator<Tuple>> = inputs.fold(ImmutableList.builder<PeekingIterator<Tuple>>()) { builder, input -> builder.add(Iterators.peekingIterator(input)) }.build()
  private val emitNoMatches = BooleanArray(inputs.size) //ImmutableSet.copyOf(emitNoMatches)

  val inputComparator = TupleIteratorComparatorByPrefix(prefixSize)
  val inputIndexComparator: Comparator<Int> = java.util.Comparator.comparing(
      Function({ it:Int -> this.inputs[it] }), inputComparator)
  /** A priority queue of indexes, referencing [inputs] and [emitNoMatches] */
  val pq: PriorityQueue<Int> = PriorityQueue(inputs.size, inputIndexComparator)
  var topIter: Iterator<Tuple> = Collections.emptyIterator()

  private val _actives = BooleanArray(inputs.size)
  private var _collision  = false
  private var _allFinished = false

  init {
    // check that emitNoMatches is a valid set - every index corresponds to an input
    emitNoMatches.forEach {
      Preconditions.checkElementIndex(it, inputs.size, "emitNoMatch index $it is out of range; provided ${inputs.size} inputs")
      this.emitNoMatches[it] = true
    }
    pq.addAll(inputs.indices)
    findTopIter()
  }

  /** Todo: test that these indexes are the *least*. If not, reverse the comparator.
   * Sets state variables [_actives] and [_collision] according to the active set of indices and whether they trigger a collision.
   * */
  private fun pollActives() {
//    _actives.fill(false)
    val top = pq.poll()!!
    // return all false if no iterators have any more elements left
    if (!inputs[top].hasNext()) {
      _allFinished = true
      return
    }
    _actives[top] = true
    _collision = _collision || this.emitNoMatches[top] // collision if an active match is designated as emitNoMatch
    while (pq.isNotEmpty() && inputIndexComparator.compare(top, pq.peek()) == 0) {
      val p = pq.poll()
      _actives[p] = true
      _collision = _collision || this.emitNoMatches[p]
    }
    if (pq.isEmpty()) _collision = true // collision if all iterators are active
    // all active inputs have hasNext() == true
  }

  /**
   * Re-add the active indexes into the priority queue, after advancing them to at least the least element in the queue.
   */
  private fun advanceActives() {
    // all active inputs have hasNext() == true
    val toAdvanceTo: PeekingIterator<Tuple>? = pq.poll()?.let { inputs[it] }
    for ((idx, active) in _actives.withIndex()) {
      if (!active) continue
      // todo: this can be optimized further to account for the emitNoMatch criteria
      if (toAdvanceTo == null)
        inputs[idx].next()
      else
        advanceTo(inputs[idx], toAdvanceTo)
    }
  }

  private fun addActivesIntoQueue() {
    for ((idx, active) in _actives.withIndex()) {
      if (!active) continue
      pq.add(idx)
      _actives[idx] = false
    }
    _collision = false
    // post-condition: actives is filled with false, no collision
  }

  /** Primitive version without seeking */
  private fun advanceTo(input: PeekingIterator<Tuple>, toAdvanceTo: PeekingIterator<Tuple>) {
    do {
      input.next()
    } while (input.hasNext() && inputComparator.compare(input, toAdvanceTo) < 0)
  }

  private fun findTopIter() {
    if (inputs.isEmpty()) return
    do {
      pollActives()
      while (!_allFinished && !_collision) {
        advanceActives()
        addActivesIntoQueue()
        pollActives()
      }
      if (_allFinished)
        return
      topIter = collider.collide(inputs, _actives) // collider must advance the active inputs
      addActivesIntoQueue()
    } while (!topIter.hasNext())
  }

  override fun hasNext(): Boolean = topIter.hasNext()

  override fun next(): Tuple {
    val next = topIter.next()
    if (!topIter.hasNext())
      findTopIter()
    return next
  }
}

fun commonKeyNames(schemas: List<KeySchema>): Set<Name> =
    schemas.map { it.keyNames.toSet() }.reduce { s1, s2 -> s1.intersect(s2) }

fun ensureKeyNamesSortedAtPrefix(schemas: List<SortedSchema>, names: Set<Name>): List<Name> {
  if (schemas.isEmpty()) return names.toList()
  val bag1 = schemas[0]
  val nl: List<Name> = bag1.keyNames.subList(0, names.size)
  require(nl.toSet() == names) {"names $names must be in the prefix of each iterator, but the first iterator has a prefix of $nl"}
  schemas.forEach {
    require(it.keyNames.subList(0, names.size) == nl) {"all iterators must have the same prefix key attributes; expected $nl but actual ${it.keyNames}"}
    require(it.sortedUpto >= names.size) {"all iterators must be sorted at least up to the names $names; this one is sorted on the first ${it.sortedUpto} elements of ${it.keyNames}"}
  }
  return nl
}

fun ensureSamePrefix(schemas: List<KeySchema>, prefixSize: Int) {
  if (schemas.isEmpty()) return
  schemas.forEach { require(it.keyNames.size >= prefixSize) {"A schema has less than $prefixSize keys: ${it}"} }
  for (i in 0..prefixSize-1) {
    val s = schemas[0].keyNames[i]
    for (j in 1..schemas.size-1)
      require(s == schemas[j].keyNames[i]) {"The schemas differ in their first $prefixSize attributes: $schemas"}
  }
}

fun ensureSortedUpto(schemas: List<SortedSchema>, prefixSize: Int) {
  schemas.forEach { require(it.sortedUpto >= prefixSize) }
}

/**
 * @return List of tuples from [iter] up until iter is exhausted or the [rowComparator] indicates that [iter] as a different tuple
 */
fun readRow(
    /** See [TupleComparatorByKeyPrefix] */
    rowComparator: Comparator<Tuple>,
    iter: PeekingIterator<Tuple>
): List<Tuple> {
  check(iter.hasNext()) {"$iter should hasNext()"}
  val first = iter.peek()
  val list = LinkedList<Tuple>()
  do {
    list.add(iter.next())
  } while (iter.hasNext() && rowComparator.compare(first, iter.peek()) == 0)
  return list
}

class OneRowIterator<T>(val rowComparator: Comparator<T>,
                     private val iter: PeekingIterator<T>) : PeekingIterator<T> by iter {
  val firstTuple: T? = if (iter.hasNext()) iter.peek() else null

  override fun next(): T = if (hasNext()) iter.next() else throw NoSuchElementException("the iterator is past the original row $firstTuple")

  override fun hasNext(): Boolean = iter.hasNext() && rowComparator.compare(firstTuple, iter.peek()) == 0

  override fun peek(): T = if (hasNext()) iter.peek() else throw NoSuchElementException("the iterator is past the original row $firstTuple")
}

class CompareKeyValueUptoColumnQualifierPrefix(val cqPrefix: Int): Comparator<Key> {
  override fun compare(o1: Key, o2: Key): Int {
    val c = o1.compareTo(o2, PartialKey.ROW_COLFAM)
    if (c != 0) return c

    val cq1 = o1.columnQualifierData
    val cq2 = o2.columnQualifierData
    return WritableComparator.compareBytes(
        cq1.backingArray, cq1.offset(), Math.min(cq1.length(), cqPrefix),
        cq2.backingArray, cq2.offset(), Math.min(cq2.length(), cqPrefix))
  }
}

class KeyValueToTuple(
    private val kvIter: PeekingIterator<KeyValue>,
    val apSchema: APSchema,
    val widthSchema: WidthSchema
): Iterator<Tuple> {
  init {
    require(widthSchema.widths.size >= apSchema.keyNames.size) {"bad widthSchema $widthSchema for schema $apSchema"}
  }

  val keyComparator = compareBy<KeyValue,Key>(
      CompareKeyValueUptoColumnQualifierPrefix(
          widthSchema.widths.subList(apSchema.dapLen, apSchema.dapLen+apSchema.lap.size).map {
            if (it == -1) throw UnsupportedOperationException("not supporting variable-length key attributes yet"); it }.sum()
      )) { it.key }


  private lateinit var rowIter: OneRowIterator<KeyValue>
  private lateinit var keyList: ImmutableList.Builder<ArrayByteSequence>
  private var valNamePos: Int = -1
  private lateinit var family: ArrayByteSequence

  private fun readKeysFromTop() {
    while(kvIter.hasNext()) {
      rowIter = OneRowIterator(keyComparator, kvIter)
      keyList = ImmutableList.builder<ArrayByteSequence>()
      val firstKV = rowIter.peek()

      /** @return the position of the first byte not read, or -1 if this is a bad tuple */
      fun addToList(bs: ByteSequence, off: Int, len: Int, allowVariableLast: Boolean): Int {
        assert(bs.isBackedByArray)
        var p = 0
        for (i in off..off + len - 1) {
          var width = widthSchema.widths[i]
          require(width != -1 || (allowVariableLast && i == off + len - 1)) { "Variable width not allowed here. Widths are ${widthSchema.widths}" }
          if (width == -1) {
            width = bs.length() - p
          } else if (p + width > bs.length()) {
            println("Warning: Dropping TupleImpl: bad key ${firstKV.key} for schema $apSchema and widths ${widthSchema.widths}")
            while (rowIter.hasNext()) rowIter.next() // drain tuple
            return -1
          }
          keyList.add(ArrayByteSequence(bs.backingArray, bs.offset() + p, width))
          p += width
        }
        return p
      }

      // fill the dap from the row
      val row = firstKV.key.rowData
      val tmp = addToList(row, 0, apSchema.dap.size, true) // don't care about the bytes remaining after reading the row
      if (tmp == -1) continue

      // fill the lap from the cq
      val cqFirst = firstKV.key.columnQualifierData
      valNamePos = addToList(cqFirst, apSchema.dapLen, apSchema.lap.size, false)
      if (valNamePos == -1) continue

      assert(firstKV.key.columnFamilyData is ArrayByteSequence)
      family = firstKV.key.columnFamilyData as ArrayByteSequence
    }
  }

  override fun hasNext(): Boolean {
    if (valNamePos == -1)
      readKeysFromTop()
    return kvIter.hasNext()
  }

  override fun next(): Tuple {
    if (!hasNext())
      throw NoSuchElementException()

    // vals
    val vals = ImmutableListMultimap.builder<ArrayByteSequence, FullValue>()
    do {
      val kv = rowIter.next()
      val cq = kv.key.columnQualifierData
      val valName = ArrayByteSequence(cq.backingArray, valNamePos, cq.length() - valNamePos)
      val tmp = kv.value.get()
      assert(kv.key.columnVisibilityData is ArrayByteSequence)
      val valValue = FullValue(ArrayByteSequence(tmp, 0, tmp.size),
          kv.key.columnVisibilityData as ArrayByteSequence, kv.key.timestamp)
      vals.put(valName, valValue)
    } while (rowIter.hasNext())

    valNamePos = -1

    return TupleImpl(keyList.build(), family, vals.build())
  }
}


















































///*
//  0. check that common keys are in the front of every iterator *in the same order*
//  1. align tuples on common key (the Aligner)
//  2. Collider: with the postcondition to advance all iterators past the common keys
//     Returns an Iterator<TupleImpl> - the tuples must conform to the colliderSchema -
//  2a. If holding all in memory, put all in memory and pass the maps to
// */
//
///**
// * @return resulting schema and the iterator stream
// */
//fun mergeJoin(
//    multiplyOp: MultiplyOp,
//    /** The Schema of value attributes as returned by the multiplyOp.
//     * The [multiplyOp] should not include key attributes. */
//    multiplyOpValSchema: ImmutableKeySchema,
//    inputs: List<Pair<Schema, Iterator<TupleImpl>>>
//): Pair<Schema, Iterator<TupleImpl>> {
//  // new keyNames = union of existing keyNames
//  // equi-join on matching key attributes
//  val schemas: List<Schema> = inputs.map { it.first }
//  val schemaNames: List<List<Triple<Int, Int, String>>> = schemas.mapIndexed { sidx, schema -> schema.keyNames.mapIndexed { idx, attr -> Triple(sidx, idx, attr) } }
//  // todo: need helper method to make sure that attributes with the same name are compatible (same type, etc.)
//  val commonNames: List<Triple<Int, Int, String>> = schemaNames.reduce { pa, pb -> pa.filter { ita -> pb.any { it.third == ita.third } } }
//  val resultKeyNames: List<Triple<Int, Int, String>> = schemaNames.fold(commonNames) { acc, names -> acc + (names.filterNot { itn -> acc.any { it.third == itn.third } })}
//  val resultkeyNames: List<Name> = resultKeyNames.map { schemas[it.first].keyNames[it.second] }
//  val resultSchema: Schema = Schema.build(resultkeyNames, multiplyOpValSchema)
//
//  // assert that the input Iterator<TupleImpl>s are sorted in the right way...
//
//  val comp = Comparator<TupleImpl> { o1, o2 ->
//    // tuples must match on all common key attributes
//    commonNames.forEach {
//      val b1 = o1[it.second]
//      val b2 = o2[it.second]
//      val c = b1.compareTo(b2)
////      WritableComparator.compareBytes(b1.array(), b1.arrayOffset() + b1.position(), b1.remaining(),
////          b2.array(), b2.arrayOffset() + b2.position(), b2.remaining())
//      if (c != 0)
//        return@Comparator c
//    }
//    0
//  }
//
//  // todo - replace this with code a la MergeJoin. This code just merges; it doesn't multiply
//  return resultSchema to Iterators.mergeSorted(inputs.map { it.second }, comp)
//}
//
//// method to check that an iterator is sorted in the right way, on the fly