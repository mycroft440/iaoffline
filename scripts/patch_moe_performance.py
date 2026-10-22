#!/usr/bin/env python3
"""Strict patch on top of prepare_llama_android.sh, against pinned llama.cpp v0.7.0."""
from pathlib import Path
import shutil
import sys

root = Path(sys.argv[1])
native = Path(__file__).parent / 'native'
cpp = root / 'examples/llama.android/lib/src/main/cpp'

def edit(path, old, new, count=1):
    text = path.read_text()
    assert text.count(old) == count, (path, old[:100], text.count(old))
    path.write_text(text.replace(old, new))

# Dynamic CPU variants must register the callback in the variant actually loaded.
h = root / 'ggml/include/ggml-cpu.h'
edit(h, '    GGML_BACKEND_API void ggml_backend_cpu_set_abort_callback', '''    typedef const void * (*ggml_cpu_expert_resolver_t)(const void *, size_t, void *);
    GGML_BACKEND_API void ggml_backend_cpu_set_expert_resolver(ggml_cpu_expert_resolver_t callback, void * user);
    GGML_BACKEND_API const void * ggml_cpu_resolve_expert(const void * source, size_t size);

    GGML_BACKEND_API void ggml_backend_cpu_set_abort_callback''')
c = root / 'ggml/src/ggml-cpu/ggml-cpu.c'
edit(c, 'static void ggml_compute_forward_mul_mat_id(', '''// Set/unset only while all compute threads are idle.
static ggml_cpu_expert_resolver_t iaoffline_expert_resolver;
static void * iaoffline_expert_user;
void ggml_backend_cpu_set_expert_resolver(ggml_cpu_expert_resolver_t callback, void * user) {
    iaoffline_expert_resolver = callback;
    iaoffline_expert_user = user;
}
const void * ggml_cpu_resolve_expert(const void * source, size_t size) {
    return iaoffline_expert_resolver ? iaoffline_expert_resolver(source, size, iaoffline_expert_user) : source;
}

static void ggml_compute_forward_mul_mat_id(''')
for path in (c, root / 'ggml/src/ggml-cpu/tiled/tiled.cpp'):
    edit(path, 'const char * src0_cur = (const char *) src0->data + cur_a * nb02;',
         'const char * src0_cur = (const char *) ggml_cpu_resolve_expert((const char *) src0->data + cur_a * nb02, nb02);')
edit(root / 'ggml/src/ggml-cpu/ggml-cpu.cpp', '    if (strcmp(name, "ggml_backend_set_n_threads") == 0) {', '''    if (strcmp(name, "ggml_backend_cpu_set_expert_resolver") == 0) {
        return (void *)ggml_backend_cpu_set_expert_resolver;
    }
    if (strcmp(name, "ggml_backend_set_n_threads") == 0) {''')

