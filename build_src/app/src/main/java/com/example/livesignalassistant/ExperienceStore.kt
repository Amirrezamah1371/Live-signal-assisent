package com.example.livesignalassistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Permanent Experience V3 — compact hierarchical Beta-Binomial statistics.
 *
 * What it stores: win/loss weights per context cell. The key space is FINITE
 * (action x state x regime x entry-band = 2x5x3x3), so the file can never grow beyond ~160 cells (~15 KB).
 * No screenshots, no raw observations.
 *
 * How it learns: only WIN/LOSS train it (VOID never). Existing cells of the same action decay slightly
 * (x0.995) before each update so old knowledge fades slowly, never instantly.
 *
 * How it is used: advise() returns a posterior for the cell that matches the current decision.
 * Each level is shrunk toward its parent (strength 10), so a handful of trades barely moves anything.
 * Experience can only (a) leave the decision untouched, (b) lower/raise decision STRENGTH, or
 * (c) turn a signal into WAIT when the cell has >= 10 effective trades and its 80% upper bound is below 50%.
 * It never flips UP<->DOWN.
 */
class ExperienceStore(private val ctx: Context) {
    data class Advice(
        val action: String,          // NONE | BOOST | DAMP | AVOID
        val strengthDelta: Double,
        val nEff: Double,            // effective trades in the matched cell
        val posterior: Double,       // shrunk win-rate estimate of the matched cell
        val lb80: Double, val ub80: Double,
        val level: Int,              // 0..3 deepest level with data
        val key: String,
        val note: String,
        val altPosterior: Double     // same estimate for the opposite direction (logging only)
    )

    private val dir = File(ctx.filesDir, "permanent_experience")
    private val file = File(dir, "experience_v3.json")
    private val bak = File(dir, "experience_v3.bak")
    private val lock = Any()
    private val priorStrength = 10.0
    private val decayPerTrade = 0.995
    private val minEffForEffect = 10.0
    @Volatile var loadNote: String = "OK"
        private set

    companion object {
        val STATES = listOf("CONTINUING", "PULLBACK", "EXHAUSTION", "REGIME_CHANGE", "NOISE", "NO_RECENT")
        fun entryBand(entryQ: Double) = if (entryQ < 50.0) "E_LOW" else if (entryQ < 70.0) "E_MID" else "E_HIGH"
        fun regimeBand(trend: Double) = if (trend >= 0.6) "TREND" else if (trend <= 0.3) "RANGE" else "MIXED"
    }

    private fun fresh(): JSONObject = JSONObject().put("version", 3).put("total_results", 0)
        .put("cells", JSONArray()).put("recent", "")

    private fun parse(f: File): JSONObject? = try {
        val o = JSONObject(f.readText())
        if (o.optInt("version", 0) == 3 && o.optJSONArray("cells") != null) o else null
    } catch (_: Throwable) { null }

    private fun load(): JSONObject {
        dir.mkdirs()
        if (!file.exists() && !bak.exists()) { loadNote = "NEW"; return fresh() }
        parse(file)?.let { loadNote = "OK"; return it }
        parse(bak)?.let { loadNote = "RESTORED_FROM_BACKUP"; return it }
        // Never overwrite an unreadable store silently: keep it for inspection.
        try { if (file.exists()) file.renameTo(File(dir, "experience_v3.corrupt-" + System.currentTimeMillis())) } catch (_: Throwable) {}
        loadNote = "CORRUPT_STARTED_FRESH"
        return fresh()
    }

