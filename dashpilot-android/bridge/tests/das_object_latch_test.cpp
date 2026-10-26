// Host-run unit tests for DasObjectLatch and its TeslaDashKitMapper wiring
// (repo/bridge/car/cars/tesla.h). Built by CMakeLists.txt when not configuring
// for Android:
//   cmake -B build-host -S . && cmake --build build-host && ctest --test-dir build-host

#include "car/cars/tesla.h"

#include <cmath>
#include <cstdio>
#include <string>

static int failures = 0;

#define CHECK(cond)                                                          \
    do {                                                                     \
        if (!(cond)) {                                                       \
            std::printf("FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond);      \
            failures++;                                                      \
        }                                                                    \
    } while (0)

namespace {

using Clock = std::chrono::steady_clock;

// DAS_object mux groups 0-2 as defined in bus_1_tesla_vehicle.dbc, plus
// DI_speed as unrelated chassis-bus traffic that keeps the mapper running.
const char* kChassisDbc =
    "BO_ 777 DAS_object: 8 CH\n"
    " SG_ DAS_objectId M: 0|3@1+ (1,0) [0|0] \"\" X\n"
    " SG_ DAS_leadVehType m0: 3|3@1+ (1,0) [0|0] \"\" X\n"
    " SG_ DAS_leadVehDx m0: 8|8@1+ (0.5,0) [0|0] \"m\" X\n"
    " SG_ DAS_leadVehDy m0: 20|7@1+ (0.35,-22.05) [0|0] \"m\" X\n"
    " SG_ DAS_leftVehType m1: 3|3@1+ (1,0) [0|0] \"\" X\n"
    " SG_ DAS_leftVehDx m1: 8|8@1+ (0.5,0) [0|0] \"m\" X\n"
    " SG_ DAS_leftVehDy m1: 20|7@1+ (0.35,-22.05) [0|0] \"m\" X\n"
    " SG_ DAS_rightVehType m2: 3|3@1+ (1,0) [0|0] \"\" X\n"
    " SG_ DAS_rightVehDx m2: 8|8@1+ (0.5,0) [0|0] \"m\" X\n"
    " SG_ DAS_rightVehDy m2: 20|7@1+ (0.35,-22.05) [0|0] \"m\" X\n"
    "\n"
    "BO_ 599 DI_speed: 8 VEH\n"
    " SG_ DI_uiSpeed : 24|9@1+ (1,0) [0|510] \"\" X\n";

void setBits(std::string& frame, int start, int len, uint32_t raw) {
    for (int i = 0; i < len; i++) {
        int bit = start + i;
        auto& byte = reinterpret_cast<uint8_t&>(frame[bit / 8]);
        if (raw & (1u << i)) byte |= 1u << (bit % 8);
        else byte &= ~(1u << (bit % 8));
    }
}

// rawDx/rawDy/type are unscaled; dx = rawDx * 0.5, dy = rawDy * 0.35 - 22.05.
std::string objectFrame(uint32_t mux, uint32_t type, uint32_t rawDx, uint32_t rawDy) {
    std::string frame(8, '\0');
    setBits(frame, 0, 3, mux);
    setBits(frame, 3, 3, type);
    setBits(frame, 8, 8, rawDx);
    setBits(frame, 20, 7, rawDy);
    return frame;
}

bool near(double a, double b) { return std::fabs(a - b) < 1e-6; }

struct Harness {
    CANParsers cp;
    DasObjectLatch latch;
    CarState cs;
    Clock::time_point now = Clock::time_point{} + std::chrono::seconds(100);

    Harness() {
        cp.addBus(0, kChassisDbc);
        cp.buildCache();
    }

    void feed(const std::string& data, uint32_t address = 777) {
        cp.updateFrame(0, address, reinterpret_cast<const uint8_t*>(data.data()), data.size());
        latch.update(cp, cs, now);
    }

    void feedOther() { feed(std::string(8, '\0'), 599); }
    void advance(int ms) { now += std::chrono::milliseconds(ms); }
};

void no_frames_means_no_vehicles() {
    Harness h;
    h.feedOther();
    CHECK(h.cs.leadVehDx == -1.0);
    CHECK(h.cs.leftVehDx == -1.0);
    CHECK(h.cs.rightVehDx == -1.0);
}

void latches_each_group_across_frames() {
    Harness h;
    h.feed(objectFrame(0, 2, 60, 63));   // lead: car, 30 m, dy 0
    h.feed(objectFrame(1, 1, 20, 73));   // left: truck, 10 m, dy 3.5
    h.feed(objectFrame(2, 3, 9, 53));    // right: motorcycle, 4.5 m, dy -3.5
    CHECK(near(h.cs.leadVehDx, 30.0));
    CHECK(near(h.cs.leadVehDy, 0.0));
    CHECK(h.cs.leadVehType == 2.0);
    CHECK(near(h.cs.leftVehDx, 10.0));
    CHECK(near(h.cs.leftVehDy, 3.5));
    CHECK(h.cs.leftVehType == 1.0);
    CHECK(near(h.cs.rightVehDx, 4.5));
    CHECK(near(h.cs.rightVehDy, -3.5));
    CHECK(h.cs.rightVehType == 3.0);
}

void sna_distance_clears_the_slot() {
    Harness h;
    h.feed(objectFrame(1, 2, 20, 73));
    CHECK(near(h.cs.leftVehDx, 10.0));
    h.feed(objectFrame(1, 0, 255, 0));
    CHECK(h.cs.leftVehDx == -1.0);
    CHECK(h.cs.leftVehType == 0.0);
}

void expires_when_group_stops_refreshing_despite_other_traffic() {
    Harness h;
    h.feed(objectFrame(0, 2, 60, 63));
    h.advance(400);
    h.feedOther();
    CHECK(near(h.cs.leadVehDx, 30.0));
    h.advance(200);  // 600 ms since the last DAS_object frame
    h.feedOther();
    CHECK(h.cs.leadVehDx == -1.0);
}

void other_groups_do_not_refresh_a_slot() {
    Harness h;
    h.feed(objectFrame(0, 2, 60, 63));
    for (int i = 0; i < 6; i++) {
        h.advance(100);
        h.feed(objectFrame(1, 2, 20, 73));
    }
    CHECK(h.cs.leadVehDx == -1.0);
    CHECK(near(h.cs.leftVehDx, 10.0));
}

void dashkit_mapper_publishes_through_the_double_array() {
    CANParsers cp;
    cp.addBus(0, kChassisDbc);
    cp.buildCache();
    TeslaDashKitMapper mapper;
    CarState cs;
    std::string frame = objectFrame(1, 2, 20, 73);
    cp.updateFrame(0, 777, reinterpret_cast<const uint8_t*>(frame.data()), frame.size());
    mapper.update(cp, cs);

    double out[CarState::FIELD_COUNT];
    cs.toArray(out);
    CHECK(out[36] == -1.0);  // leadVehDx
    CHECK(near(out[39], 10.0));  // leftVehDx
    CHECK(near(out[40], 3.5));   // leftVehDy
    CHECK(out[41] == 2.0);       // leftVehType
    CHECK(out[42] == -1.0);      // rightVehDx
}

} // namespace

int main() {
    no_frames_means_no_vehicles();
    latches_each_group_across_frames();
    sna_distance_clears_the_slot();
    expires_when_group_stops_refreshing_despite_other_traffic();
    other_groups_do_not_refresh_a_slot();
    dashkit_mapper_publishes_through_the_double_array();

    if (failures > 0) {
        std::printf("%d check(s) failed\n", failures);
        return 1;
    }
    std::printf("all das_object_latch tests passed\n");
    return 0;
}
