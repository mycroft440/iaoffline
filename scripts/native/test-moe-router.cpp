#include "moe-cache.h"
#include "ggml.h"
#include "ggml-cpu.h"
#include "ggml-backend.h"
#include "ggml-impl.h"
#include <atomic>
#include <cassert>
#include <cmath>
#include <condition_variable>
#include <deque>
#include <iostream>
#include <string>
#include <thread>
#include <vector>
#include <sys/mman.h>
#include <sys/resource.h>
#include <unistd.h>
#define LOGi(...) ((void)0)
static int g_perf_guessed_experts = 0, g_perf_guessed_right = 0;
#include "production-moe.inc"

int main() {
    auto backend = ggml_backend_cpu_init();
    ggml_backend_cpu_set_n_threads(backend, 4);
    auto sched = ggml_backend_sched_new(&backend, nullptr, 1, 2048, false, false);
    ggml_init_params params{16 * 1024 * 1024, nullptr, false};
    auto ctx = ggml_init(params);
    auto mapped = ggml_backend_cpu_buffer_from_ptr(ggml_get_mem_buffer(ctx), ggml_get_mem_size(ctx));
    auto weights = ggml_new_tensor_3d(ctx, GGML_TYPE_Q3_K, 256, 32, 4);
    weights->buffer = mapped;
    ggml_set_name(weights, "blk.0.ffn_up_exps.weight");
    std::vector<float> values(256 * 32 * 4);
    for (size_t i = 0; i < values.size(); ++i) values[i] = std::sin(i * 0.031f);
    ggml_quantize_chunk(weights->type, values.data(), weights->data, 0, 128, 256, nullptr);
    auto input = ggml_new_tensor_3d(ctx, GGML_TYPE_F32, 256, 1, 1);
    input->buffer = mapped;
    for (int i = 0; i < 256; ++i) ((float *)input->data)[i] = std::cos(i * 0.13f);
    auto raw_ids = ggml_new_tensor_2d(ctx, GGML_TYPE_I32, 2, 1);
    raw_ids->buffer = mapped;
    ((int32_t *)raw_ids->data)[0] = 1; ((int32_t *)raw_ids->data)[1] = 3;
    auto ids = ggml_dup(ctx, raw_ids);
    ids->buffer = mapped;
    ggml_set_name(ids, "ffn_moe_topk-0");
    auto out = ggml_mul_mat_id(ctx, weights, input, ids);
    out->buffer = mapped;
    auto graph = ggml_new_graph(ctx);
    ggml_build_forward_expand(graph, out);
    assert(ggml_backend_sched_graph_compute(sched, graph) == GGML_STATUS_SUCCESS);
    std::vector<float> expected((float *)out->data, (float *)out->data + ggml_nelements(out));
    g_expert_cache.reset(ggml_nbytes(weights));
    ggml_backend_cpu_set_expert_resolver(iaoffline_resolve_expert, nullptr);
    ggml_backend_sched_set_eval_callback(sched, iaoffline_moe_eval_callback, nullptr);
    // First pass discovers mapped tensors; later router callbacks select/admit slices.
    for (int pass = 0; pass < 5; ++pass) assert(ggml_backend_sched_graph_compute(sched, graph) == GGML_STATUS_SUCCESS);
    assert(g_expert_cache.stats().bytes == 2 * weights->nb[2]);
    assert(g_expert_cache.stats().hits > 0);
    memset(weights->data, 0, ggml_nbytes(weights));
    assert(ggml_backend_sched_graph_compute(sched, graph) == GGML_STATUS_SUCCESS);
    for (size_t i = 0; i < expected.size(); ++i) assert(std::abs(expected[i] - ((float *)out->data)[i]) <= 1e-5f);
    iaoffline_reset_prefetch();
    ggml_backend_cpu_set_expert_resolver(nullptr, nullptr);
    ggml_backend_sched_free(sched);
    ggml_backend_buffer_free(mapped);
    ggml_free(ctx);
    ggml_backend_free(backend);
    std::cout << "Production router callback selects the slices consumed by the scheduled CPU graph\n";
}
