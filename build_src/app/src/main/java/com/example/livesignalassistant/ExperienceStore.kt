package com.example.livesignalassistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Permanent Experience V3 — compact hierarchical Beta-Binomial statistics.
 *
 * What it stores: win/loss weights per context cell. The key space is finite.
 * Version stays 3 so an existing experience_v3.json still loads. Optional keys
 * (applied_signal_ids, untrained_results) are ignored by older readers that only
 * check version and cells.
 *
 * How it learns: only WIN/LOSS train it, and only when the decision had a real
 * recent-change state. NO_RECENT and VOID are written to the audit log and do not
 * move the weights. Existing cells of the same action decay slightly (x0.995)
 * before each trained update.
 *
 * How it is used: advise() can leave a decision alone, lower STRENGTH, or turn it
 * into WAIT. It does not raise STRENGTH and it never flips UP to DOWN.
 */
class ExperienceStore(private val ctx: Context) : ExperienceSource {
    data class Advice(
        val action: String,          // NONE | DAMP | AVOID
        val strengthDelta: Double,
        val nEff: Double,
        val posterior: Double,
        val lb80: Double, val ub80: Double,
        val level: Int,
        val key: String,
        val note: String,
        val altPosterior: Double
    )

    private val dir = File(ctx.filesDir, "permanent_experience")
    private val file = File(dir, "experience_v3.json")
    private val bak = File(dir, "experience_v3.bak")
    private val audit = File(dir, "experience_audit.jsonl")
    private val priorStrength = 10.0
    private val decayPerTrade = 0.995
    @Volatile var loadNote: String = "OK"
        private set

    companion object {
        private val fileLock = Any()
        val STATES = listOf("CONTINUING", "PULLBACK", "EXHAUSTION", "REGIME_CHANGE", "NOISE", "NO_RECENT")
        fun entryBand(entryQ: Double) = if (entryQ < 50.0) "E_LOW" else if (entryQ < 70.0) "E_MID" else "E_HIGH"
        fun regimeBand(trend: Double) = if (trend >= 0.6) "TREND" else if (trend <= 0.3) "RANGE" else "MIXED"
    }

    private fun fresh(): JSONObject = JSONObject().put("version", 3).put("total_results", 0)
        .put("untrained_results", 0).put("cells", JSONArray()).put("recent", "")
        .put("applied_signal_ids", JSONArray())

    private fun parse(f: File): JSONObject? = try {
        val o = JSONObject(f.readText())
        if (o.optInt("version", 0) == 3 && o.optJSONArray("cells") != null) o else null
    } catch (_: Throwable) { null }

