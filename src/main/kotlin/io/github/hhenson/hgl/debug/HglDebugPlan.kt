package io.github.hhenson.hgl.debug

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.execution.ExecutionException
import java.nio.file.Path

internal data class HglDebugLaunch(
    val arguments: Map<String, Any>,
    val module: String? = null,
    val tests: List<String> = emptyList(),
    val resultsFile: Path? = null,
)

internal data class HglDebugPlan(val launches: List<HglDebugLaunch>, val skipped: List<String>) {
    companion object {
        fun parse(report: String, testRun: Boolean): HglDebugPlan {
            try {
                val data = JsonParser.parseString(report).asJsonObject
                if (data["version"]?.asInt != 1) throw ExecutionException("Unsupported HGL debug launch version.")
                if (!testRun) return HglDebugPlan(listOf(HglDebugLaunch(arguments(data))), emptyList())
                val launches = data["launches"].asJsonArray.map {
                    val launch = it.asJsonObject
                    HglDebugLaunch(arguments(launch), launch["module"].asString,
                        launch["tests"].asJsonArray.map { name -> name.asString }, Path.of(launch["resultsFile"].asString))
                }
                val skipped = data["skipped"].asJsonArray.map {
                    val item = it.asJsonObject
                    "${item["name"].asString}: ${item["reason"].asString}"
                }
                return HglDebugPlan(launches, skipped)
            } catch (error: RuntimeException) {
                throw ExecutionException("Invalid debug launch description from the HGL compiler.", error)
            }
        }

        private fun arguments(launch: JsonObject): Map<String, Any> = mapOf(
            "program" to launch["executable"].asString,
            "args" to launch["arguments"].asJsonArray.map { it.asString },
            "cwd" to launch["workingDirectory"].asString,
            "env" to launch["environment"].asJsonObject.entrySet().associate { it.key to it.value.asString },
        )
    }
}

/** A stopped or crashed test host can leave a partial results file. */
internal class HglDebugResults(tests: List<String>, lines: List<String>) {
    private val outcomes = arrayOfNulls<String>(tests.size)
    init {
        for (line in lines) {
            val fields = line.split(' ', limit = 3)
            val i = fields.getOrNull(1)?.toIntOrNull() ?: continue
            if (i !in tests.indices) continue
            when (fields[0]) {
                "passed" -> outcomes[i] = ""
                "failed", "unloaded" -> {
                    val detail = unescape(fields.getOrElse(2) { "" })
                    outcomes[i] = "${tests[i]}: ${fields[0]} $detail".trimEnd()
                }
            }
        }
    }
    // The native test host escapes backslashes, newlines and carriage returns in one record.
    private fun unescape(value: String): String = buildString {
        val chars = value.iterator()
        while (chars.hasNext()) {
            val c = chars.nextChar()
            if (c != '\\' || !chars.hasNext()) append(c)
            else append(when (val escaped = chars.nextChar()) {
                'n' -> '\n'
                'r' -> '\r'
                else -> escaped
            })
        }
    }

    val complete get() = outcomes.all { it != null }
    val failures get() = outcomes.filterNotNull().filter(String::isNotEmpty)
}
