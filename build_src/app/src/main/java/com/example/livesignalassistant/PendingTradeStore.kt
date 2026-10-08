package com.example.livesignalassistant

import java.io.File

/**
 * Executed signals whose WIN/LOSS/VOID has not been recorded yet.
 * The overlay buttons live only in RAM; this file is what survives process death.
 * Stored beside Experience, so clearing session screenshots does not drop an open label.
 */
class PendingTradeStore(private val dir: File) {
    data class Pending(
        val signalId: String,
        val direction: String,
        val state: String,
        val regime: String,
        val band: String,
        val score: Int,
        val fields: Map<String, String> = emptyMap()
    )

    private val file = File(dir, "pending_trades.tsv")

    fun all(): List<Pending> = synchronized(lock) { read() }

    fun upsert(p: Pending) = synchronized(lock) {
        val rows = read().filter { it.signalId != p.signalId }.toMutableList()
        rows.add(p)
        write(rows)
    }

    fun remove(signalId: String) = synchronized(lock) {
        write(read().filter { it.signalId != signalId })
    }

    fun snapshotBytes(): ByteArray? = synchronized(lock) { if (file.exists()) file.readBytes() else null }

    private fun read(): List<Pending> {
        if (!file.exists()) return emptyList()
        return file.readLines().mapNotNull { decode(it) }
    }

    private fun write(rows: List<Pending>) {
        dir.mkdirs()
        val tmp = File(dir, "pending_trades.tmp")
        tmp.writeText(rows.joinToString("\n") { encode(it) } + if (rows.isEmpty()) "" else "\n")
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    companion object {
        private val lock = Any()

        fun encode(p: Pending): String {
            val extra = p.fields.entries
                .filter { it.key.isNotEmpty() && !it.key.contains('=') && !it.key.contains(';') }
                .joinToString(";") { "${it.key}=${it.value.replace('\t', ' ').replace('\n', ' ').replace(';', ',')}" }
            return listOf(p.signalId, p.direction, p.state, p.regime, p.band, p.score.toString(), extra).joinToString("\t")
        }

        fun decode(line: String): Pending? {
            if (line.isBlank()) return null
            val parts = line.split('\t')
            if (parts.size < 6) return null
            val fields = LinkedHashMap<String, String>()
            if (parts.size >= 7 && parts[6].isNotEmpty()) {
                for (kv in parts[6].split(';')) {
                    val i = kv.indexOf('=')
                    if (i > 0) fields[kv.substring(0, i)] = kv.substring(i + 1)
                }
            }
            return Pending(
                parts[0], parts[1], parts[2], parts[3], parts[4],
                parts[5].toIntOrNull() ?: 0, fields
            )
        }
    }
}
