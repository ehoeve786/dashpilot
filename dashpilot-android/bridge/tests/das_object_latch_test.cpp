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
    " SG_ DAS_leadVehVxRel m0: 16|4@1+ (4,-30) [0|0] \"m/s\" X\n"
    " SG_ DAS_cutinVehType m3: 3|3@1+ (1,0) [0|0] \"\" X\n"
    " SG_ DAS_cutinVehDx m3: 8|8@1+ (0.5,0) [0|0] \"m\" X\n"
    " SG_ DAS_cutinVehVxRel m3: 16|4@1+ (4,-30) [0|0] \"m/s\" X\n"
    " SG_ DAS_cutinVehDy m3: 20|7@1+ (0.35,-22.05) [0|0] \"m\" X\n"
    " SG_ DAS_roadSignColor m4: 3|3@1+ (1,0) [0|0] \"\" X\n"
    " SG_ DAS_roadSignId m4: 6|8@1+ (1,0) [0|0] \"\" X\n"
    " SG_ DAS_roadSignStopLineDist m4: 14|10@1+ (0.2,-20) [0|0] \"m\" X\n"
    " SG_ DAS_roadSignControlActive m4: 24|1@1+ (1,0) [0|0] \"\" X\n"
    " SG_ DAS_roadSignArrow m4: 27|3@1+ (1,0) [0|0] \"\" X\n"
    " SG_ DAS_leadVehHeading m5: 8|8@1+ (0.0245437,-3.14159) [0|0] \"rad\" X\n"
    "\n"
    "BO_ 569 DAS_lanes: 8 CH\n"
    " SG_ DAS_virtualLaneWidth: 4|4@1+ (0.3125,2) [0|0] \"m\" X\n"
    " SG_ DAS_virtualLaneViewRange: 8|8@1+ (1,0) [0|0] \"m\" X\n"
    " SG_ DAS_virtualLaneC0: 16|8@1+ (3.5,-350) [0|0] \"cm\" X\n"
    " SG_ DAS_virtualLaneC1: 24|8@1+ (0.092,-11.46) [0|0] \"deg\" X\n"
    " SG_ DAS_virtualLaneC2: 32|8@1+ (2e-05,-0.0025) [0|0] \"m-1\" X\n"
    " SG_ DAS_virtualLaneC3: 40|8@1+ (2.4e-07,-3e-05) [0|0] \"m-2\" X\n"
    "\n"
    "BO_ 921 DAS_status: 8 CH\n"
    " SG_ DAS_forwardCollisionWarning: 22|2@1+ (1,0) [0|0] \"\" X\n"
    " SG_ DAS_sideCollisionWarning: 32|2@1+ (1,0) [0|0] \"\" X\n"
    " SG_ DAS_laneDepartureWarning: 37|3@1+ (1,0) [0|0] \"\" X\n"
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

bool near(double a, double b, double tol = 1e-6) { return std::fabs(a - b) < tol; }

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

    void feedLanes(const std::string& data) {
        cp.updateFrame(0, 569, reinterpret_cast<const uint8_t*>(data.data()), data.size());
        lanes.update(cp, cs, now);
    }

    DasLanesLatch lanes;
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

void sna_distance_clears_the_slot_after_the_window() {
    Harness h;
    h.feed(objectFrame(1, 2, 20, 73));
    CHECK(near(h.cs.leftVehDx, 10.0));
    // A dropout of a frame or two holds the last reading...
    for (int i = 0; i < 4; i++) {
        h.advance(100);
        h.feed(objectFrame(1, 0, 255, 0));
    }
    CHECK(near(h.cs.leftVehDx, 10.0));
    CHECK(h.cs.leftVehType == 2.0);
    // ...but SNA for longer than the window (500 ms at 10 Hz) clears it.
    for (int i = 0; i < 2; i++) {
        h.advance(100);
        h.feed(objectFrame(1, 0, 255, 0));
    }
    CHECK(h.cs.leftVehDx == -1.0);
    CHECK(h.cs.leftVehType == 0.0);
}

void slow_group_cadence_does_not_blink() {
    Harness h;
    // Before the cadence is known the window is the 2 s maximum.
    // Lead group about once a second, other traffic in between.
    for (int i = 0; i < 4; i++) {
        h.feed(objectFrame(0, 2, 60, 63));
        for (int j = 0; j < 9; j++) {
            h.advance(100);
            h.feedOther();
            CHECK(near(h.cs.leadVehDx, 30.0));
        }
        h.advance(100);
    }
    // Window is capped: gone 2 s after the last frame.
    h.advance(1100);
    h.feedOther();
    CHECK(h.cs.leadVehDx == -1.0);
}

void expires_when_group_stops_refreshing_despite_other_traffic() {
    Harness h;
    for (int i = 0; i < 3; i++) {  // 10 Hz cadence: 500 ms window
        h.feed(objectFrame(0, 2, 60, 63));
        h.advance(100);
    }
    h.advance(300);
    h.feedOther();
    CHECK(near(h.cs.leadVehDx, 30.0));
    h.advance(200);  // 600 ms since the last DAS_object frame
    h.feedOther();
    CHECK(h.cs.leadVehDx == -1.0);
}

void other_groups_do_not_refresh_a_slot() {
    Harness h;
    h.feed(objectFrame(0, 2, 60, 63));
    for (int i = 0; i < 21; i++) {  // past the 2 s window of an unknown cadence
        h.advance(100);
        h.feed(objectFrame(1, 2, 20, 73));
    }
    CHECK(h.cs.leadVehDx == -1.0);
    CHECK(near(h.cs.leftVehDx, 10.0));
}