    private fun load(): JSONObject {
        dir.mkdirs()
        if (!file.exists() && !bak.exists()) { loadNote = "NEW"; return fresh() }
        parse(file)?.let { loadNote = "OK"; return it }
        parse(bak)?.let { loadNote = "RESTORED_FROM_BACKUP"; return it }
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

    private fun appliedIds(o: JSONObject): MutableList<String> {
        val out = ArrayList<String>()
        val a = o.optJSONArray("applied_signal_ids") ?: return out
        for (i in 0 until a.length()) out.add(a.optString(i))
        return out
    }

    /** @return true if this signal was already recorded */
    private fun markApplied(o: JSONObject, signalId: String): Boolean {
        if (signalId.isEmpty()) return false
        val ids = appliedIds(o)
        if (ExperienceMath.alreadyApplied(ids, signalId)) return true
        ids.add(signalId)
        while (ids.size > 500) ids.removeAt(0)
        val arr = JSONArray()
        for (id in ids) arr.put(id)
        o.put("applied_signal_ids", arr)
        return false
    }

    private fun writeAudit(
        signalId: String, action: String, state: String, regime: String, band: String,
        result: String, trained: Boolean, extra: Map<String, String>
    ) {
        dir.mkdirs()
        val line = ExperienceMath.auditJson(
            System.currentTimeMillis(), signalId, action, state, regime, band, result, trained, extra
        )
        audit.appendText(line + "\n")
    }

    override fun advise(action: String, state: String, regime: String, band: String): Advice = synchronized(fileLock) {
        ExperienceMath.advise(cellMap(load()), action, state, regime, band)
    }

    /**
     * WIN/LOSS entry point. NO_RECENT outcomes are audited and not trained.
     * A repeated signalId is ignored so a recovered button cannot double-count.
     */
    fun learn(
        action: String, state: String, regime: String, band: String, result: String,
        signalId: String = "", extra: Map<String, String> = emptyMap()
    ): Pair<Int, Int> = synchronized(fileLock) {
        if ((action != "UP" && action != "DOWN") || (result != "WIN" && result != "LOSS")) return@synchronized statsLocked()
        val o = load()
        if (markApplied(o, signalId)) {
            save(o)
            return@synchronized statsLocked()
        }
        val train = ExperienceMath.trainsWeights(state)
        writeAudit(signalId, action, state, regime, band, result, train, extra)
        if (!train) {
            o.put("untrained_results", o.optInt("untrained_results", 0) + 1)
            save(o)
            return@synchronized statsLocked()
        }
        val m = cellMap(o)
        for ((k, v) in m) if (k == action || k.startsWith("$action|")) { v[0] *= decayPerTrade; v[1] *= decayPerTrade }
        for (k in ExperienceMath.levelKeys(action, state, regime, band)) {
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
        statsLocked()
    }

    /** VOID and any other labeled outcome that must not train. Still idempotent per signalId. */
    fun recordUntrained(
        signalId: String, result: String, action: String, state: String, regime: String, band: String,
        extra: Map<String, String> = emptyMap()
    ) = synchronized(fileLock) {
        val o = load()
        if (markApplied(o, signalId)) { save(o); return@synchronized }
        writeAudit(signalId, action, state, regime, band, result, false, extra)
        o.put("untrained_results", o.optInt("untrained_results", 0) + 1)
        save(o)
    }

    fun stats(): Pair<Int, Int> = synchronized(fileLock) { statsLocked() }

    private fun statsLocked(): Pair<Int, Int> {
        val o = load()
        return o.optInt("total_results", 0) to (o.optJSONArray("cells")?.length() ?: 0)
    }

    /** lifetime weighted win rate vs the last (up to) 40 trained results. */
    fun driftSummary(): String = synchronized(fileLock) {
        val o = load()
        val cells = cellMap(o)
        val tw = (cells["UP"]?.get(0) ?: 0.0) + (cells["DOWN"]?.get(0) ?: 0.0)
        val tl = (cells["UP"]?.get(1) ?: 0.0) + (cells["DOWN"]?.get(1) ?: 0.0)
        val rec = o.optString("recent", "")
        val rw = rec.count { it == 'W' }
        val life = if (tw + tl > 0) "%.0f%%".format(100.0 * tw / (tw + tl)) else "--"
        val last = if (rec.isNotEmpty()) "%d/%d".format(rw, rec.length) else "--"
        val untrained = o.optInt("untrained_results", 0)
        "lifetime ${o.optInt("total_results", 0)} trained · $untrained untrained · weighted WR $life · last ${rec.length}: $last wins · store: $loadNote"
    }

    fun snapshotFile(): File? = synchronized(fileLock) { if (file.exists()) file else null }

    /** Raw bytes, so an export does not parse or repair the live file. */
    fun snapshotBytes(): ByteArray? = synchronized(fileLock) { if (file.exists()) file.readBytes() else null }

    fun auditSnapshot(): ByteArray? = synchronized(fileLock) { if (audit.exists()) audit.readBytes() else null }

    fun exportText(): String = synchronized(fileLock) { load().toString(1) }

    /** Import a backup. Replaces the current store only if the backup has at least as many lifetime results. */
    fun importText(text: String): String = synchronized(fileLock) {
        val inc = try { JSONObject(text) } catch (_: Throwable) { return@synchronized "Import rejected: not valid JSON" }
        if (inc.optInt("version", 0) != 3 || inc.optJSONArray("cells") == null) return@synchronized "Import rejected: not an Experience V3 file"
        val cur = load()
        if (inc.optInt("total_results", 0) < cur.optInt("total_results", 0))
            return@synchronized "Import rejected: backup has fewer results (${inc.optInt("total_results", 0)}) than current (${cur.optInt("total_results", 0)})"
        val union = LinkedHashSet<String>()
        fun collect(a: JSONArray?) { if (a == null) return; for (i in 0 until a.length()) union.add(a.optString(i)) }
        collect(cur.optJSONArray("applied_signal_ids"))
        collect(inc.optJSONArray("applied_signal_ids"))
        val arr = JSONArray()
        for (id in union) if (id.isNotEmpty()) arr.put(id)
        inc.put("applied_signal_ids", arr)
        if (!inc.has("untrained_results")) inc.put("untrained_results", cur.optInt("untrained_results", 0))
        save(inc)
        "Experience restored: ${inc.optInt("total_results", 0)} results"
    }
}
