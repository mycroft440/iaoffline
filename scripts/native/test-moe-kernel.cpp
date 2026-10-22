#include "moe-cache.h"
#include "ggml.h"
#include "ggml-cpu.h"
#include <cassert>
#include <cmath>
#include <iostream>
#include <vector>

static const void *resolve(const void *source, size_t size, void *user) {
    return static_cast<iaoffline::ExpertCache *>(user)->resolve(source, size);
}

int main() {
    for (auto type : {GGML_TYPE_F32, GGML_TYPE_Q4_0, GGML_TYPE_Q3_K, GGML_TYPE_Q4_K}) {
        for (int batch : {1, 16}) {
            ggml_init_params params{32 * 1024 * 1024, nullptr, false};
            auto *ctx = ggml_init(params);
            auto *weights = ggml_new_tensor_3d(ctx, type, 256, 32, 4);
            std::vector<float> source(256 * 32 * 4);
            for (size_t i = 0; i < source.size(); ++i) source[i] = std::sin(i * 0.071f);
            ggml_quantize_chunk(type, source.data(), weights->data, 0, 32 * 4, 256, nullptr);
            auto *input = ggml_new_tensor_3d(ctx, GGML_TYPE_F32, 256, 1, batch);
            for (int i = 0; i < 256 * batch; ++i) ((float *)input->data)[i] = std::cos(i * 0.013f);
            auto *ids = ggml_new_tensor_2d(ctx, GGML_TYPE_I32, 2, batch);
            for (int i = 0; i < 2 * batch; ++i) ((int32_t *)ids->data)[i] = i % 2;
            auto *out = ggml_mul_mat_id(ctx, weights, input, ids);
            auto *graph = ggml_new_graph(ctx);
            ggml_build_forward_expand(graph, out);
            ggml_backend_cpu_set_expert_resolver(nullptr, nullptr);
            assert(ggml_graph_compute_with_ctx(ctx, graph, 4) == GGML_STATUS_SUCCESS);
            std::vector<float> expected((float *)out->data, (float *)out->data + ggml_nelements(out));
            iaoffline::ExpertCache cache;
            cache.reset(ggml_nbytes(weights));
            for (int repeat = 0; repeat < 2; ++repeat) {
                cache.begin_layer(true);
                for (int expert = 0; expert < 2; ++expert) {
                    auto *p = (char *)weights->data + weights->nb[2] * expert;
                    cache.select(p, weights->nb[2]);
                    cache.resolve(p, weights->nb[2]);
                }
            }
            assert(cache.stats().bytes == 2 * weights->nb[2]);
            // If either kernel ignores the resolver, it will read zeros, not the
            // original quantized values. This tests consumption, not just caching.
            memset(weights->data, 0, ggml_nbytes(weights));
            ggml_backend_cpu_set_expert_resolver(resolve, &cache);
            assert(ggml_graph_compute_with_ctx(ctx, graph, 4) == GGML_STATUS_SUCCESS);
            for (size_t i = 0; i < expected.size(); ++i) {
                assert(std::isfinite(expected[i]));
                assert(std::abs(expected[i] - ((float *)out->data)[i]) <= 1e-5f);
            }
            ggml_backend_cpu_set_expert_resolver(nullptr, nullptr);
            ggml_free(ctx);
            std::cout << ggml_type_name(type) << " batch=" << batch << ": cached kernel output matches\n";
        }
    }
}
