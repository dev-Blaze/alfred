package com.yshah.alfred.network

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.json.JSONObject
import java.nio.LongBuffer
import java.text.Normalizer
import java.util.Locale
import kotlin.math.exp
import kotlin.math.sqrt

/** Advisory only. The seed classifier is not calibrated and never authorizes actions. */
class IntentClassifier(context: Context) {
    private val assets = context.applicationContext.assets
    private val environment = OrtEnvironment.getEnvironment()
    private val vocabulary by lazy {
        assets.open("intent/vocab.txt").bufferedReader().useLines { lines ->
            lines.withIndex().associate { it.value to it.index.toLong() }
        }
    }
    private val head by lazy { JSONObject(assets.open("intent/intent-head.json").bufferedReader().use { it.readText() }) }
    private val session by lazy {
        OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(2)
            environment.createSession(assets.open("intent/intent-encoder.onnx").use { it.readBytes() }, options)
        }
    }

    @Synchronized
    fun classify(text: String, isFollowUp: Boolean): String {
        if (isFollowUp || text.isBlank() || text.length > 2000) return "uncertain"
        return try {
            val normalized = Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "")
            val ids = mutableListOf(101L)
            for (word in Regex("[\\p{L}\\p{N}]+|[^\\s\\p{L}\\p{N}]").findAll(normalized).map { it.value }) {
                val pieces = mutableListOf<Long>()
                var start = 0
                while (start < word.length) {
                    var end = word.length
                    var found: Long? = null
                    while (end > start) {
                        found = vocabulary[(if (start == 0) "" else "##") + word.substring(start, end)]
                        if (found != null) break
                        end--
                    }
                    if (found == null) { pieces.clear(); pieces += 100L; break }
                    pieces += found
                    start = end
                }
                ids += pieces
                if (ids.size > 127) return "uncertain" // Never classify a silently truncated request.
            }
            ids += 102L
            val shape = longArrayOf(1, ids.size.toLong())
            OnnxTensor.createTensor(environment, LongBuffer.wrap(ids.toLongArray()), shape).use { tokens ->
                OnnxTensor.createTensor(environment, LongBuffer.wrap(LongArray(ids.size) { 1 }), shape).use { mask ->
                    OnnxTensor.createTensor(environment, LongBuffer.wrap(LongArray(ids.size)), shape).use { types ->
                        session.run(mapOf("input_ids" to tokens, "attention_mask" to mask, "token_type_ids" to types)).use { result ->
                            @Suppress("UNCHECKED_CAST")
                            val vectors = (result[0].value as Array<Array<FloatArray>>)[0]
                            val pooled = DoubleArray(384) { j -> vectors.sumOf { it[j].toDouble() } / vectors.size }
                            val norm = sqrt(pooled.sumOf { it * it }).coerceAtLeast(1e-12)
                            val weights = head.getJSONArray("weights")
                            val bias = head.getJSONArray("bias")
                            val scores = DoubleArray(weights.length()) { i ->
                                bias.getDouble(i) + pooled.indices.sumOf { j -> weights.getJSONArray(i).getDouble(j) * pooled[j] / norm }
                            }
                            val max = scores.max()
                            val probabilities = scores.map { exp(it - max) }
                            val best = scores.indices.maxBy { scores[it] }
                            if (probabilities[best] / probabilities.sum() < head.getDouble("threshold")) "uncertain"
                            else head.getJSONArray("labels").getString(best)
                        }
                    }
                }
            }
        } catch (_: Exception) { "uncertain" }
    }
}
