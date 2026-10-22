package com.example.ialocal.runtime

import org.json.JSONObject

/** A smoke check of the same model's next-token rankings, not an evaluation of answer quality. */
object BenchmarkConsistency {
    fun agreement(cpu: JSONObject, candidate: JSONObject): Int? {
        if (cpu.optString("modelId").isEmpty() || cpu.optString("modelId") != candidate.optString("modelId") ||
            cpu.optString("backend") != "CPU" || cpu.optString("profile") != "CPU" ||
            cpu.optInt("contextTokens") <= 0 || cpu.optInt("contextTokens") != candidate.optInt("contextTokens")) return null
        return runCatching {
            val references = cpu.getJSONArray("runs")
            val runs = candidate.getJSONArray("runs")
            if (references.length() != 3 || runs.length() != 3) return null
            var total = 0
            var equal = 0
            repeat(3) { pass ->
                val expected = references.getJSONObject(pass).getJSONArray("topTokens")
                val actual = runs.getJSONObject(pass).getJSONArray("topTokens")
                if (expected.length() != 64 || actual.length() != 64) return null
                repeat(64) { token ->
                    if (expected.getInt(token) == actual.getInt(token)) equal++
                    total++
                }
            }
            equal * 100 / total
        }.getOrNull()
    }

    fun canApplyGpu(report: JSONObject): Boolean = report.optString("backend") == "Vulkan" &&
        report.optInt("cpuAgreementPercent", -1) >= 95
}
