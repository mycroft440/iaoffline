#!/usr/bin/env python3
"""Compile the production prefetch queue code and test cancellation without Android or a GGUF."""

import ast
from pathlib import Path
import re
import subprocess
import tempfile


def prefetch_source():
    script = (Path(__file__).parent / "prepare_llama_android.sh").read_text()
    for block in re.findall(r"^python3 - [^\n]+ <<'PY'\n(.*?)^PY$", script, re.M | re.S):
        for node in ast.walk(ast.parse(block)):
            if not isinstance(node, ast.Call) or not isinstance(node.func, ast.Name):
                continue
            if node.func.id != "replace_once" or len(node.args) != 2:
                continue
            value = node.args[1]
            if isinstance(value, ast.Constant) and isinstance(value.value, str):
                source = value.value
                if "struct iaoffline_prefetch_state {" in source:
                    declarations = source[source.index("struct iaoffline_prefetch_range {"):]
                    declarations = declarations[:declarations.index("static void iaoffline_guess_experts(")]
                    reset = source[source.index("static void iaoffline_reset_prefetch() {"):]
                    reset = reset[:reset.index("static int iaoffline_layer_of(")]
                    return declarations + reset
    raise RuntimeError("Production prefetch patch was not found")


HARNESS = r"""
#include <algorithm>
#include <atomic>
#include <cassert>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <deque>
#include <future>
#include <mutex>
#include <thread>
#include <vector>

struct ggml_tensor;
constexpr int MADV_WILLNEED = 3;
static std::vector<std::pair<uintptr_t, size_t>> reads;
static bool cancel_after_first = false;
static int fake_madvise(void *, size_t, int);
#define madvise fake_madvise

PRODUCTION_CODE

static int fake_madvise(void *addr, size_t size, int advice) {
    assert(advice == MADV_WILLNEED);
    reads.emplace_back((uintptr_t) addr, size);
    if (cancel_after_first) ++iaoffline_prefetch().serial;
    return 0;
}

int main() {
    using namespace std::chrono_literals;
    auto &state = iaoffline_prefetch();
    const auto *addr = reinterpret_cast<const char *>(0x100003);
    const size_t bytes = 3 * IAOFFLINE_PREFETCH_CHUNK;
    for (uintptr_t page : {4096, 16384}) {
        state.serial = 10;
        reads.clear();
        iaoffline_willneed(addr, bytes, page - 1, 10);
        assert(reads.size() == 4);
        assert(reads.front().first == 0x100000);
        assert(reads.back().first + reads.back().second == (uintptr_t) addr + bytes);
        for (const auto &read : reads) {
            assert(read.first % page == 0);
            assert(read.second <= IAOFFLINE_PREFETCH_CHUNK);
        }

        // A new router arriving during a range must cancel all subsequent chunks.
        reads.clear();
        cancel_after_first = true;
        iaoffline_willneed(addr, bytes, page - 1, 10);
        assert(reads.size() == 1);
        cancel_after_first = false;

        // A range already obsolete when dequeued must cause no I/O at all.
        reads.clear();
        iaoffline_willneed(addr, bytes, page - 1, 10);
        assert(reads.empty());
    }

    // Unload must drop queued work immediately, but must not return while a worker
    // still uses a mapped range or router weights. Exercise both kinds of work.
    for (bool guessing : {false, true}) {
        state.serial = 100;
        state.reading = !guessing;
        state.guessing = guessing;
        state.queue.push_back({addr, bytes, 100});
        state.guesses.emplace_back();
        auto reset = std::async(std::launch::async, [] { iaoffline_reset_prefetch(); });
        const auto deadline = std::chrono::steady_clock::now() + 2s;
        while (state.serial == 100 && std::chrono::steady_clock::now() < deadline) {
            std::this_thread::yield();
        }
        assert(state.serial != 100);
        {
            std::lock_guard<std::mutex> lock(state.mutex);
            assert(state.queue.empty());
            assert(state.guesses.empty());
            assert(reset.wait_for(0s) == std::future_status::timeout);
            state.reading = false;
            state.guessing = false;
        }
        state.cv.notify_all();
        assert(reset.wait_for(2s) == std::future_status::ready);
        reset.get();
    }
}
"""


def main():
    with tempfile.TemporaryDirectory() as directory:
        source = Path(directory) / "prefetch_test.cpp"
        binary = Path(directory) / "prefetch_test"
        source.write_text(HARNESS.replace("PRODUCTION_CODE", prefetch_source()))
        subprocess.run(
            ["g++", "-std=c++17", "-O1", "-pthread", "-Wall", "-Wextra", str(source), "-o", str(binary)],
            check=True,
        )
        subprocess.run([str(binary)], check=True, timeout=10)
    print("MoE prefetch: 4/16 KB alignment, stale reads and unload synchronization passed")


if __name__ == "__main__":
    main()
