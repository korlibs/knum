package com.soywiz.knum

import java.nio.Buffer
import java.nio.FloatBuffer

object KNumExample {
    @JvmStatic
    fun main(args: Array<String>) {
        val res = KNum {
            //println(floatArrayOf(1f, 2f, 3f, 4f).const)
            //println(floatArrayOf(1f, 2f, 3f, 4f).const.reshape(2, 2))
            val tensor = floatArrayOf(1f, 2f, 3f, 4f).const + floatArrayOf(4f, 5f, 6f, 7f).const
            val result = (tensor * -(1f)).clamp(-8f, -6f).compute().getFloatArray()
            println(result.toList())
            10
        }
    }
}

open class KNumContext {
    class DefaultResult<T>(dims: IntArray, type: KNum.Type, val _data: Buffer) : KNum.Result<T>(dims, type) {
        override fun getData(): Buffer = _data
    }

    open fun <T> session(callback: () -> T): T {
        return callback()
    }

    fun <T> compute(tensor: KNum.Tensor<T>): KNum.Result<T> {
        return when (tensor) {
            is KNum.Constant -> computeConstant(tensor)
            is KNum.Operation -> computeOperation(tensor)
            else -> TODO("Don't know how to compute $tensor")
        }
    }

    open fun <T> computeConstant(tensor: KNum.Constant<T>): KNum.Result<T> {
        return DefaultResult<T>(tensor.dims, tensor.type, tensor.data)
    }

    open fun <T> computeOperation(tensor: KNum.Operation<T>): KNum.Result<T> = tensor.run {
        when (op) {
            "add", "sub", "mul", "div", "min", "max" -> computeBinaryOp<T>(op, compute(inputs[0] as KNum.Tensor<T>), compute(inputs[1] as KNum.Tensor<T>))
            "neg" -> computeUnaryOp<T>(op, compute(inputs[0] as KNum.Tensor<T>))
            else -> TODO("Unsuported operation $op")
        }
    }

    open fun <T> computeUnaryOp(op: String, l: KNum.Result<T>): KNum.Result<T> {
        val lf = l.getData() as FloatBuffer
        val num = l.numElements
        val fop = when (op) {
            "neg" -> ::fneg
            else -> TODO("Unsupported operation $op")
        }
        return DefaultResult<T>(l.dims, l.type, FloatBuffer.allocate(l.numElements).apply {
            for (n in 0 until num) put(n, fop(lf[n]))
        })
    }

    protected fun fneg(l: Float): Float = -l
    protected fun fadd(l: Float, r: Float): Float = l + r
    protected fun fsub(l: Float, r: Float): Float = l - r
    protected fun fmul(l: Float, r: Float): Float = l * r
    protected fun fdiv(l: Float, r: Float): Float = l / r
    protected fun fmax(l: Float, r: Float): Float = kotlin.math.max(l, r)
    protected fun fmin(l: Float, r: Float): Float = kotlin.math.min(l, r)

    open fun <T> computeBinaryOp(op: String, l: KNum.Result<T>, r: KNum.Result<T>): KNum.Result<T> {
        val leftBuffer = l.getData() as FloatBuffer
        val rightBuffer = r.getData() as FloatBuffer
        val num = l.numElements

        fun getL_multi(n: Int) = leftBuffer[n]
        fun getR_single(n: Int) = rightBuffer[0]
        fun getR_multi(n: Int) = rightBuffer[n]

        val getL = ::getL_multi
        val getR = if (r.dims.size == 1 && r.dims[0] == 1) ::getR_single else ::getR_multi

        val fop = when (op) {
            "add" -> ::fadd
            "sub" -> ::fsub
            "mul" -> ::fmul
            "div" -> ::fdiv
            "max" -> ::fmax
            "min" -> ::fmin
            else -> TODO("Unsuported operation $op")
        }

        return DefaultResult(l.dims, l.type, FloatBuffer.allocate(l.numElements).apply {
            for (n in 0 until num) put(n, fop(getL(n), getR(n)))
        })
    }
}

class KNum(val ctx: KNumContext) {
    enum class Type(val size: Int) { INT(4), FLOAT(4) }