shutil.copyfile(native / 'moe-cache.h', cpp / 'moe-cache.h')
a = cpp / 'ai_chat.cpp'
edit(a, '#include <cstdlib>', '#include <cstdlib>\n#include "moe-cache.h"\n#include <fstream>')
edit(a, 'struct iaoffline_prefetch_range {', '''static iaoffline::ExpertCache g_expert_cache;
static std::string g_backend_dir;
static bool g_gpu_active = false;
static const void *iaoffline_resolve_expert(const void *source, size_t size, void *) {
    return g_expert_cache.resolve(source, size);
}
static void iaoffline_configure_cache() {
    auto reg = ggml_backend_reg_by_name("CPU");
    using setter = void (*)(ggml_cpu_expert_resolver_t, void *);
    auto set = reg ? (setter) ggml_backend_reg_get_proc_address(reg, "ggml_backend_cpu_set_expert_resolver") : nullptr;
    const char *mb = getenv("IAOFFLINE_EXPERT_CACHE_MB");
    const size_t capacity = set && mb ? (size_t) std::clamp(atoi(mb), 0, 512) * 1024 * 1024 : 0;
    g_expert_cache.reset(capacity);
    if (set) set(capacity ? iaoffline_resolve_expert : nullptr, nullptr);
}

struct iaoffline_prefetch_range {''')
edit(a, '    long residual_serial = -1;\n', '    long residual_serial = -1;\n    iaoffline::PrefetchConfidence confidence;\n')
# Existing global confidence no longer drives predictions; retain its declaration
# for the original patch's independent cancellation harness, remove from generated C++.
text = a.read_text()
start = text.index('// The guessed experts are read ahead only')
end = text.index('// Requests [addr, addr + size)', start)
text = text[:start] + text[end:]
start = text.index('    g_guess_window = 0;', text.index('static void iaoffline_reset_prefetch'))
end = text.index('\n}', start)
text = text[:start] + '    g_expert_cache.reset(0);' + text[end:]
start = text.index('        g_guess_window++;')
end = text.index('\n    }\n    return true;', start)
text = text[:start] + '        g_moe_layers[layer].confidence.observe(guessed, right);' + text[end:]
a.write_text(text)
edit(a, '    const bool sampled = g_guess_read_ahead || !g_guess_measured || serial % IAOFFLINE_GUESS_SAMPLE == 0;\n    if (one_token && sampled', '    if (one_token')
edit(a, '                guess.serial = serial;', '''                auto &confidence = g_moe_layers[next].confidence;
                if (!confidence.sample()) break;
                guess.serial = serial;''')
edit(a, '                guess.read_ahead = g_guess_read_ahead;', '                guess.read_ahead = confidence.enabled;')
edit(a, '    iaoffline_reset_prefetch();\n    if (iaoffline_moe_stream_mode()) iaoffline_start_prefetch();',
     '    iaoffline_reset_prefetch();\n    iaoffline_configure_cache();\n    if (iaoffline_moe_stream_mode()) iaoffline_start_prefetch();')
# Never dereference device buffers. IDs can be a strided view: copy its full byte span.
edit(a, '    auto id_of = [t](int64_t token, int64_t k) {\n        return *(const int32_t *) ((const char *) t->data + token * t->nb[1] + k * t->nb[0]);\n    };', '''    std::vector<char> device_ids;
    const char *ids = (const char *) t->data;
    if (t->buffer && !ggml_backend_buffer_is_host(t->buffer)) {
        device_ids.resize(ggml_nbytes(t));
        ggml_backend_tensor_get(t, device_ids.data(), 0, device_ids.size());
        ids = device_ids.data();
    }
    auto id_of = [t, ids](int64_t token, int64_t k) {
        int32_t id;
        memcpy(&id, ids + token * t->nb[1] + k * t->nb[0], sizeof(id));
        return id;
    };
    g_expert_cache.begin_layer(one_token);''')
edit(a, '    std::vector<bool> used;', '    std::vector<bool> used;') # shape guard
edit(a, '    // Guesses the next layer\'s experts', '''    for (const ggml_tensor *w : info.experts) {
        if (!ggml_is_contiguous(w)) continue;
        for (size_t id = 0; id < used.size() && id < (size_t) w->ne[2]; ++id) {
            if (used[id]) g_expert_cache.select((const char *) w->data + id * w->nb[2], w->nb[2]);
        }
    }
    // Guesses the next layer's experts''')
edit(a, 't->data != nullptr && strncmp(t->name, "blk.", 4) == 0;',
     't->data != nullptr && t->buffer && ggml_backend_buffer_is_host(t->buffer) && strncmp(t->name, "blk.", 4) == 0;')
edit(a, '        info.residual->data != nullptr) {',
     '        info.residual->data != nullptr && info.residual->buffer && ggml_backend_buffer_is_host(info.residual->buffer)) {')
# Cached copies are consumed by the kernel; prefetching their old mapping wastes I/O.
edit(a, '                            if (expert >= w->ne[2]) continue;',
     '                            if (expert >= w->ne[2] || g_expert_cache.contains((const char *) w->data + expert * w->nb[2])) continue;')
