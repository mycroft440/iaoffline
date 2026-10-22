package com.example.ialocal.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.ialocal.data.AiModelEntity
import com.example.ialocal.models.ModelRepository
import com.example.ialocal.runtime.BenchmarkConsistency
import com.example.ialocal.runtime.ModelRuntime
import com.example.ialocal.runtime.PerformanceProfile
import com.example.ialocal.runtime.RuntimeStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class PerformanceViewModel(
    context: Context,
    repository: ModelRepository,
    private val runtime: ModelRuntime,
) : ViewModel() {
    val models = repository.models.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val runtimeState = runtime.state
    private val prefs = context.getSharedPreferences("performance_reports", Context.MODE_PRIVATE)
    private val _reports = MutableStateFlow(readReports())
    val reports = _reports.asStateFlow()
    private val _progress = MutableStateFlow<String?>(null)
    val progress = _progress.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    private val _profiles = MutableStateFlow<Map<String, PerformanceProfile>>(emptyMap())
    val profiles = _profiles.asStateFlow()
    private var job: Job? = null

    init {
        viewModelScope.launch {
            models.collect { list -> _profiles.value = list.associate { it.id to runtime.performanceProfile(it.id) } }
        }
    }

    /** Compare one or several installed quantizations; nothing is downloaded or activated. */
    fun compare(selectedIds: Set<String>) {
        if (job?.isActive == true) return
        if (runtime.state.value.status !in setOf(RuntimeStatus.IDLE, RuntimeStatus.READY, RuntimeStatus.ERROR)) {
            _message.value = "Aguarde a IA terminar a tarefa atual."
            return
        }
        val selected = models.value.filter { it.id in selectedIds }
        if (selected.isEmpty()) return
        job = viewModelScope.launch {
            _message.value = null
            try {
                // Rotate ordering on subsequent comparisons; the OS file cache cannot be
                // flushed by an unprivileged app and must not be called a cold-storage test.
                val sequence = prefs.getInt("sequence", 0)
                prefs.edit().putInt("sequence", (sequence + 1) % 3).apply()
                for (model in selected) {
                    val group = java.util.UUID.randomUUID().toString()
                    val completed = mutableListOf<JSONObject>()
                    val order = PerformanceProfile.entries.let { it.drop(sequence) + it.take(sequence) }
                    for (profile in order) {
                        _progress.value = "${model.name} · ${profile.label} · 3 passagens"
                        try {
                            val report = JSONObject(runtime.benchmark(model, profile))
                            report.put("group", group)
                            completed += report
                            save(report.toString())
                        } catch (cancel: CancellationException) {
                            throw cancel
                        } catch (failure: Exception) {
                            save(JSONObject().put("modelId", model.id).put("modelName", model.name)
                                .put("profile", profile.name).put("timestamp", System.currentTimeMillis())
                                .put("error", failure.message ?: "Falha no teste").toString())
                            // A failed allocation changes the safety level. Stop instead of
                            // comparing the remaining profiles under different memory limits.
                            _message.value = "Teste interrompido: ${failure.message}"
                            return@launch
                        }
                    }
                    completed.firstOrNull { it.optString("profile") == "CPU" }?.let { reference ->
                        completed.forEach { report ->
                            BenchmarkConsistency.agreement(reference, report)?.let { report.put("cpuAgreementPercent", it) }
                        }
                        _reports.value = _reports.value.filterNot { JSONObject(it).optString("group") == group }
                        completed.forEach { save(it.toString()) }
                    }
                }
                _message.value = "Comparação concluída. Escolha um perfil pelos resultados; a qualidade deve ser avaliada nas suas conversas."
            } finally {
                _progress.value = null
                _profiles.value = models.value.associate { it.id to runtime.performanceProfile(it.id) }
            }
        }
    }

    fun stop() {
        if (_progress.value == null) return
        runtime.interruptNativeWork()
        job?.cancel()
        _message.value = "Teste cancelado."
    }

    fun apply(modelId: String, profile: PerformanceProfile) {
        if (_progress.value != null) return
        if (profile == PerformanceProfile.GPU && _reports.value.none { raw ->
            val report = JSONObject(raw)
            report.optString("modelId") == modelId && BenchmarkConsistency.canApplyGpu(report)
        }) {
            _message.value = "Conclua uma comparação válida entre GPU e CPU antes de aplicar o perfil."
            return
        }
        runtime.setPerformanceProfile(modelId, profile)
        _profiles.value = _profiles.value + (modelId to profile)
        _message.value = "Perfil salvo. Será usado no próximo carregamento do modelo."
    }

    private fun readReports(): List<String> = runCatching {
        val values = JSONArray(prefs.getString("reports", "[]"))
        List(values.length()) { values.getJSONObject(it).toString() }
    }.getOrDefault(emptyList())

    private fun save(report: String) {
        _reports.value = (listOf(report) + _reports.value).take(30)
        prefs.edit().putString("reports", JSONArray().apply {
            _reports.value.forEach { put(JSONObject(it)) }
        }.toString()).apply()
    }

    override fun onCleared() { stop() }

    class Factory(private val context: Context, private val repository: ModelRepository, private val runtime: ModelRuntime) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            PerformanceViewModel(context.applicationContext, repository, runtime) as T
    }
}