    abstract class Tensor<T>(val dims: IntArray, val type: Type) {
        val numElements: Int by lazy { dims.reduce { acc, i -> acc * i } }
        override fun toString(): String = "Tensor[$type](${dims.joinToString(", ")})"
    }

    abstract class Result<T>(dims: IntArray, type: Type) : Tensor<T>(dims, type) {
        abstract fun getData(): Buffer

        fun getFloatBuffer(): FloatBuffer = getData() as FloatBuffer
        fun getFloatArray(): FloatArray = getFloatBuffer().run {
            val out = FloatArray(limit())
            position(0)
            get(out)
            out
        }
    }

    class Operation<T>(val op: String, type: Type, dims: IntArray, val inputs: Array<Tensor<*>>) : Tensor<T>(dims, type) {
        override fun toString(): String = "Operation($op[$type], ${dims.toList()})(${inputs.toList()})"
    }

    class Constant<T>(dims: IntArray, type: Type, val data: Buffer) : Tensor<T>(dims, type) {
        init {
            if (numElements != data.limit()) {
                throw IllegalArgumentException("${dims.toList()}")
            }
        }
    }

    val FloatArray.const: Constant<Float> get() = Constant(intArrayOf(this.size), Type.FLOAT, FloatBuffer.wrap(this))
    val Float.const: Constant<Float> get() = Constant(intArrayOf(1), Type.FLOAT, FloatBuffer.wrap(floatArrayOf(this)))
    val <T> T.const: Constant<T>
        get() = when (this) {
            is Float -> this.const as Constant<T>
            is FloatArray -> this.const as Constant<T>
            else -> throw IllegalArgumentException("Unsupported $this")
        }

    fun <T> max(l: Tensor<T>, r: Tensor<T>): Tensor<T> = Operation<T>("max", l.type, l.dims, arrayOf(l, r))
    fun <T> min(l: Tensor<T>, r: Tensor<T>): Tensor<T> = Operation<T>("min", l.type, l.dims, arrayOf(l, r))

    fun <T> Tensor<T>.clamp(min: Tensor<T>, max: Tensor<T>): Tensor<T> = min(max(this, min), max)
    fun <T> Tensor<T>.clamp(min: T, max: T): Tensor<T> = min(max(this, min.const), max.const)

    operator fun <T> Tensor<T>.unaryMinus(): Tensor<T> = Operation<T>("neg", this.type, this.dims, arrayOf(this))

    operator fun <T> Tensor<T>.times(that: Tensor<T>): Tensor<T> = Operation<T>("mul", this.type, this.dims, arrayOf(this, that))
    operator fun <T> Tensor<T>.div(that: Tensor<T>): Tensor<T> = Operation<T>("div", this.type, this.dims, arrayOf(this, that))
    operator fun <T> Tensor<T>.plus(that: Tensor<T>): Tensor<T> = Operation<T>("add", this.type, this.dims, arrayOf(this, that))
    operator fun <T> Tensor<T>.minus(that: Tensor<T>): Tensor<T> = Operation<T>("sub", this.type, this.dims, arrayOf(this, that))
    operator fun <T> Tensor<T>.times(that: T): Tensor<T> = Operation<T>("mul", this.type, this.dims, arrayOf(this, that.const))
    operator fun <T> Tensor<T>.div(that: T): Tensor<T> = Operation<T>("div", this.type, this.dims, arrayOf(this, that.const))
    operator fun <T> Tensor<T>.plus(that: T): Tensor<T> = Operation<T>("add", this.type, this.dims, arrayOf(this, that.const))
    operator fun <T> Tensor<T>.minus(that: T): Tensor<T> = Operation<T>("sub", this.type, this.dims, arrayOf(this, that.const))

    fun <T> Tensor<T>.reshape(vararg dims: Int): Tensor<T> = Operation<T>("reshape", this.type, dims, arrayOf(this))
    fun <T> Tensor<T>.transpose(vararg axis: Int): Tensor<T> = Operation<T>("transpose", this.type, axis.map { this.dims[it] }.toIntArray(), arrayOf(this))

    fun <T> Tensor<T>.compute(): Result<T> = ctx.compute(this)
}

fun <T> KNum(context: KNumContext = KNumContext(), callback: KNum.() -> T): T {
    return context.session {
        callback(KNum(context))
    }
}