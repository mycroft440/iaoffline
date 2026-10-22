package com.example.ialocal.runtime

/** Execution settings only: no change to weights, sampling, or the conversation. */
enum class PerformanceProfile(val label: String) {
    CPU("CPU · páginas do sistema"),
    CACHE("CPU · cache de especialistas"),
    GPU("GPU parcial · experimental");

    companion object {
        fun parse(value: String?) = entries.firstOrNull { it.name == value } ?: CACHE
    }
}