void latches_cutin_road_sign_and_headings() {
    Harness h;
    h.feed(objectFrame(3, 2, 30, 63));  // cut-in car at 15 m
    std::string sign(8, '\0');
    setBits(sign, 0, 3, 4);
    setBits(sign, 3, 3, 3);      // green
    setBits(sign, 6, 8, 1);      // traffic light
    setBits(sign, 14, 10, 200);  // 200 * 0.2 - 20 = 20 m
    setBits(sign, 27, 3, 1);     // left arrow
    h.feed(sign);
    std::string headings(8, '\0');
    setBits(headings, 0, 3, 5);
    setBits(headings, 8, 8, 148);  // 148 * 0.0245437 - 3.14159 = 0.4908 rad
    h.feed(headings);
    h.feed(objectFrame(0, 2, 60, 63));

    CHECK(near(h.cs.cutinVehDx, 15.0));
    CHECK(h.cs.cutinVehType == 2.0);
    CHECK(h.cs.roadSignId == 1.0);
    CHECK(h.cs.roadSignColor == 3.0);
    CHECK(h.cs.roadSignArrow == 1.0);
    CHECK(near(h.cs.roadSignStopLineDist, 20.0, 1e-3));
    CHECK(near(h.cs.leadVehHeading, 0.4908, 1e-3));
    CHECK(h.cs.leftVehHeading == 0.0);  // no left vehicle
}

void road_sign_sna_and_staleness() {
    Harness h;
    std::string sign(8, '\0');
    setBits(sign, 0, 3, 4);
    setBits(sign, 6, 8, 0);       // stop sign
    setBits(sign, 14, 10, 1023);  // stop line SNA
    h.feed(sign);
    CHECK(h.cs.roadSignId == 0.0);
    CHECK(h.cs.roadSignStopLineDist == -1.0);
    h.advance(100);
    h.feed(sign);  // 10 Hz cadence: 500 ms window
    h.advance(600);
    h.feedOther();
    CHECK(h.cs.roadSignId == -1.0);
}

void lanes_convert_to_si_and_expire() {
    Harness h;
    std::string lanes(8, '\0');
    setBits(lanes, 4, 4, 5);      // 2 + 5 * 0.3125 = 3.5625 m
    setBits(lanes, 8, 8, 60);     // 60 m view range
    setBits(lanes, 16, 8, 110);   // 110 * 3.5 - 350 = 35 cm
    setBits(lanes, 24, 8, 135);   // 135 * 0.092 - 11.46 = 0.96 deg
    setBits(lanes, 32, 8, 175);   // 175 * 2e-5 - 0.0025 = 0.001 1/m
    setBits(lanes, 40, 8, 125);   // 125 * 2.4e-7 - 3e-5 = 0
    h.feedLanes(lanes);
    CHECK(near(h.cs.laneWidth, 3.5625));
    CHECK(near(h.cs.laneViewRange, 60.0));
    CHECK(near(h.cs.laneC0, 0.35, 1e-9));
    CHECK(near(h.cs.laneC1, std::tan(0.96 * 3.14159265358979 / 180.0), 1e-9));
    CHECK(near(h.cs.laneC2, 0.001, 1e-9));
    CHECK(near(h.cs.laneC3, 0.0, 1e-12));
    h.advance(1100);
    h.lanes.update(h.cp, h.cs, h.now);
    CHECK(h.cs.laneViewRange == 0.0);
    CHECK(h.cs.laneC2 == 0.0);
}

void dashkit_mapper_reads_das_status_warnings() {
    CANParsers cp;
    cp.addBus(0, kChassisDbc);
    cp.buildCache();
    TeslaDashKitMapper mapper;
    CarState cs;
    std::string status(8, '\0');
    setBits(status, 22, 2, 1);  // FCW
    setBits(status, 32, 2, 2);  // side collision right
    setBits(status, 37, 3, 3);  // lane departure left, severe
    cp.updateFrame(0, 921, reinterpret_cast<const uint8_t*>(status.data()), status.size());
    mapper.update(cp, cs);
    CHECK(cs.forwardCollisionWarning == 1.0);
    CHECK(cs.sideCollisionWarning == 2.0);
    CHECK(cs.laneDepartureWarning == 3.0);

    setBits(status, 22, 2, 3);  // FCW SNA
    setBits(status, 37, 3, 5);  // LDW SNA
    cp.updateFrame(0, 921, reinterpret_cast<const uint8_t*>(status.data()), status.size());
    mapper.update(cp, cs);
    CHECK(cs.forwardCollisionWarning == 0.0);
    CHECK(cs.laneDepartureWarning == 0.0);
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
    CHECK(out[45] == -1.0);      // cutinVehDx
    CHECK(out[56] == -1.0);      // roadSignId
    CHECK(out[62] == 0.0);       // laneViewRange
    CHECK(CarState::VIN_OFFSET == 70 && CarState::FIELD_COUNT == 73);
}

} // namespace

int main() {
    no_frames_means_no_vehicles();
    latches_each_group_across_frames();
    sna_distance_clears_the_slot_after_the_window();
    slow_group_cadence_does_not_blink();
    expires_when_group_stops_refreshing_despite_other_traffic();
    other_groups_do_not_refresh_a_slot();
    latches_cutin_road_sign_and_headings();
    road_sign_sna_and_staleness();
    lanes_convert_to_si_and_expire();
    dashkit_mapper_reads_das_status_warnings();
    dashkit_mapper_publishes_through_the_double_array();

    if (failures > 0) {
        std::printf("%d check(s) failed\n", failures);
        return 1;
    }
    std::printf("all das_object_latch tests passed\n");
    return 0;
}
