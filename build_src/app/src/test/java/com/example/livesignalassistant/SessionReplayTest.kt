package com.example.livesignalassistant

import org.junit.Test
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream

/**
 * Replays the October 8 phone telemetry through the repaired engine when the tape is present.
 * The tape is produced by validation/extract_replay.py and is not part of the APK.
 */
class SessionReplayTest {
    @Test
    fun replayOctober8Tape() {
        val tape = File("/tmp/lsa_replay.bin")
        if (!tape.exists()) return
        val report = StringBuilder()
        DataInputStream(FileInputStream(tape)).use { inn ->
            val ce = ChangeEngine()
            var active = ce
            val cycleObs = HashMap<Int, ArrayList<Obs>>()
            var session = ""
            var holds = 0
            var accepts = 0
            var invalidated = 0
            var seeds = 0
            var promoted = 0
            var updates = 0
            var newWait = 0
            var newPub = 0
            var newRecent = 0
            var oldRecent = 0
            var decisions = 0
            var trustedAtDecision = 0
            val changed = ArrayList<String>()
            val same = ArrayList<String>()
            var openSignal: Pair<String, String>? = null
            val results = HashMap<String, String>()
            val published = ArrayList<String>()
            fun flushSignal(result: String, direction: String) {
                val sig = openSignal
                if (sig != null) results[sig.first] = result
                openSignal = null
                if (direction.isNotEmpty()) published += "$direction $result"
            }
            while (inn.available() > 0) {
                when (inn.readUnsignedByte()) {
                    3 -> {
                        session = readStr(inn)
                        active = ChangeEngine()
                        cycleObs.clear()
                        report.append("SESSION ").append(session).append('\n')
                    }
                    1 -> {
                        val cycle = inn.readInt()
                        val elapsed = inn.readLong()
                        val update = inn.readUnsignedByte() == 1
                        if (update) {
                            val t = inn.readDouble()
                            val n = inn.readUnsignedShort()
                            val path = DoubleArray(n) { -inn.readUnsignedShort().toDouble() }
                            val real = BooleanArray(n) { inn.readUnsignedByte() == 1 }
                            val info = active.update(t, path, real)
                            updates++
                            when (info["reg_reason"]) {
                                "ACCEPTED" -> accepts++
                                "PROMOTED_CANDIDATE" -> promoted++
                                else -> {
                                    val why = info["reg_reason"] as String
                                    if (why.startsWith("HELD_") || why == "CANDIDATE_ACCEPTED") holds++
                                    else if (why.startsWith("INVALIDATED_")) invalidated++
                                    else if (why.startsWith("SEED_")) seeds++
                                }
                            }
                        }
                        val dirCode = inn.readByte().toInt()
                        val side = inn.readInt()
                        val entry = inn.readInt()
                        val conflict = inn.readInt()
                        val tq = inn.readDouble()
                        val exh = inn.readDouble()
                        val cont = inn.readDouble()
                        val trend = inn.readDouble()
                        val vol = inn.readDouble()
                        val conflictValue = inn.readDouble()
                        var reason = readStr(inn)
                        var direction = when (dirCode) { 1 -> "UP"; 2 -> "DOWN"; else -> "WAIT" }
                        val structural = reason == "BRK" || reason == "FAIL_BRK" || reason == "TURN" || reason == "REV"
                        if (structural && exh >= 0.62 && cont >= 0.34) {
                            direction = "WAIT"; reason = "LATE_ENTRY_RISK"
                        } else if (structural && vol >= 2.20 && conflictValue > 35.0) {
                            direction = "WAIT"; reason = "SHOCK_UNRESOLVED"
                        }
                        val obs = Obs(elapsed, SignalResult(
                            direction, 60, 0, entry, conflict, reason,
                            diagnostics = mapOf("exhaustion" to exh, "continuation" to cont, "trend_regime" to trend, "vol_expansion" to vol),
                            side = side, traceQuality = tq
                        ))
                        cycleObs.getOrPut(cycle) { ArrayList() }.add(obs)
                    }
                    2 -> {
                        val cycle = inn.readInt()
                        val tNow = inn.readDouble()
                        val oldRecentFlag = inn.readUnsignedByte() == 1
                        val oldDir = readStr(inn)
                        val oldReason = readStr(inn)
                        readStr(inn)
                        val obs = cycleObs[cycle] ?: emptyList()
                        val result = CycleDecider.decide(obs, 90000L, active, tNow, none(), 0L)
                        decisions++
                        if (oldRecentFlag) oldRecent++
                        val recent = result.diagnostics["recent_valid"] == true
                        if (recent) newRecent++
                        if (result.diagnostics["reg_trusted"] == true) trustedAtDecision++
                        if (result.direction == "WAIT") newWait++ else newPub++
                        val d = result.diagnostics
                        val line = "c$cycle old=$oldDir/$oldReason new=${result.direction}/${result.reason} recent=$recent why=${d["recent_invalid_reason"]} trusted=${d["reg_trusted"]} safety=${d["entry_safety"]} exh=${d["safety_exhaustion"]} imp=${d["impulse_z"]} vr=${d["velocity_ratio"]} since=${d["since_peak_s"]} z3=${d["slope_3s"]} z6=${d["slope_6s"]} z10=${d["slope_10s"]} state=${d["final_state"]} refuse=${d["refuse_weight"]} direct=${d["direct_weight"]} pts=${d["series_points"]} span=${d["series_span_s"]}"
                        if (oldDir != result.direction || oldReason != result.reason) changed += line else same += oldDir
                        if (result.direction != "WAIT") {
                            openSignal = Pair("c$cycle", result.direction)
                        }
                        report.append(line).append('\n')
                    }
                    4 -> { inn.readInt(); readStr(inn) }
                    5 -> {
                        inn.readInt()
                        val id = readStr(inn)
                        val dir = readStr(inn)
                        openSignal = Pair(id, dir)
                    }
                    6 -> {
                        val id = readStr(inn)
                        val res = readStr(inn)
                        val dir = readStr(inn)
                        results["$session|$id"] = "$dir $res"
                        report.append("RESULT ").append(dir).append(' ').append(res).append('\n')
                    }
                    else -> error("bad tape tag")
                }
            }
            report.append("UPDATES ").append(updates)
                .append(" ACCEPT ").append(accepts)
                .append(" HOLD ").append(holds)
                .append(" PROMOTED ").append(promoted)
                .append(" INVALIDATED ").append(invalidated)
                .append(" SEED ").append(seeds).append('\n')
            report.append("DECISIONS ").append(decisions)
                .append(" OLD_RECENT ").append(oldRecent)
                .append(" NEW_RECENT ").append(newRecent)
                .append(" NEW_TRUSTED ").append(trustedAtDecision)
                .append(" NEW_WAIT ").append(newWait)
                .append(" NEW_PUB ").append(newPub).append('\n')
            report.append("CHANGED ").append(changed.size).append('\n')
            report.append("RESULTS ").append(results).append('\n')
        }
        File("/opt/cursor/artifacts").mkdirs()
        File("/opt/cursor/artifacts/replay_oct8.txt").writeText(report.toString())
        println(report.toString())
    }

    private fun readStr(inn: DataInputStream): String {
        val n = inn.readUnsignedShort()
        val b = ByteArray(n)
        inn.readFully(b)
        return String(b, Charsets.UTF_8)
    }

    private fun none() = object : ExperienceSource {
        override fun advise(action: String, state: String, regime: String, band: String) =
            ExperienceStore.Advice("NONE", 0.0, 0.0, 0.5, 0.0, 1.0, -1, "", "REPLAY", 0.5)
    }
}
