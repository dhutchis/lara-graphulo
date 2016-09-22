package edu.washington.cs.laragraphulo.opt.raco

import java.io.PushbackReader
import java.io.Reader
import java.util.*


/**
 * Python Tree. Parsed form of a Python repr string.
 * This is the first stage of converting a RACO plan to a Kotlin/Java representation
 */
sealed class PTree {
  data class PNode(val name: String, val args: List<PTree>) : PTree()
  data class PList(val args: List<PTree>) : PTree()
  data class PPair(val left: PTree, val right: PTree) : PTree()
  data class PMap(val map: Map<String, PTree>) : PTree()
  data class PLong(val v: Long) : PTree()
  data class PDouble(val v: Double) : PTree()
  data class PString(val str: String) : PTree()

  companion object {
    /**
     * Parse a Python repr string into a [PTree]
     */
    fun parseRaco(repr: Reader): PTree = parseRacoOne(PushbackReader(repr))

    /**
     * NamedAttributeRef('dst')
     * @param fcc Optional first character
     */
    private fun parseRacoOne(repr: PushbackReader, fcc: Char? = null): PTree {
      val fc = fcc ?: repr.readNonWhitespace()
      return when (fc) {
        '-', '.', in '0'..'9' -> {
          val num = repr.readNumber(fc)
          if (num.contains('.') || num.contains('e') || num.contains('E'))
            PTree.PDouble(num.toDouble())
          else
            PTree.PLong(num.toLong())
        }
        '\'', '"' -> PTree.PString(repr.readName(fc)) // string ends with matching ' or "
        '[' -> PTree.PList(parseRacoArgs(repr, ']'))
        '(' -> parseRacoOne(repr).let { // ignoring case of tuples with 3+ elements
          repr.readAssert(',')
          val it2 = parseRacoOne(repr)
          repr.readAssert(')')
          PTree.PPair(it, it2)
        }
        '{' -> PTree.PMap(parseRacoMap(repr))
        else -> repr.readName('(', fc).let {
          PTree.PNode(it, parseRacoArgs(repr, ')')) // read NAME(ARG1,ARG2,...,ARGN)
        }
      }
    }

    private fun PushbackReader.readNumber(firstDigit: Char? = null): String {
      val sb = StringBuilder()
      if (firstDigit != null) sb.append(firstDigit)
      loop@ while (true) {
        val r = this.read()
        if (r == -1) break
        val c = r.toChar()
        when (c) {
          '.', 'e', 'E', 'x', 'X', in '0'..'9' -> sb.append(c)
          else -> {
            this.unread(r); break@loop
          }
        }
      }
      return sb.toString()
    }

    /**
     * Positions the stream immediately after the end character
     * @param ec end character, exclusive
     * @param fc first character, optional and inclusive
     */
    private fun Reader.readName(ec: Char, fc: Char? = null): String {
      val sbname = StringBuilder()
      if (fc != null) sbname.append(fc)
      while (true) {
        val r = this.read().assertNoEOS()
        if (r == ec) break
        sbname.append(r)
      }
      return sbname.toString()
    }

    private fun Int.assertNoEOS() = if (this == -1) throw IllegalArgumentException("repr ended early") else this.toChar()

    private fun Reader.readNonWhitespace(): Char = this.discardWhitespace().assertNoEOS()

    /** @return first non-whitespace character read, or -1 for end of stream */
    private fun Reader.discardWhitespace(): Int {
      var fc: Int
      do {
        fc = this.read()
      } while (fc != -1 && Character.isWhitespace(fc))
      return fc
    }

    private fun Reader.readAssert(c: Char) {
      val r = this.discardWhitespace()
      if (r == -1 || r.toChar() != c)
        throw IllegalArgumentException(if (r == -1) "stream ended early but expected $c" else "read ${r.toChar()} but expected $c")
    }

    /** // [ excluded
     *  ('src', NamedAttributeRef('src')), ('dst', NamedAttributeRef('dst'))]
     *  // ] included
     *  @param ec either ']' or ')'
     */
    private fun parseRacoArgs(repr: PushbackReader, ec: Char): List<PTree> {
      var t1: Char? = repr.readNonWhitespace()
      return if (t1 == ec) listOf()
      else {
        val arglist = ArrayList<PTree>()
        do {
          arglist.add(parseRacoOne(repr, t1))
          t1 = null
          val t = repr.readNonWhitespace() // additional args mean t is ','
        } while (t != ec)
        arglist
      }
    }

    /** // { excluded
     *  'a': 'b', 'skip': 0}
     *  // } included
     */
    private fun parseRacoMap(repr: PushbackReader): Map<String, PTree> {
      var t1: Char? = repr.readNonWhitespace()
      return if (t1 == '}') mapOf()
      else {
        val map = HashMap<String, PTree>()
        do {
          val name = parseRacoOne(repr, t1)
          t1 = null
          if (name !is PTree.PString)
            throw IllegalArgumentException("expected a PString name in the map but got $name")
          repr.readAssert(':')
          map.put(name.str, parseRacoOne(repr))
          val t = repr.readNonWhitespace() // additional mappings mean t is ','
        } while (t != '}')
        map
      }
    }

  }
}