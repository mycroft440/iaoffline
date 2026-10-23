#include "moe-cache.h"
#include <array>
#include <cassert>
#include <thread>
#include <vector>
#include <iostream>

int main() {
    iaoffline::ExpertCache cache;
    std::array<char, 256> a{}, b{}, c{};
    for (size_t i = 0; i < a.size(); ++i) { a[i] = i; b[i] = i + 7; c[i] = i + 19; }
    cache.reset(512);
    auto use = [&](const auto &data, bool decode = true) {
        cache.begin_layer(decode);
        cache.select(data.data(), data.size());
        return cache.resolve(data.data(), data.size());
    };
    assert(use(a, false) == a.data()); // a prefill cannot fill/evict the cache
    assert(use(a) == a.data()); // require reuse
    const auto *copy = use(a);
    assert(copy != a.data() && memcmp(copy, a.data(), a.size()) == 0);
    assert(use(b) == b.data());
    assert(use(b) != b.data());
    assert(cache.stats().bytes == 512);
    // Pin both demanded entries before a new, hotter entry asks for room.
    cache.begin_layer(true);
    cache.select(a.data(), a.size()); cache.select(b.data(), b.size());
    cache.select(c.data(), c.size());
    assert(cache.resolve(c.data(), c.size()) == c.data());
    assert(cache.resolve(a.data(), a.size()) == copy);
    // Multithreaded readers must receive stable, byte-identical storage.
    std::vector<std::thread> readers;
    for (int t = 0; t < 8; ++t) readers.emplace_back([&] {
        for (int n = 0; n < 1000; ++n) {
            const auto *p = cache.resolve(a.data(), a.size());
            assert(p == copy && memcmp(p, a.data(), a.size()) == 0);
        }
    });
    for (auto &thread : readers) thread.join();
    for (int n = 0; n < 5; ++n) use(c);
    assert(cache.contains(c.data()));
    assert(cache.stats().bytes <= 512);
    assert(cache.stats().hits > 0);
    assert(use(c, false) != c.data()); // prefill can reuse existing copies
    // Oversize, disabled, and wrong-stride lookups must preserve the mapped source.
    assert(cache.resolve(c.data(), 257) == c.data());
    cache.reset(128);
    assert(use(a) == a.data()); assert(use(a) == a.data());
    cache.reset(0);
    assert(use(a) == a.data() && cache.stats().bytes == 0);
    cache.reset(512);
    for (int i = 0; i < 2100; ++i) use(a);
    assert(cache.stats().bytes <= 512);
    cache.reset(512);
    cache.begin_layer(true); cache.select(a.data(), a.size());
    cache.begin_layer(true); cache.select(a.data(), a.size());
    std::array<const void *, 8> pointers{};
    readers.clear();
    for (size_t t = 0; t < pointers.size(); ++t) readers.emplace_back([&, t] {
        pointers[t] = cache.resolve(a.data(), a.size());
    });
    for (auto &thread : readers) thread.join();
    for (auto *p : pointers) assert(p == pointers[0] && p != a.data());
    assert(cache.stats().copied == a.size()); // one allocation/copy under concurrent admission
    iaoffline::PrefetchConfidence good, bad;
    for (int i = 0; i < 16; ++i) { good.observe(8, 7); bad.observe(8, 3); }
    assert(good.enabled && !bad.enabled);
    for (int i = 0; i < 16; ++i) good.observe(8, 5);
    assert(good.enabled); // hysteresis
    for (int i = 0; i < 16; ++i) good.observe(8, 4);
    assert(!good.enabled);
    unsigned sampled = 0;
    for (int i = 0; i < 80; ++i) if (bad.sample()) ++sampled;
    assert(sampled == 10);
    std::cout << "Expert cache: exact bytes, pinning, eviction, concurrency, bounds and confidence OK\n";
}
