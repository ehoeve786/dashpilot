// Host-run unit tests for TpmsLatch, DasLanesLatch and the DAS_status warnings
// in TeslaDashKitMapper
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

// Messages as defined in bus_1_tesla_vehicle.dbc, plus DI_speed as unrelated
// chassis-bus traffic that keeps the mapper running.
const char* kChassisDbc =
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

const char* kVehicleDbc =
    "BO_ 537 VCSEC_TPMSData: 5 CH\n"
    " SG_ VCSEC_TPMSDataIndex M : 0|2@1+ (1,0) [0|3] \"\" X\n"
    " SG_ VCSEC_TPMSLocation0 m0 : 32|3@1+ (1,0) [0|4] \"\" X\n"
    " SG_ VCSEC_TPMSLocation1 m1 : 32|3@1+ (1,0) [0|4] \"\" X\n"
    " SG_ VCSEC_TPMSLocation2 m2 : 32|3@1+ (1,0) [0|4] \"\" X\n"
    " SG_ VCSEC_TPMSLocation3 m3 : 32|3@1+ (1,0) [0|4] \"\" X\n"
    " SG_ VCSEC_TPMSPressure0 m0 : 8|8@1+ (0.025,0) [0|6.35] \"bar\" X\n"
    " SG_ VCSEC_TPMSPressure1 m1 : 8|8@1+ (0.025,0) [0|6.35] \"bar\" X\n"
    " SG_ VCSEC_TPMSPressure2 m2 : 8|8@1+ (0.025,0) [0|6.35] \"bar\" X\n"
    " SG_ VCSEC_TPMSPressure3 m3 : 8|8@1+ (0.025,0) [0|6.35] \"bar\" X\n"
    " SG_ VCSEC_TPMSTemperature0 m0 : 16|8@1+ (1,-40) [-40|214] \"C\" X\n"
    " SG_ VCSEC_TPMSTemperature1 m1 : 16|8@1+ (1,-40) [-40|214] \"C\" X\n"
    " SG_ VCSEC_TPMSTemperature2 m2 : 16|8@1+ (1,-40) [-40|214] \"C\" X\n"
    " SG_ VCSEC_TPMSTemperature3 m3 : 16|8@1+ (1,-40) [-40|214] \"C\" X\n";

void setBits(std::string& frame, int start, int len, uint32_t raw) {
    for (int i = 0; i < len; i++) {
        int bit = start + i;
        auto& byte = reinterpret_cast<uint8_t&>(frame[bit / 8]);
        if (raw & (1u << i)) byte |= 1u << (bit % 8);
        else byte &= ~(1u << (bit % 8));
    }
}

// One TPMS sensor slot; rawPressure * 0.025 bar, rawTemp - 40 degC.
std::string tpmsFrame(uint32_t index, uint32_t location, uint32_t rawPressure, uint32_t rawTemp) {
    std::string frame(5, '\0');
    setBits(frame, 0, 2, index);
    setBits(frame, 8, 8, rawPressure);
    setBits(frame, 16, 8, rawTemp);
    setBits(frame, 32, 3, location);
    return frame;
}

bool near(double a, double b, double tol = 1e-6) { return std::fabs(a - b) < tol; }

struct Harness {
    CANParsers cp;
    TpmsLatch tpms;
    DasLanesLatch lanes;
    CarState cs;
    Clock::time_point now = Clock::time_point{} + std::chrono::seconds(100);

    Harness() {
        cp.addBus(0, kChassisDbc);
        cp.addBus(1, kVehicleDbc);
        cp.buildCache();
    }

    void feedTpms(const std::string& data) {
        cp.updateFrame(1, 537, reinterpret_cast<const uint8_t*>(data.data()), data.size());
        tpms.update(cp, cs);
    }

    void feedLanes(const std::string& data) {
        cp.updateFrame(0, 569, reinterpret_cast<const uint8_t*>(data.data()), data.size());
        lanes.update(cp, cs, now);
    }

