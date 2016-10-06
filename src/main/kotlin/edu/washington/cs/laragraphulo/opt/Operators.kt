package edu.washington.cs.laragraphulo.opt

import com.google.common.collect.ImmutableListMultimap
import edu.washington.cs.laragraphulo.Encode
import org.apache.accumulo.core.data.ArrayByteSequence
import org.apache.accumulo.core.iterators.IteratorEnvironment
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVParser
import org.apache.commons.csv.CSVRecord
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.Serializable
import java.net.URL
import java.util.*



sealed class AccumuloOp(args: List<Op<*>> = emptyList()) : Op<Tuple>(args), Serializable
{
  constructor(vararg args: Op<*>): this(args.asList())

  /**
   * From the RACO types in the catalog for Scan; FileScan scheme.
   */
  abstract val encodingSchema: EncodingSchema
  /**
   * todo. Attach a prior iterator in place of this.
   */
  abstract val reducingSchema: ReducingSchema
  /**
   * From the order of the attributes in the catalog / FileScan scheme; with placeholders __DAP__ and __LAP__
   */
  abstract val keySchema: KeySchema
  /**
   * From the order of the attributes in the catalog / FileScan scheme; with placeholders __DAP__ and __LAP__
   */
  abstract val positionSchema: PositionSchema

  /**
   * @param parent The source iterator that one of the leaves will connect to.
   * @param options Execution-time environmental parameters, passed from client
   * @param env Execution-time Accumulo parameters
   */
  abstract fun construct(parent: TupleIterator, options: Map<String,String>, env: IteratorEnvironment): AccumuloLikeIterator<*,*>
}




class OpCSVScan(
    val url: Obj<String>,
    /** Nulls in the list indicate fields to skip reading */
    val encoders: Obj<List<Encode<String>?>>,
    val names: Obj<List<Pair<Name,Type<*>>>>,
    val skip: Obj<Int> = Obj(0),
    val delimiter: Obj<Char> = Obj(','),
    val quote: Obj<Char> = Obj('"'),
    val escape: Obj<Char?> = Obj(null)
) : AccumuloOp(url, encoders, skip, delimiter, quote, escape) {
  init {
    require(encoders().filterNotNull().size == names().size) {"There must be a name/type for each (non-null) encoder. Names: $names. Encoders: $encoders"}
  }
  override fun construct(parent: TupleIterator, options: Map<String, String>, env: IteratorEnvironment): CSVScan {
    return CSVScan(URL(url.obj), encoders.obj, skip.obj, delimiter.obj, quote.obj, escape.obj)
  }

  override val encodingSchema = throw UnsupportedOperationException("meh")
//  object : EncodingSchema {
//    override val encodings: Map<Name, Type<*>> = names().toMap()
//  }
  override val reducingSchema = throw UnsupportedOperationException("meh")
//  object : ReducingSchema {
//    override val reducers: Map<Name, (List<FullValue>) -> FullValue> = emptyMap()
//  }
  override val keySchema = throw UnsupportedOperationException("meh")
//  object : KeySchema {
//    override val keyNames: List<Name> = names().map { it.first }
//  }
  override val positionSchema: List<Name> = names().map { it.first }
}


/**
 * The output schema places all attributes into the key attributes, in the order of the encoders.
 */
class CSVScan(
    val url: URL,
    /** Nulls in the list indicate fields to skip reading */
    val encoders: List<Encode<String>?>,
    val skip: Int = 0,
    val delimiter: Char = ',',
    val quote: Char = '"',
    val escape: Char? = null
) : TupleIterator {
//  val parser: CSVParser
  private val iterator: Iterator<CSVRecord>
  private var linenumber: Int = 0

  init {
    val parser = CSVParser(
        BufferedReader(InputStreamReader(url.openStream())),
        CSVFormat.newFormat(delimiter).withQuote(quote).withEscape(escape))
    iterator = parser.iterator()
    for (i in 0..skip - 1) {
      iterator.next()
    }
  }

  private var top: Tuple? = null

  private fun findTop() {
    if (top == null && iterator.hasNext()) {
      val csvRecord = iterator.next()
      if (csvRecord.size() != encoders.size) {
        throw RuntimeException("error parsing line $linenumber: expected ${encoders.size} attributes: $csvRecord")
      }
      val attrs = csvRecord.zip(encoders).filter { it.second != null }.map { ArrayByteSequence(it.second!!.encode(it.first)) }
      top = TupleImpl(attrs, EMPTY, ImmutableListMultimap.of())
      linenumber++
    }
  }

  override fun hasNext(): Boolean {
    findTop()
    return top != null
  }
  override fun next(): Tuple {
    findTop()
    val t = top ?: throw NoSuchElementException()
    top = null
    return t
  }
  override fun peek(): Tuple {
    findTop()
    return top ?: throw NoSuchElementException()
  }
  override fun seek(seek: TupleSeekKey) {
    // recover from a saved state
    throw UnsupportedOperationException("not implemented")
  }
  override fun serializeState(): ByteArray {
    // write the line number to a bytearray
    throw UnsupportedOperationException("not implemented")
  }
  override fun deepCopy(env: IteratorEnvironment): CSVScan {
    if (linenumber != 0)
      throw UnsupportedOperationException("not implemented when iteration already began")
    return CSVScan(url, encoders, skip, delimiter, quote, escape)
  }
}





data class TypedExpr(
    val expr: Expr<ABS>,
    val type: Type<*>
)

data class ValueTypedExpr(
    val name: ABS,
    val expr: Expr<FullValue>,
    val type: Type<*>
)


