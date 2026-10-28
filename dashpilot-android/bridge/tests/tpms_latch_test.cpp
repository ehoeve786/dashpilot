// Host-run unit tests for the VCSEC_TPMSData (0x219) decoder, TpmsLatch in
// repo/bridge/car/cars/tesla.h. Built by CMakeLists.txt when not configuring
// for Android:
//   cmake -B build-host -S . && cmake --build build-host && ctest --test-dir build-host

#include "car/cars/tesla.h"

#include <cmath>
#include <cstdio>
#include <cstring>
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

using Clock = TpmsLatch::Clock;
using std::chrono::seconds;

// VCSEC_TPMSData as defined in bus_1_tesla_vehicle.dbc (7-byte layout).
const char* kTpmsDbc =
    "BO_ 537 VCSEC_TPMSData: 7 CH\n"
    " SG_ VCSEC_TPMSDataIndex M : 0|3@1+ (1,0) [0|5] \"\"  Receiver\n"
    " SG_ VCSEC_TPMSPressureFL m5 : 16|8@1+ (0.025,0) [0|6.375] \"bar\" X\n"
    " SG_ VCSEC_TPMSPressureFR m5 : 24|8@1+ (0.025,0) [0|6.375] \"bar\" X\n"
    " SG_ VCSEC_TPMSPressureRL m5 : 32|8@1+ (0.025,0) [0|6.375] \"bar\" X\n"
    " SG_ VCSEC_TPMSPressureRR m5 : 40|8@1+ (0.025,0) [0|6.375] \"bar\" X\n"
    " SG_ VCSEC_TPMSPressure0 m0 : 16|8@1+ (0.05,-1) [-1|11.75] \"bar\" X\n"
    " SG_ VCSEC_TPMSPressure1 m1 : 16|8@1+ (0.05,-1) [-1|11.75] \"bar\" X\n"
    " SG_ VCSEC_TPMSPressure2 m2 : 16|8@1+ (0.05,-1) [-1|11.75] \"bar\" X\n"
    " SG_ VCSEC_TPMSPressure3 m3 : 16|8@1+ (0.05,-1) [-1|11.75] \"bar\" X\n"
    " SG_ VCSEC_TPMSTemperature0 m0 : 24|8@1+ (1,-40) [-40|215] \"C\" X\n"
    " SG_ VCSEC_TPMSTemperature1 m1 : 24|8@1+ (1,-40) [-40|215] \"C\" X\n"
    " SG_ VCSEC_TPMSTemperature2 m2 : 24|8@1+ (1,-40) [-40|215] \"C\" X\n"
    " SG_ VCSEC_TPMSTemperature3 m3 : 24|8@1+ (1,-40) [-40|215] \"C\" X\n"
    " SG_ VCSEC_TPMSBatVoltage0 m0 : 32|8@1+ (0.01,1.5) [1.5|4.04] \"V\" X\n"
    " SG_ VCSEC_TPMSBatVoltage1 m1 : 32|8@1+ (0.01,1.5) [1.5|4.04] \"V\" X\n"
    " SG_ VCSEC_TPMSBatVoltage2 m2 : 32|8@1+ (0.01,1.5) [1.5|4.04] \"V\" X\n"
    " SG_ VCSEC_TPMSBatVoltage3 m3 : 32|8@1+ (0.01,1.5) [1.5|4.04] \"V\" X\n";

// Feeds one 0x219 frame into the parser and returns the updated latch state.
void feed(CANParsers& cp, TpmsLatch& latch, const std::string& frame, Clock::time_point t) {
    cp.updateFrame(1, 0x219, reinterpret_cast<const uint8_t*>(frame.data()), frame.size());
    latch.update(cp, t);
}

std::string frame(uint8_t b0, uint8_t b1, uint8_t b2, uint8_t b3, uint8_t b4, uint8_t b5, uint8_t b6) {
    return std::string{static_cast<char>(b0), static_cast<char>(b1), static_cast<char>(b2),
                       static_cast<char>(b3), static_cast<char>(b4), static_cast<char>(b5),
                       static_cast<char>(b6)};
}

bool near(double a, double b) { return std::fabs(a - b) < 1e-6; }

// Mux 5 frame from the car: FL 110, FR 112, RL 112, RR 112 raw (0.025 bar each).
void mux5_reads_all_four_wheels() {
    CANParsers cp; cp.addBus(1, kTpmsDbc); cp.buildCache();
    TpmsLatch latch; Clock::time_point t0{};
    feed(cp, latch, frame(0x05, 0x00, 0x6e, 0x70, 0x70, 0x70, 0x00), t0);
    CHECK(near(latch.pressureBar(0, t0), 110 * 0.025));  // FL
    CHECK(near(latch.pressureBar(1, t0), 112 * 0.025));  // FR
    CHECK(near(latch.pressureBar(2, t0), 112 * 0.025));  // RL
    CHECK(near(latch.pressureBar(3, t0), 112 * 0.025));  // RR
}

