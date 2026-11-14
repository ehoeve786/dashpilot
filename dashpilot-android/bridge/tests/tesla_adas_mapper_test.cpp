// Host-run test for TeslaDashKitMapper's adasOn (repo/bridge/car/cars/tesla.h),
// against the shipped vehicle DBC. Built by CMakeLists.txt when not
// configuring for Android.

#include "common/vehicle_decoder.h"

#include <cstdio>
#include <fstream>
#include <iterator>

static int failures = 0;

#define CHECK(cond)                                                          \
    do {                                                                     \
        if (!(cond)) {                                                       \
            std::printf("FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond);      \
            failures++;                                                      \
        }                                                                    \
    } while (0)

namespace {

// Writes `value` into little-endian (Intel) bits [start, start+len) of `frame`.
void setBits(uint8_t* frame, int start, int len, uint64_t value) {
    for (int i = 0; i < len; i++) {
        int bit = start + i;
        if ((value >> i) & 1) frame[bit / 8] |= static_cast<uint8_t>(1u << (bit % 8));
        else frame[bit / 8] &= static_cast<uint8_t>(~(1u << (bit % 8)));
    }
}

struct Harness {
    VehicleDecoder decoder;
    explicit Harness(const std::string& dbc) : decoder({dbc, dbc}, {0, 1}, "dashkit") {}

    double feed(uint32_t id, int start, int len, uint64_t value) {
        uint8_t f[8] = {};
        setBits(f, start, len, value);
        decoder.updateFrame(0, id, f, 8);
        decoder.updateMapper();
        return decoder.state().adasOn;
    }
    double accState(uint64_t v) { return feed(0x2B9, 12, 4, v); }        // DAS_control
    double autopilotState(uint64_t v) { return feed(0x399, 0, 4, v); }   // DAS_status
    double lssState(uint64_t v) { return feed(0x389, 31, 3, v); }        // DAS_status2
};

enum Acc { ACC_CANCEL_GENERIC = 0, ACC_HOLD = 3, ACC_ON = 4, ACC_CANCEL_GENERIC_SILENT = 13 };
enum Ap { UNAVAILABLE = 1, AVAILABLE = 2, ACTIVE_NOMINAL = 3, ACTIVE_RESTRICTED = 4, ACTIVE_NAV = 5, ABORTING = 8 };
enum Lss { LDW = 1, LKA = 2, ELK = 3, OFF = 7 };

void tacc_alone_counts_as_assist(const std::string& dbc) {
    // openpilot: the car only sees TACC; Autosteer stays AVAILABLE.
    Harness h(dbc);
    h.autopilotState(AVAILABLE);
    CHECK(h.accState(ACC_ON) == 1);
    CHECK(h.accState(ACC_HOLD) == 1);
    CHECK(h.accState(ACC_CANCEL_GENERIC_SILENT) == 0);
    CHECK(h.accState(ACC_CANCEL_GENERIC) == 0);
}

void autosteer_counts_as_assist(const std::string& dbc) {
    Harness h(dbc);
    h.accState(ACC_CANCEL_GENERIC);
    CHECK(h.autopilotState(ACTIVE_NOMINAL) == 1);
    CHECK(h.autopilotState(ACTIVE_RESTRICTED) == 1);
    CHECK(h.autopilotState(ACTIVE_NAV) == 1);
    CHECK(h.autopilotState(ABORTING) == 0);
    CHECK(h.autopilotState(AVAILABLE) == 0);
}

void lane_safety_no_longer_counts_once_das_control_is_seen(const std::string& dbc) {
    Harness h(dbc);
    h.accState(ACC_CANCEL_GENERIC);
    h.autopilotState(UNAVAILABLE);
    CHECK(h.lssState(OFF) == 0);   // lane assist switched off
    CHECK(h.lssState(ELK) == 0);   // a lane-keep intervention without TACC
    CHECK(h.lssState(LKA) == 0);
}

void older_firmware_without_das_control_keeps_the_lss_rule(const std::string& dbc) {
    Harness h(dbc);
    CHECK(h.lssState(LDW) == 0);
    CHECK(h.lssState(ELK) == 1);
}

}  // namespace

int main(int argc, char** argv) {
    if (argc != 2) return 2;
    std::ifstream in(argv[1]);
    if (!in) {
        std::printf("cannot open %s\n", argv[1]);
        return 2;
    }
    const std::string dbc{std::istreambuf_iterator<char>(in), {}};

    tacc_alone_counts_as_assist(dbc);
    autosteer_counts_as_assist(dbc);
    lane_safety_no_longer_counts_once_das_control_is_seen(dbc);
    older_firmware_without_das_control_keeps_the_lss_rule(dbc);

    if (failures) {
        std::printf("%d check(s) failed\n", failures);
        return 1;
    }
    std::printf("tesla_adas_mapper_test: all checks passed\n");
    return 0;
}
