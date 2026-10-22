    if (!g_context || pp < 1 || tg < 1 || pp > 256 || tg > 128 || pl != 1 || nr < 1 || nr > 3) {
        return env->NewStringUTF("{\"error\":\"Invalid benchmark configuration\"}");
    }
    // Teacher forcing measures decode on identical natural text across profiles.
    // No token-zero loop, sampling variation, speculative decoding or second model.
    std::string corpus;
    const char *passage = "A cidade planeja melhorar o transporte publico e preservar as arvores. "
        "Explique as vantagens, os custos e como avaliar os resultados ao longo do tempo. "
        "Uma equipe coleta dados, compara alternativas e publica um relatorio claro. "
        "In a small program, a function receives a list of numbers, computes their sum "
        "and checks the result. Reliable measurements require the same inputs and repeated trials. ";
    for (int i = 0; i < 20; ++i) corpus += passage;
    const auto tokens = common_tokenize(g_context, corpus, true, false);
    if (tokens.size() < (size_t)(pp + tg) || llama_n_ctx(g_context) < (uint32_t)(pp + tg)) {
        return env->NewStringUTF("{\"error\":\"Benchmark text or context too short\"}");
    }
    auto read_bytes = []() -> int64_t {
        std::ifstream file("/proc/self/io");
        std::string key;
        int64_t value;
        while (file >> key >> value) if (key == "read_bytes:") return value;
        return -1;
    };
    auto faults = []() -> int64_t {
        struct rusage usage{};
        return getrusage(RUSAGE_SELF, &usage) == 0 ? usage.ru_majflt : -1;
    };
    std::ostringstream report;
    report << "{\"schema\":1,\"backend\":\"" << (g_gpu_active ? "Vulkan" : "CPU") << "\",\"runs\":[";
    bool failed = false;
    for (int run = 0; run < nr && !failed; ++run) {
        llama_memory_clear(llama_get_memory(g_context), false);
        common_batch batch(g_context);
        for (int i = 0; i < pp; ++i) batch.add(tokens[i], i, 0, i == pp - 1);
        auto start = ggml_time_us();
        if (llama_process(g_context, LLAMA_PROCESS_TYPE_DECODE, batch.get()) != 0) { failed = true; break; }
        auto prompt_us = ggml_time_us() - start;
        const auto io_start = read_bytes();
        const auto faults_start = faults();
        const auto cache_start = g_expert_cache.stats();
        start = ggml_time_us();
        for (int i = 0; i < tg; ++i) {
            batch.clear();
            batch.add(tokens[pp + i], pp + i, 0, true);
            if (llama_process(g_context, LLAMA_PROCESS_TYPE_DECODE, batch.get()) != 0) { failed = true; break; }
        }
        const auto decode_us = ggml_time_us() - start;
        const auto io_end = read_bytes();
        const auto faults_end = faults();
        const auto cache = g_expert_cache.stats();
        if (run) report << ',';
        report << "{\"promptTokens\":" << pp << ",\"promptUs\":" << prompt_us
               << ",\"decodeTokens\":" << tg << ",\"decodeUs\":" << decode_us
               << ",\"readBytes\":" << (io_start < 0 || io_end < io_start ? -1 : io_end - io_start)
               << ",\"majorFaults\":" << (faults_start < 0 ? -1 : faults_end - faults_start)
               << ",\"cacheBytes\":" << cache.bytes << ",\"cacheCapacity\":" << cache.capacity
               << ",\"cacheRequested\":" << cache.requested - cache_start.requested
               << ",\"cacheHits\":" << cache.hits - cache_start.hits
               << ",\"cacheCopied\":" << cache.copied - cache_start.copied << '}';
    }
    llama_memory_clear(llama_get_memory(g_context), false);
    if (failed) return env->NewStringUTF("{\"error\":\"Decode failed; incomplete samples discarded\"}");
    report << "]}";
    return env->NewStringUTF(report.str().c_str());