// Mux 0 is the RR sensor (raw 0x4c pressure, 0x39 temp), mux 3 is FL.
void per_sensor_mux_maps_to_wheel() {
    CANParsers cp; cp.addBus(1, kTpmsDbc); cp.buildCache();
    TpmsLatch latch; Clock::time_point t0{};
    feed(cp, latch, frame(0x00, 0x00, 0x4c, 0x39, 0x97, 0xb3, 0x04), t0);
    CHECK(near(latch.pressureBar(3, t0), 0x4c * 0.05 - 1));  // RR
    CHECK(near(latch.tempC(3, t0), 0x39 - 40.0));
    feed(cp, latch, frame(0x03, 0x00, 0x4d, 0x3a, 0x97, 0xb3, 0x04), t0);
    CHECK(near(latch.pressureBar(0, t0), 0x4d * 0.05 - 1));  // FL
    CHECK(near(latch.tempC(0, t0), 0x3a - 40.0));
}

// Raw 255 is "no reading" on both the mux 5 and the per-sensor scalings.
void sna_values_are_rejected() {
    CANParsers cp; cp.addBus(1, kTpmsDbc); cp.buildCache();
    TpmsLatch latch; Clock::time_point t0{};
    feed(cp, latch, frame(0x05, 0x00, 0xff, 0x70, 0x70, 0x70, 0x00), t0);
    CHECK(latch.pressureBar(0, t0) < 0);                 // FL SNA: no reading
    CHECK(near(latch.pressureBar(1, t0), 112 * 0.025));  // others still valid
    feed(cp, latch, frame(0x02, 0x00, 0xff, 0xff, 0x97, 0xb3, 0x04), t0);
    // SNA does not replace the last valid FR pressure (from mux 5) nor refresh its timer.
    CHECK(near(latch.pressureBar(1, t0), 112 * 0.025));
    CHECK(latch.tempC(1, t0) == -100);                   // no valid FR temperature yet
}

// Mux 4 is not a wheel reading and must not change any wheel.
void mux4_is_ignored() {
    CANParsers cp; cp.addBus(1, kTpmsDbc); cp.buildCache();
    TpmsLatch latch; Clock::time_point t0{};
    feed(cp, latch, frame(0x04, 0x74, 0x74, 0x00, 0x00, 0x00, 0x00), t0);
    for (int w = 0; w < 4; w++) CHECK(latch.pressureBar(w, t0) < 0);
}

// A re-read of the last frame (no new 0x219 frame) must not refresh the timer.
void reread_does_not_refresh_timer() {
    CANParsers cp; cp.addBus(1, kTpmsDbc); cp.buildCache();
    TpmsLatch latch; Clock::time_point t0{};
    feed(cp, latch, frame(0x05, 0x00, 0x6e, 0x70, 0x70, 0x70, 0x00), t0);
    latch.update(cp, t0 + seconds(61));  // same frame seq: nothing new
    CHECK(latch.pressureBar(0, t0 + seconds(61)) < 0);
}

// A new frame refreshes the reading; it expires STALE after the last refresh.
void new_frame_refreshes_reading() {
    CANParsers cp; cp.addBus(1, kTpmsDbc); cp.buildCache();
    TpmsLatch latch; Clock::time_point t0{};
    feed(cp, latch, frame(0x05, 0x00, 0x6e, 0x70, 0x70, 0x70, 0x00), t0);
    feed(cp, latch, frame(0x05, 0x00, 0x6e, 0x70, 0x70, 0x70, 0x00), t0 + seconds(50));
    CHECK(near(latch.pressureBar(0, t0 + seconds(100)), 110 * 0.025));  // 50 s after refresh
    CHECK(latch.pressureBar(0, t0 + seconds(111)) < 0);                 // 61 s after refresh
}

}  // namespace

int main() {
    mux5_reads_all_four_wheels();
    per_sensor_mux_maps_to_wheel();
    sna_values_are_rejected();
    mux4_is_ignored();
    reread_does_not_refresh_timer();
    new_frame_refreshes_reading();
    if (failures) {
        std::printf("%d failure(s)\n", failures);
        return 1;
    }
    std::printf("tpms_latch: all checks passed\n");
    return 0;
}