    void advance(int ms) { now += std::chrono::milliseconds(ms); }
};

void tpms_unknown_until_first_reading() {
    Harness h;
    h.feedTpms(tpmsFrame(0, 0, 116, 65));
    CHECK(near(h.cs.tpmsPressureFL, 2.9));
    CHECK(h.cs.tpmsTempFL == 25.0);
    CHECK(h.cs.tpmsPressureFR == -1.0);
    CHECK(h.cs.tpmsTempRR == -100.0);
}

void tpms_maps_sensors_by_location_and_keeps_them() {
    Harness h;
    // Sensor slots don't have to match wheel positions.
    h.feedTpms(tpmsFrame(0, 3, 112, 70));  // RR 2.8 bar 30 C
    h.feedTpms(tpmsFrame(1, 2, 114, 68));  // RL 2.85 bar 28 C
    h.feedTpms(tpmsFrame(2, 1, 88, 60));   // FR 2.2 bar 20 C
    h.feedTpms(tpmsFrame(3, 0, 116, 65));  // FL 2.9 bar 25 C
    CHECK(near(h.cs.tpmsPressureRR, 2.8));
    CHECK(near(h.cs.tpmsPressureRL, 2.85));
    CHECK(near(h.cs.tpmsPressureFR, 2.2));
    CHECK(near(h.cs.tpmsPressureFL, 2.9));
    CHECK(h.cs.tpmsTempRR == 30.0);
    CHECK(h.cs.tpmsTempFR == 20.0);
    // A later reading of one wheel leaves the others alone.
    h.feedTpms(tpmsFrame(2, 1, 80, 61));
    CHECK(near(h.cs.tpmsPressureFR, 2.0));
    CHECK(near(h.cs.tpmsPressureFL, 2.9));
}

void tpms_ignores_sna_and_unlocated_sensors() {
    Harness h;
    h.feedTpms(tpmsFrame(0, 0, 116, 65));
    h.feedTpms(tpmsFrame(0, 0, 255, 255));  // SNA keeps the last reading
    CHECK(near(h.cs.tpmsPressureFL, 2.9));
    CHECK(h.cs.tpmsTempFL == 25.0);
    h.feedTpms(tpmsFrame(1, 4, 40, 65));    // location unknown
    CHECK(h.cs.tpmsPressureFR == -1.0);
    CHECK(h.cs.tpmsPressureRL == -1.0);
    CHECK(h.cs.tpmsPressureRR == -1.0);
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
    cp.addBus(1, kVehicleDbc);
    cp.buildCache();
    TeslaDashKitMapper mapper;
    CarState cs;
    std::string frame = tpmsFrame(0, 1, 112, 70);  // FR 2.8 bar 30 C
    cp.updateFrame(1, 537, reinterpret_cast<const uint8_t*>(frame.data()), frame.size());
    mapper.update(cp, cs);

    double out[CarState::FIELD_COUNT];
    cs.toArray(out);
    CHECK(out[36] == -1.0);         // tpmsPressureFL
    CHECK(near(out[37], 2.8));      // tpmsPressureFR
    CHECK(out[41] == 30.0);         // tpmsTempFR
    CHECK(out[43] == -100.0);       // tpmsTempRR
    CHECK(out[45] == 0.0);          // laneViewRange
    CHECK(CarState::VIN_OFFSET == 53 && CarState::FIELD_COUNT == 56);
}

} // namespace

int main() {
    tpms_unknown_until_first_reading();
    tpms_maps_sensors_by_location_and_keeps_them();
    tpms_ignores_sna_and_unlocated_sensors();
    lanes_convert_to_si_and_expire();
    dashkit_mapper_reads_das_status_warnings();
    dashkit_mapper_publishes_through_the_double_array();

    if (failures > 0) {
        std::printf("%d check(s) failed\n", failures);
        return 1;
    }
    std::printf("all tesla_dashkit tests passed\n");
    return 0;
}
