package com.jonathan.multitool.core.drone

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.exp

/** Loads model.json (StandardScaler + 1-hidden-layer MLP) from assets and runs
 *  the exact same forward pass as the Python reference:
 *    z = (x - mean) / scale ;  h = relu(W1 z + b1) ;  p = sigmoid(W2 h + b2) */
class DroneModel private constructor(
    private val mean: FloatArray, private val scale: FloatArray,
    private val w1: Array<FloatArray>, private val b1: FloatArray,
    private val w2: Array<FloatArray>, private val b2: FloatArray,
    val threshold: Float
) {
    val nIn get() = mean.size
    val nHid get() = b1.size

    fun predict(feat: FloatArray): Float {
        val z = FloatArray(nIn) { (feat[it] - mean[it]) / scale[it] }
        val h = FloatArray(nHid)
        for (j in 0 until nHid) {
            var acc = b1[j]; val row = w1[j]
            for (k in 0 until nIn) acc += row[k] * z[k]
            h[j] = if (acc > 0f) acc else 0f
        }
        var o = b2[0]
        val row = w2[0]
        for (j in 0 until nHid) o += row[j] * h[j]
        return 1f / (1f + exp(-o))
    }

    companion object {
        fun fromAssets(ctx: Context, name: String = "model.json"): DroneModel {
            val text = ctx.assets.open(name).bufferedReader().use { it.readText() }
            val o = JSONObject(text)
            fun vec(a: JSONArray) = FloatArray(a.length()) { a.getDouble(it).toFloat() }
            fun mat(a: JSONArray) = Array(a.length()) { vec(a.getJSONArray(it)) }
            return DroneModel(
                vec(o.getJSONArray("scaler_mean")),
                vec(o.getJSONArray("scaler_scale")),
                mat(o.getJSONArray("W1")), vec(o.getJSONArray("b1")),
                mat(o.getJSONArray("W2")), vec(o.getJSONArray("b2")),
                o.getDouble("threshold").toFloat()
            )
        }
    }
}