class OpApplyIterator(
    val parent: AccumuloOp,
    val keyExprs: Obj<List< TypedExpr >>,
    val famExpr: Obj< TypedExpr >,
    val valExprs: Obj<List< ValueTypedExpr >>,
    override val keySchema: KeySchema
) : AccumuloOp(parent, keyExprs, famExpr, valExprs) {

  val encodings: Map<Name,Type<*>>
  val positions: List<Name>

  init {
    val keyNames: Map<Name, Type<*>> = keyExprs().zip(keySchema.keyNames).map { it.second to it.first.type }.toMap()
    val valNames = valExprs().map { it.name.toString() to it.type }
    val famName = mapOf(__FAMILY__ to famExpr().type)
    encodings = keyNames + valNames.toMap() + famName
    positions = keySchema.keyNames + __FAMILY__ + famName.map { it.key }
  }






  override val encodingSchema: EncodingSchema = object : EncodingSchema {
    override val encodings: Map<Name, Type<*>> = this@OpApplyIterator.encodings
  }
  override val reducingSchema: ReducingSchema = parent.reducingSchema
  override val positionSchema: PositionSchema = positions

  override fun construct(parent: TupleIterator, options: Map<String, String>, env: IteratorEnvironment): AccumuloLikeIterator<*, *> {
    return ApplyIterator(
        parent, keyExprs().map { it.expr }, famExpr().expr,
        valExprs().map { it.name to it.expr }
    )
  }
}







/** Mock version that does a fixed thing. */
class ApplyIterator(
    val parent: TupleIterator,
    val keyExprs: List<Expr<ABS>>,
    val famExpr: Expr<ABS>,
    val valExprs: List<Pair<ABS,Expr<FullValue>>>
) : TupleIterator {
  var topTuple: Tuple? = null

  companion object {
    val RESULT = ABS("result".toByteArray())
    val SRC = ABS("src".toByteArray())
    val DST = ABS("dst".toByteArray())
    val UNDER = '_'.toByte()
  }

  override fun seek(seek: TupleSeekKey) {
    parent.seek(seek)
  }

  private fun applyToTuple(pt: Tuple): Tuple {
    val input = listOf(pt) // single tuple expression
    val keys = keyExprs.map { it(input) }
    val fam = famExpr(input)
    val vals: ImmutableListMultimap<ArrayByteSequence, FullValue> = valExprs.fold(ImmutableListMultimap.builder<ABS,FullValue>()) { builder, it -> builder.put(it.first, it.second(input)) }.build()
    return TupleImpl(keys, fam, vals)
  }

  private fun prepTop() {
    if (topTuple != null && parent.hasNext()) {
      val t = parent.peek()
      // src_dst
//      val src = t.vals[SRC]!!.first().value
//      val dst = t.vals[DST]!!.first().value
//      val result = ByteArray(src.length()+dst.length()+1)
//      System.arraycopy(src.backingArray, src.offset(), result, 0, src.length())
//      result[src.length()] = UNDER
//      System.arraycopy(dst.backingArray, dst.offset(), result, src.length()+1, dst.length())
//      topTuple = TupleImpl(t.keys, t.family,
//          ImmutableListMultimap.of(RESULT, FullValue(ABS(result), EMPTY, Long.MAX_VALUE)))
      // todo - this implementation restricts Apply to return a single Tuple per input Tuple. Does not allow flatmap/ext.
      topTuple = applyToTuple(t)
    }
  }

  override fun peek(): Tuple {
    prepTop()
    return topTuple!!
  }

  override fun next(): Tuple {
    prepTop()
    val t = topTuple!!
    parent.next()
    topTuple = null
    return t
  }

  override fun hasNext(): Boolean {
    return parent.hasNext()
  }

  override fun serializeState(): ByteArray {
    throw UnsupportedOperationException("not implemented")
  }

  override fun deepCopy(env: IteratorEnvironment): ApplyIterator {
    return ApplyIterator(parent.deepCopy(env), keyExprs, famExpr, valExprs)
  }
}





class OpStoreIterator(
    val input: AccumuloOp,
    val tableName: String,
    val accumuloConfig: AccumuloConfig
) : AccumuloOp(input, Obj(tableName), Obj(accumuloConfig)) {
  override val encodingSchema: EncodingSchema
    get() = throw UnsupportedOperationException()
  override val reducingSchema: ReducingSchema
    get() = throw UnsupportedOperationException()
  override val keySchema: KeySchema
    get() = throw UnsupportedOperationException()
  override val positionSchema: List<String>
    get() = throw UnsupportedOperationException()

  override fun construct(parent: TupleIterator, options: Map<String, String>, env: IteratorEnvironment): AccumuloLikeIterator<*, *> {

//    TupleToKeyValueIterator(parent, )

    // RemoteWriteIterator from accumuloConfig
    // pass in the input

    throw UnsupportedOperationException("not implemented")
  }
}



































//inline fun <reified T> concatArrays(arrs: Array<Array<T>>): Array<T> {
//  val size = arrs.sumBy { it.size }
//  val a = Array<T?>(size, {null})
//  var i = 0
//  for (arr in arrs) {
//    System.arraycopy(arr, 0, a, i, arr.size)
//    i += arr.size
//  }
//  @Suppress("UNCHECKED_CAST")
//  return a as Array<T>
//}

fun concatArrays(vararg arrs: ByteArray): ByteArray {
  val size = arrs.sumBy { it.size }
  val a = ByteArray(size)
  var i = 0
  for (arr in arrs) {
    System.arraycopy(arr, 0, a, i, arr.size)
    i += arr.size
  }
  return a
}







// class ChangeAccessPath -- destroys sort

// class RemoteStore - RemoteWriteIterator

// class

// class Sink - drop all entries - hasTop() always false