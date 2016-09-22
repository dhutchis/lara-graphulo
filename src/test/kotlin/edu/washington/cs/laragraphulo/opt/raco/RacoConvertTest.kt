package edu.washington.cs.laragraphulo.opt.raco

import org.apache.accumulo.core.data.ArrayByteSequence
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.StringReader
import java.nio.ByteBuffer
import edu.washington.cs.laragraphulo.opt.raco.PTree.*

@RunWith(Parameterized::class)
class RacoConvertTestPrelim(
    val params: Params
) {

  data class Params (
      val name: String,
      val repr: String,
      val expected: PTree
  ) {
    override fun toString() = name
  }

  @Test
  fun testPTreeParse() {
    val inps = StringReader(params.repr).buffered()
    val parsed = PTree.parseRaco(inps)
    Assert.assertEquals(params.expected, parsed)
  }

  companion object {
    val tests = arrayOf(
        Params(
            name = "simple string",
            repr = "'hello world '",
            expected = PString("hello world ")
        ),
        Params(
            name = "simple long",
            repr = "  42    ",
            expected = PLong(42)
        ),
        Params(
            name = "complicated long",
            repr = " -000765",
            expected = PLong(-765)
        ),
        Params(
            name = "complicated double",
            repr = " -00.0765e2",
            expected = PDouble(-7.65)
        ),
        Params(
            name = "list 1",
            repr = "  [ \" hi \", 27 , 32 ]",
            expected = PList(listOf(
                PString(" hi "), PLong(27), PLong(32)
            ))
        ),
        Params(
            name = "list + pair",
            repr = "  [ \" hi \", (27, 'ok') , 32 ]",
            expected = PList(listOf(
                PString(" hi "), PPair(PLong(27), PString("ok")), PLong(32)
            ))
        ),
        Params(
            name = "list o list",
            repr = "  [ \" hi \", [(27, 'ok')] , 32 ]",
            expected = PList(listOf(
                PString(" hi "),
                PList(listOf(PPair(PLong(27), PString("ok")))),
                PLong(32)
            ))
        ),
        Params(
            name = "list o maps",
            repr = "  [ {\" hi \" : 99}, [(27, 'ok')] , {'inlist': [32]} ,{}]",
            expected = PList(listOf(
                PMap(mapOf(" hi " to PLong(99))),
                PList(listOf(PPair(PLong(27), PString("ok")))),
                PMap(mapOf("inlist" to PList(listOf(PLong(32))))),
                PMap(mapOf())
            ))
        ),
        Params(
            name = "0-arg",
            repr = "Dump()",
            expected = PNode("Dump", listOf())
        ),
        Params(
            name = "1-arg",
            repr = "Something('hey hey')",
            expected = PNode("Something", listOf(PString("hey hey")))
        ),
        Params(
            name = "3-arg nested",
            repr = "Something3('hey hey', Dump(), Something(42))",
            expected = PNode("Something3", listOf(
                PString("hey hey"),
                PNode("Dump", listOf()),
                PNode("Something", listOf(PLong(42)))
            ))
        )
    )

    @JvmStatic
    @Parameterized.Parameters(name = "test {index}: {0}")
    fun parameters(): Array<out Any> = tests
  }
}






class RacoConvertTest(
    val params: Params
) {


  data class Params (
      val query: String,
      val catalog: Scheme,
      val data: Relation,
      val expected: Relation
  )


  companion object {

    fun String.toABS() = this.toByteArray().let { ArrayByteSequence(it, 0, it.size) }
    fun Long.toABS() = ByteBuffer.allocate(4).putLong(this).array().let { ArrayByteSequence(it, 0, it.size) }

    // 'public:adhoc:smallGraph' : [('src', 'LONG_TYPE'), ('dst', 'LONG_TYPE')],
    // A = scan(smallGraph); B = select src, dst from A; dump(B);
    /*
    echo "A = scan(smallGraph); B = select src, dst from A; dump(B);" > tmp.myl &&
    c:/anaconda/python.exe "C:\Users\Class2014\GITdir\raco\scripts\myrial" tmp.myl -l
    Sequence
      Dump()[Apply(src=src,dst=dst)[Scan(public:adhoc:smallGraph)]]

    Dump(Apply([('src', NamedAttributeRef('src')), ('dst', NamedAttributeRef('dst'))], Scan(RelationKey(
    'public','adhoc','smallGraph'), Scheme([('src', 'LONG_TYPE'), ('dst', 'LONG_TYPE')]), 10000,
    RepresentationProperties(frozenset([]), None, None))))

    list [
    tuple (
    map {

    A = load("mock.csv", csv(schema(src:int,dst:int))); B = select src, dst from A; dump(B);
    Dump(Apply([('src', NamedAttributeRef('src')), ('dst', NamedAttributeRef('dst'))],
    FileScan('mock.csv', 'CSV', Scheme([('src', 'LONG_TYPE'), ('dst', 'LONG_TYPE')]), {})))
     */
    val tests = arrayOf(
        Params(
            query = "",
            catalog = mapOf(),
            data = listOf(),
            expected = listOf()
        ),
        Params(
            query = "",
            catalog = mapOf("src" to Type.LONG, "dst" to Type.LONG),
            data = listOf(
                mapOf("src" to 1L.toABS(), "dst" to 2L.toABS())
            ),
            expected = listOf()
        ),
        Params(
            query = "",
            catalog = mapOf("src" to Type.LONG, "dst" to Type.LONG),
            data = listOf(
                mapOf("src" to 1L.toABS(), "dst" to 2L.toABS()),
                mapOf("src" to 1L.toABS(), "dst" to 3L.toABS()),
                mapOf("src" to 1L.toABS(), "dst" to 4L.toABS()),
                mapOf("src" to 2L.toABS(), "dst" to 3L.toABS()),
                mapOf("src" to 2L.toABS(), "dst" to 5L.toABS()),
                mapOf("src" to 3L.toABS(), "dst" to 4L.toABS())
            ),
            expected = listOf()
        )
    )

    @JvmStatic
    @Parameterized.Parameters(name = "test {index}: {0}")
    fun parameters(): Array<out Any> = tests
  }



}