    private fun save(o: JSONObject) {
        dir.mkdirs()
        val tmp = File(dir, "experience_v3.tmp")
        tmp.writeText(o.toString())
        if (file.exists()) { try { file.copyTo(bak, overwrite = true) } catch (_: Throwable) {} }
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    private fun cellMap(o: JSONObject): MutableMap<String, DoubleArray> {
        val m = HashMap<String, DoubleArray>()
        val a = o.optJSONArray("cells") ?: return m
        for (i in 0 until a.length()) {
            val c = a.optJSONObject(i) ?: continue
            m[c.optString("k")] = doubleArrayOf(c.optDouble("w", 0.0), c.optDouble("l", 0.0))
        }
        return m
    }

    private fun levelKeys(action: String, state: String, regime: String, band: String) = listOf(
        action, "$action|$state", "$action|$state|$regime", "$action|$state|$regime|$band"
    )

    private fun chain(m: Map<String, DoubleArray>, keys: List<String>): Triple<Double, Double, Int> {
        // Hierarchical shrinkage WITHOUT double counting: each level's prior is the parent's rate
        // computed from the parent's data EXCLUDING the child's own trades.
        val c = Array(4) { m[keys[it]] ?: doubleArrayOf(0.0, 0.0) }
        val w0 = (c[0][0] - c[1][0]).coerceAtLeast(0.0)
        val l0 = (c[0][1] - c[1][1]).coerceAtLeast(0.0)
        var prior = (w0 + priorStrength) / (w0 + l0 + 2.0 * priorStrength)
        var alpha = priorStrength * prior
        var beta = priorStrength * (1.0 - prior)
        var deepest = if (c[0][0] + c[0][1] > 0) 0 else -1
        for (lv in 1..3) {
            val own = c[lv]
            alpha = own[0] + priorStrength * prior
            beta = own[1] + priorStrength * (1.0 - prior)
            if (own[0] + own[1] > 0) deepest = lv
            if (lv < 3) {
                val child = c[lv + 1]
                val rw = (own[0] - child[0]).coerceAtLeast(0.0)
                val rl = (own[1] - child[1]).coerceAtLeast(0.0)
                prior = (rw + priorStrength * prior) / (rw + rl + priorStrength)
            }
        }
        return Triple(alpha, beta, deepest)
    }

    fun advise(action: String, state: String, regime: String, band: String): Advice = synchronized(lock) {
        if (action != "UP" && action != "DOWN")
            return@synchronized Advice("NONE", 0.0, 0.0, 0.5, 0.0, 1.0, -1, "", "NO_SIDE", 0.5)
        val m = cellMap(load())
        val keys = levelKeys(action, state, regime, band)
        val (alpha, beta, deepest) = chain(m, keys)
        val cell = m[keys[3]] ?: doubleArrayOf(0.0, 0.0)
        val nEff = cell[0] + cell[1]
        val p = alpha / (alpha + beta)
        val sd = sqrt(p * (1.0 - p) / (alpha + beta + 1.0))
        val lb = p - 1.2816 * sd; val ub = p + 1.2816 * sd
        val other = if (action == "UP") "DOWN" else "UP"
        val (a2, b2, _) = chain(m, levelKeys(other, state, regime, band))
        val alt = a2 / (a2 + b2)
        val key = keys[3]
        if (nEff < minEffForEffect)
            return@synchronized Advice("NONE", 0.0, nEff, p, lb, ub, deepest, key, "SMALL_SAMPLE", alt)
        if (ub < 0.50)
            return@synchronized Advice("AVOID", -100.0, nEff, p, lb, ub, deepest, key, "UPPER80_BELOW_50", alt)
        if (ub < 0.55 && nEff >= 20.0)
            return@synchronized Advice("DAMP", -((0.55 - p) * 100.0).coerceIn(3.0, 15.0), nEff, p, lb, ub, deepest, key, "UPPER80_BELOW_55", alt)
        if (lb > 0.55)
            return@synchronized Advice("BOOST", min(8.0, (p - 0.55) * 80.0).coerceAtLeast(1.0), nEff, p, lb, ub, deepest, key, "LOWER80_ABOVE_55", alt)
        Advice("NONE", 0.0, nEff, p, lb, ub, deepest, key, "NEUTRAL", alt)
    }

    /** Only WIN/LOSS train. Returns (lifetime results, number of cells). */
    fun learn(action: String, state: String, regime: String, band: String, result: String): Pair<Int, Int> = synchronized(lock) {
        if ((action != "UP" && action != "DOWN") || (result != "WIN" && result != "LOSS")) return@synchronized stats()
        val o = load()
        val m = cellMap(o)
        // slow forgetting for this action only
        for ((k, v) in m) if (k == action || k.startsWith("$action|")) { v[0] *= decayPerTrade; v[1] *= decayPerTrade }
        for (k in levelKeys(action, state, regime, band)) {
            val c = m.getOrPut(k) { doubleArrayOf(0.0, 0.0) }
            if (result == "WIN") c[0] += 1.0 else c[1] += 1.0
        }
        val arr = JSONArray()
        for ((k, v) in m.entries.sortedBy { it.key }) {
            if (v[0] + v[1] < 0.02) continue
            arr.put(JSONObject().put("k", k).put("w", Math.round(v[0] * 1000.0) / 1000.0).put("l", Math.round(v[1] * 1000.0) / 1000.0))
        }
        o.put("cells", arr).put("total_results", o.optInt("total_results", 0) + 1)
        val rec = (o.optString("recent", "") + (if (result == "WIN") "W" else "L")).takeLast(40)
        o.put("recent", rec)
        save(o)
        stats()
    }

    fun stats(): Pair<Int, Int> {
        val o = load()
        return o.optInt("total_results", 0) to (o.optJSONArray("cells")?.length() ?: 0)
    }

    /** lifetime win rate vs the last (up to) 40 results — drift monitor. null if no data. */
    fun driftSummary(): String = synchronized(lock) {
        val o = load()
        val cells = cellMap(o)
        val tw = (cells["UP"]?.get(0) ?: 0.0) + (cells["DOWN"]?.get(0) ?: 0.0)
        val tl = (cells["UP"]?.get(1) ?: 0.0) + (cells["DOWN"]?.get(1) ?: 0.0)
        val rec = o.optString("recent", "")
        val rw = rec.count { it == 'W' }
        val life = if (tw + tl > 0) "%.0f%%".format(100.0 * tw / (tw + tl)) else "--"
        val last = if (rec.isNotEmpty()) "%d/%d".format(rw, rec.length) else "--"
        "lifetime ${o.optInt("total_results", 0)} trades · weighted WR $life · last ${rec.length}: $last wins · store: $loadNote"
    }

    fun snapshotFile(): File? = synchronized(lock) { if (file.exists()) file else null }

    fun exportText(): String = synchronized(lock) { load().toString(1) }

    /** Import a backup. Replaces the current store only if the backup has at least as many lifetime results. */
    fun importText(text: String): String = synchronized(lock) {
        val inc = try { JSONObject(text) } catch (_: Throwable) { return@synchronized "Import rejected: not valid JSON" }
        if (inc.optInt("version", 0) != 3 || inc.optJSONArray("cells") == null) return@synchronized "Import rejected: not an Experience V3 file"
        val cur = load()
        if (inc.optInt("total_results", 0) < cur.optInt("total_results", 0))
            return@synchronized "Import rejected: backup has fewer results (${inc.optInt("total_results", 0)}) than current (${cur.optInt("total_results", 0)})"
        save(inc)
        "Experience restored: ${inc.optInt("total_results", 0)} results"
    }
}
