package com.fortis.mobile.network

import org.json.JSONObject

/** GET /telemetry (docs/api §5) formatted for display. */
data class Telemetry(
    val uptime: String,
    val cpu: String,
    val mem: String,
    val swap: String,
    val storage: String,
    val temp: String,
) {
    companion object {
        private fun gb(bytes: Long) = "%.1f GB".format(bytes / 1_073_741_824.0)
        private fun duration(secs: Long): String {
            val d = secs / 86_400; val h = (secs % 86_400) / 3600; val m = (secs % 3600) / 60
            return when { d > 0 -> "${d}d ${h}h"; h > 0 -> "${h}h ${m}m"; else -> "${m}m" }
        }

        fun parse(json: String): Telemetry {
            val o = JSONObject(json)
            return Telemetry(
                uptime = duration(o.optLong("uptime_secs")),
                cpu = "%.1f%%".format(o.optDouble("cpu_percent", 0.0)),
                mem = "${o.optLong("mem_used_mb")} / ${o.optLong("mem_total_mb")} MB",
                swap = "${o.optLong("swap_used_mb")} / ${o.optLong("swap_total_mb")} MB",
                storage = "${gb(o.optLong("disk_used_bytes"))} / ${gb(o.optLong("disk_total_bytes"))}",
                temp = if (o.isNull("temp_c") || !o.has("temp_c")) "--" else "%.0f°C".format(o.optDouble("temp_c")),
            )
        }
    }
}
