#pragma once

#include <algorithm>
#include <map>
#include <set>
#include <unordered_map>
#include <vector>
#include <string>
#include <cstring>
#include "dbc/dbcfile.h"

class CANParsers {
public:
    ~CANParsers() {
        for (auto& [_, dbc] : busDbcFiles_) {
            delete dbc;
        }
    }

    void addBus(int busIndex, const std::string& dbcContent) {
        busDbcFiles_[busIndex] = new DBCFile("bus_" + std::to_string(busIndex), dbcContent);
    }

    // Pre-resolve all (msgName, sigName) pairs per bus to avoid expensive runtime lookups.
    // Must be called once after all addBus() calls.
    void buildCache() {
        busSignalCache_.clear();
        for (const auto& [busIdx, dbc] : busDbcFiles_) {
            auto& cache = busSignalCache_[busIdx & 3];
            for (const auto& [addr, msg] : dbc->getMessages()) {
                for (const auto* sig : msg.getSignals()) {
                    std::string key = msg.name + "::" + sig->name;
                    cache[key] = { msg.address, sig };
                }
            }
        }
    }

    void updateFrame(int bus, uint32_t address, const uint8_t* data, size_t len) {
        uint64_t key = frameKey(bus, address);
        auto& stored = latestFrames_[key];
        stored.resize(len);
        std::memcpy(stored.data(), data, len);
    }

    double get(int bus, const std::string& msgName, const std::string& sigName) const {
        int maskedBus = bus & 3;
        auto busIt = busSignalCache_.find(maskedBus);
        if (busIt == busSignalCache_.end()) return 0.0;

        auto it = busIt->second.find(msgName + "::" + sigName);
        if (it == busIt->second.end()) return 0.0;

        const auto& cached = it->second;
        markUsed(cached);
        auto frameIt = latestFrames_.find(frameKey(maskedBus, cached.address));
        if (frameIt == latestFrames_.end()) return 0.0;

        double value = 0.0;
        cached.signal->getValue(frameIt->second.data(), frameIt->second.size(), &value);
        return value;
    }

    // Unscaled signal bits; false if the signal is unknown, no frame has been
    // seen, or the frame's multiplexor doesn't select this signal.
    bool getRaw(int bus, const std::string& msgName, const std::string& sigName, uint64_t* out,
                size_t minLength = 0) const {
        int maskedBus = bus & 3;
        auto busIt = busSignalCache_.find(maskedBus);
        if (busIt == busSignalCache_.end()) return false;

        auto it = busIt->second.find(msgName + "::" + sigName);
        if (it == busIt->second.end()) return false;

        const auto& cached = it->second;
        markUsed(cached);
        auto frameIt = latestFrames_.find(frameKey(maskedBus, cached.address));
        if (frameIt == latestFrames_.end() || frameIt->second.size() < minLength) return false;

        return cached.signal->getRawBits(frameIt->second.data(), frameIt->second.size(), out);
    }

    // Grows each time the mapper reads a message it hadn't read before.
    int usedVersion() const { return usedVersion_; }

    // The DashKit CAN filter (CADA0002) for every message the mapper has read:
    // per bus [bus][count][address LE32 x count]. Standard IDs only.
    std::vector<uint8_t> usedFilter() const {
        std::map<int, std::set<uint32_t>> ids;
        for (const auto& [bus, cache] : busSignalCache_)
            for (const auto& [_, c] : cache)
                if (c.used && c.address <= 0x7FF) ids[bus].insert(c.address);
        std::vector<uint8_t> out;
        for (const auto& [bus, set] : ids) {
            out.push_back(static_cast<uint8_t>(bus));
            out.push_back(static_cast<uint8_t>(std::min<size_t>(set.size(), 255)));
            size_t n = 0;
            for (uint32_t a : set) {
                if (n++ == 255) break;
                for (int i = 0; i < 4; i++) out.push_back(static_cast<uint8_t>(a >> (8 * i)));
            }
        }
        return out;
    }

private:
    struct CachedSignal {
        uint32_t address;
        const cabana::Signal* signal;
        mutable bool used = false;
    };

    void markUsed(const CachedSignal& c) const {
        if (!c.used) { c.used = true; usedVersion_++; }
    }
    mutable int usedVersion_ = 0;

    static inline uint64_t frameKey(int bus, uint32_t address) {
        return (static_cast<uint64_t>(bus & 3) << 32) | address;
    }

    std::map<int, DBCFile*> busDbcFiles_;
    std::map<uint64_t, std::vector<uint8_t>> latestFrames_;
    std::map<int, std::unordered_map<std::string, CachedSignal>> busSignalCache_;
};