edit(a, '                if (!used[expert]) continue;',
     '                if (!used[expert] || g_expert_cache.contains((const char *) w->data + expert * w->nb[2])) continue;')
# GPU is probed ONLY for an explicit experiment/profile, in the isolated AI process.
edit(root / 'ggml/src/ggml-backend-reg.cpp', '    ggml_backend_load_best("vulkan", silent, dir_path);',
     '    // IA Offline explicitly loads Vulkan on opt-in; default CPU must not probe drivers.\n#ifndef __ANDROID__\n    ggml_backend_load_best("vulkan", silent, dir_path);\n#endif')
edit(a, '    ggml_backend_load_all_from_path(path_to_backend);',
     '    g_backend_dir = path_to_backend;\n    ggml_backend_load_all_from_path(path_to_backend);')
edit(a, '    model_params.n_gpu_layers = 0;', '''    model_params.n_gpu_layers = 0;
    static ggml_backend_dev_t chosen_devices[2] = {nullptr, nullptr};
    chosen_devices[0] = nullptr;
    model_params.devices = chosen_devices; // an empty list explicitly keeps CPU runs on CPU
    g_gpu_active = false;
    const char *gpu = getenv("IAOFFLINE_GPU_LAYERS");
    if (gpu && atoi(gpu) > 0) {
        auto reg = ggml_backend_reg_by_name("Vulkan");
        if (!reg) reg = ggml_backend_load((g_backend_dir + "/libggml-vulkan.so").c_str());
        if (reg && ggml_backend_reg_dev_count(reg) > 0) {
            chosen_devices[0] = ggml_backend_reg_dev_get(reg, 0);
            model_params.n_gpu_layers = std::clamp(atoi(gpu), 0, 4);
            model_params.use_extra_bufts = false;
            g_gpu_active = true;
        }
    }''')
edit(a, '        model_params.use_extra_bufts = false;\n        model = llama_model_load_from_file(model_path, model_params);', '''        model_params.use_extra_bufts = false;
        model_params.n_gpu_layers = 0;
        chosen_devices[0] = nullptr;
        g_gpu_active = false;
        model = llama_model_load_from_file(model_path, model_params);''')
edit(a, '    g_model = model;\n    return 0;', '    g_model = model;\n    setenv("IAOFFLINE_ACTIVE_BACKEND", g_gpu_active ? "Vulkan" : "CPU", 1);\n    return 0;')
# A real, fixed token sequence; one existing context, no additional KV allocation.
text = a.read_text()
start = text.index('    auto *context = init_context(g_model, pp);', text.index('InferenceEngineImpl_benchModel'))
end = text.index('\n}\n', start)
text = text[:start] + (native / 'benchmark.inc').read_text() + text[end:]
a.write_text(text)

# Optional ARM64 Vulkan backend; CPU-only x86 emulator keeps the same installation path.
cmake = cpp / 'CMakeLists.txt'
edit(cmake, 'add_subdirectory(${LLAMA_SRC} build-llama)', '''if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_VULKAN ON CACHE BOOL "" FORCE)
    set(Vulkan_INCLUDE_DIR "${LLAMA_SRC}/../vulkan-include" CACHE PATH "" FORCE)
    find_library(IAOFFLINE_VULKAN_LIBRARY vulkan REQUIRED)
    set(Vulkan_LIBRARY "${IAOFFLINE_VULKAN_LIBRARY}" CACHE FILEPATH "" FORCE)
    find_program(Vulkan_GLSLC_EXECUTABLE glslc NO_CMAKE_FIND_ROOT_PATH REQUIRED)
    include_directories("${Vulkan_INCLUDE_DIR}")
endif()
add_subdirectory(${LLAMA_SRC} build-llama)''')
edit(root / 'ggml/src/ggml-vulkan/CMakeLists.txt', 'find_package(SPIRV-Headers CONFIG REQUIRED)',
     'if(NOT ANDROID)\n    find_package(SPIRV-Headers CONFIG REQUIRED)\nendif()')
print('Applied bounded expert cache, adaptive prefetch, benchmark and opt-in Vulkan patches')
