#pragma once

#include <algorithm>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <unordered_map>

namespace iaoffline {

// Owns exact, still-quantized expert slices. The router opens a new epoch only
// after the preceding layer has completed. Returned pointers stay pinned until
// the next epoch; all CPU threads may use them without holding the mutex.
// No predicted expert is admitted, and prompt batches never populate the cache.
class ExpertCache {
    struct Entry {
        size_t size = 0;
        void *copy = nullptr;
        unsigned frequency = 0;
        uint64_t last = 0;
        uint64_t pinned = 0;
        uint64_t selected = 0;
    };
    mutable std::mutex mutex_;
    std::unordered_map<const void *, Entry> entries_;
    size_t capacity_ = 0, bytes_ = 0;
    uint64_t epoch_ = 1;
    bool admit_ = false;
    uint64_t requested_ = 0, hits_ = 0, copied_ = 0;
    static constexpr size_t MAX_ENTRIES = 32768;

public:
    struct Stats { size_t capacity, bytes; uint64_t requested, hits, copied; };
    ~ExpertCache() { reset(0); }
    // Called only with inference stopped (also before the mapping is released).
    void reset(size_t capacity) {
        std::lock_guard<std::mutex> lock(mutex_);
        for (auto &item : entries_) std::free(item.second.copy);
        entries_.clear();
        capacity_ = capacity;
        bytes_ = requested_ = hits_ = copied_ = 0;
        epoch_ = 1;
        admit_ = false;
    }
    void begin_layer(bool decode) {
        std::lock_guard<std::mutex> lock(mutex_);
        ++epoch_;
        admit_ = decode;
        // Age old topics; keep bounded counters and metadata across long chats.
        if (epoch_ % 1024 == 0) {
            for (auto it = entries_.begin(); it != entries_.end();) {
                it->second.frequency /= 2;
                if (!it->second.copy && it->second.frequency == 0) it = entries_.erase(it);
                else ++it;
            }
        }
    }
    void select(const void *source, size_t size) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (!capacity_ || !source || !size || size > capacity_) return;
        auto it = entries_.find(source);
        if (it == entries_.end()) {
            if (!admit_ || entries_.size() >= MAX_ENTRIES) return;
            it = entries_.emplace(source, Entry{}).first;
            it->second.size = size;
        }
        auto &entry = it->second;
        if (entry.size != size || entry.selected == epoch_) return;
        entry.selected = epoch_;
        entry.last = epoch_;
        if (admit_) {
            entry.frequency = std::min(65535U, entry.frequency + 1);
            requested_ += size;
            if (entry.copy) hits_ += size;
        }
        // Protect ALL selected entries, including those not yet visited by a kernel.
        if (entry.copy) entry.pinned = epoch_;
    }
    bool contains(const void *source) const {
        std::lock_guard<std::mutex> lock(mutex_);
        auto it = entries_.find(source);
        return it != entries_.end() && it->second.copy;
    }
    const void *resolve(const void *source, size_t size) {
        std::lock_guard<std::mutex> lock(mutex_);
        auto it = entries_.find(source);
        if (it == entries_.end() || it->second.size != size) return source;
        auto &entry = it->second;
        if (entry.copy) { entry.pinned = epoch_; return entry.copy; }
        if (!admit_ || entry.selected != epoch_ || entry.frequency < 2) return source;
        while (size > capacity_ - bytes_) {
            auto victim = entries_.end();
            for (auto candidate = entries_.begin(); candidate != entries_.end(); ++candidate) {
                auto &v = candidate->second;
                if (!v.copy || v.pinned == epoch_ || v.frequency >= entry.frequency) continue;
                if (victim == entries_.end() || v.frequency < victim->second.frequency ||
                    (v.frequency == victim->second.frequency && v.last < victim->second.last)) victim = candidate;
            }
            if (victim == entries_.end()) return source;
            auto &v = victim->second;
            bytes_ -= v.size;
            std::free(v.copy);
            v.copy = nullptr;
        }
        void *copy = nullptr;
        if (posix_memalign(&copy, 64, size) != 0) return source;
        // This is the demanded slice the kernel would read now anyway. One copy
        // per admission, no extra storage reads for speculative cache population.
        std::memcpy(copy, source, size);
        entry.copy = copy;
        entry.pinned = epoch_;
        bytes_ += size;
        copied_ += size;
        return copy;
    }
    Stats stats() const {
        std::lock_guard<std::mutex> lock(mutex_);
        return {capacity_, bytes_, requested_, hits_, copied_};
    }
};

// Each layer learns independently: one inaccurate router must not switch every
// layer off. Hysteresis avoids oscillation; disabled layers are sampled cheaply.
struct PrefetchConfidence {
    unsigned observations = 0, picked = 0, correct = 0, samples = 0;
    bool enabled = false;
    bool sample() { return enabled || (++samples % 8 == 0); }
    void observe(unsigned n, unsigned hits) {
        picked += n;
        correct += hits;
        if (++observations < 16) return;
        enabled = correct * 100 >= picked * (enabled ? 60 : 75);
        observations = picked = correct = 0;
    }
};
} // namespace iaoffline
