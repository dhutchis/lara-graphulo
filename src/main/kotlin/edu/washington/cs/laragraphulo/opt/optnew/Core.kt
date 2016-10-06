package edu.washington.cs.laragraphulo.opt.optnew

import com.google.common.collect.ImmutableList
import java.io.Serializable


data class ArgType<T>(
    val name: String,
    val type: Class<T>,
    /** If null, then this is a required argument. */
    val default: T? = null
)

class InvokeException(val op: Op<*>,
                      msg: String = "problem invoking $op",
                      cause: Throwable? = null): RuntimeException(msg, cause)

abstract class Op<out R>(args: List<Op<*>> = emptyList()) : Serializable {
  constructor(vararg args: Op<*>): this(ImmutableList.copyOf(args))
  val args: List<Op<*>> = ImmutableList.copyOf(args)

  open val reqArgs: List<ArgType<*>> = ImmutableList.of()

  /** Call by name. Cannot do variable-argument construction, unless one of the arguments is a list. */
  operator fun invoke(argMap: Map<String,*>): R {
    return invoke(checkReqs(reqArgs.map { argMap[it.name] }))
  }

  /**
   * Check whether the arguments given in reqs match the required types.
   * Null may be present in reqs. If so, the argument it goes to must have a default.
   * The order of reqs must match that of [reqArgs].
   */
  fun checkReqs(reqs: List<*>): List<*> {
    if (reqs.size != reqArgs.size) throw InvokeException(this, "incorrect number of arguments given; expected $reqArgs; got $reqs")
    return reqs.zip(reqArgs).map {
      it.first?.apply {
        if (!it.second.type.isInstance(this)) throw InvokeException(this@Op, "bad argument type for ${it.second}: $this")
      } ?: it.second.default ?: throw InvokeException(this, "no argument given for ${it.second}")
    }
  }

  /** Call by position. Recommended to use [checkReqs] to check the arguments. */
  abstract operator fun invoke(reqs: List<*> = emptyList<Any>()): R

  override fun toString(): String{
    return "Op(args=$args)"
  }

  override fun equals(other: Any?): Boolean{
    if (this === other) return true
    if (other?.javaClass != javaClass) return false

    other as Op<*>

    if (args != other.args) return false

    return true
  }
  override fun hashCode(): Int = args.hashCode()
}


