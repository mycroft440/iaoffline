package com.example.ialocal.runtime

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BenchmarkConsistencyTest {
    private fun report(profile: String, backend: String, differingPerPass: Int = 0) = JSONObject()
        .put("modelId", "same-model").put("profile", profile).put("backend", backend).put("contextTokens", 2048)
        .put("runs", JSONArray().apply {
            repeat(3) { put(JSONObject().put("topTokens", JSONArray().apply {
                repeat(64) { put(it + if (it < differingPerPass) 1000 else 0) }
            })) }
        })

    @Test fun requiresSameModelContextAndCompleteSamples() {
        val cpu = report("CPU", "CPU")
        val gpu = report("GPU", "Vulkan")
        assertEquals(100, BenchmarkConsistency.agreement(cpu, gpu))
        assertNull(BenchmarkConsistency.agreement(cpu, gpu.put("modelId", "other")))
        gpu.put("modelId", "same-model").put("contextTokens", 1024)
        assertNull(BenchmarkConsistency.agreement(cpu, gpu))
        gpu.put("contextTokens", 2048).getJSONArray("runs").getJSONObject(1).getJSONArray("topTokens").remove(0)
        assertNull(BenchmarkConsistency.agreement(cpu, gpu))
    }

    @Test fun gpuCannotBeAppliedBeforeCheckOrAfterCpuFallback() {
        val cpu = report("CPU", "CPU")
        val gpu = report("GPU", "Vulkan", differingPerPass = 3)
        assertFalse(BenchmarkConsistency.canApplyGpu(gpu))
        gpu.put("cpuAgreementPercent", BenchmarkConsistency.agreement(cpu, gpu))
        assertTrue(BenchmarkConsistency.canApplyGpu(gpu))
        val bad = report("GPU", "Vulkan", differingPerPass = 4)
        bad.put("cpuAgreementPercent", BenchmarkConsistency.agreement(cpu, bad))
        assertFalse(BenchmarkConsistency.canApplyGpu(bad))
        assertFalse(BenchmarkConsistency.canApplyGpu(gpu.put("backend", "CPU")))
    }
}
