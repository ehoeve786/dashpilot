// Host-run test for the Sport dashboard's driving data in the Tesla DashKit mapper (updateDrive in
// bridge/car/cars/tesla.h). Frames are real ones from a legacy Model Y's full-bus capture, decoded with the DBCs the
// app ships. Run by ctest with the chassis-bus and vehicle-bus DBC paths.

#include "common/vehicle_decoder.h"

#include <cmath>
#include <cstdio>
#include <fstream>
#include <iterator>
#include <string>
#include <vector>

static int failures = 0;
#define CHECK(cond)                                                          \
    do {                                                                     \
        if (!(cond)) {                                                       \
            std::printf("FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond);      \
            failures++;                                                      \
        }                                                                    \
    } while (0)

static bool near(double a, double b) { return std::fabs(a - b) < 1e-6; }

static std::vector<uint8_t> hex(const char* s) {
    std::vector<uint8_t> out;
    for (; s[0] && s[1]; s += 2) out.push_back(static_cast<uint8_t>(std::stoi(std::string(s, 2), nullptr, 16)));
    return out;
}

int main(int argc, char** argv) {
    if (argc != 3) return 1;
    std::ifstream chassisFile(argv[1]), vehicleFile(argv[2]);
    const std::string chassis{std::istreambuf_iterator<char>(chassisFile), {}};
    const std::string vehicle{std::istreambuf_iterator<char>(vehicleFile), {}};
    CHECK(!chassis.empty() && !vehicle.empty());
    VehicleDecoder decoder({chassis, vehicle}, {0, 1}, "dashkit");
    const auto& cs = decoder.state();
    auto feed = [&](int bus, uint32_t id, const char* data) {
        auto d = hex(data);
        decoder.updateFrame(bus, id, d.data(), d.size());
        decoder.updateMapper();
    };

    feed(1, 0x332, "f8197d061724");                 // BMS_bmbMinMax mux 0 (thermistors): cells untouched
    CHECK(cs.cellVoltMin == 0 && cs.cellVoltMax == 0);
    feed(1, 0x332, "f9197d061724");                 // mux 1
    CHECK(near(cs.cellVoltMin, 3.322) && near(cs.cellVoltMax, 3.324));
    feed(0, 0x321, "2a7aa8660266d00e");             // VCFRONT_sensors (chassis bus)
    CHECK(near(cs.coolantBatIn, 29.25) && near(cs.outsideTemp, 11.0));
    feed(1, 0x383, "971829004be0600c");             // VCRIGHT_thsStatus
    CHECK(cs.cabinHumidity == 20);
    feed(1, 0x2B3, "016d6c6f606b6e74");             // VCRIGHT_logging1Hz mux 1
    CHECK(near(cs.cabinTemp, 15.5));
    feed(1, 0x2B3, "016d6cf0606b6e74");             // raw 240 = SNA: keeps the last reading
    CHECK(near(cs.cabinTemp, 15.5));
    feed(1, 0x2B3, "026d6c10606b6e74");             // another mux: ignored
    CHECK(near(cs.cabinTemp, 15.5));
    feed(0, 0x111, "e6ffacff99e91034");             // RCM_inertial2
    CHECK(near(cs.accelLong, -0.0325) && near(cs.accelLat, -0.105));
    feed(0, 0x118, "ca6a950066c88004");             // DI_systemStatus
    CHECK(near(cs.accelPedal, 40.8));
    feed(1, 0x108, "00c20e1a0ade0025");             // DI_torque
    CHECK(cs.wheelTorque == 646);
    feed(1, 0x212, "1804004082ff07c4");             // BMS_status driving: charge power SNA
    CHECK(cs.chargeStatus == 2 && cs.chargeLimit == 0);
    feed(1, 0x212, "19dc4341130c01a6");             // Supercharging at 65 kW
    CHECK(cs.chargeStatus == 3 && near(cs.chargeLimit, 67.0));

    // DAS_road: stop line in metres, and what it belongs to.
    feed(0, 0x25D, "18160e384610");                 // stop sign, 14 m
    CHECK(cs.trafficControlType == 2 && cs.stopLineDist == 14 && cs.trafficLightColor == 0);
    feed(0, 0x25D, "181c1c2b4004");                 // traffic light, yellow, 28 m
    CHECK(cs.trafficControlType == 3 && cs.stopLineDist == 28 && cs.trafficLightColor == 3);
    feed(0, 0x25D, "0000fe000000");                 // nothing ahead
    CHECK(cs.trafficControlType == 0 && cs.stopLineDist == 254);

    // VCFRONT_vehicleStatus mux 1: battery heating power target (frames from a drive to a Supercharger).
    feed(1, 0x3A1, "29d240e53c005050");             // not heating
    CHECK(cs.batteryHeatPower == 0);
    // DASHKIT_status: the DashKit's preheat injection, unknown until it reports.
    CHECK(cs.dashkitPreheat == -1);
    feed(1, 0x7F3, "01");
    CHECK(cs.dashkitPreheat == 1);
    feed(1, 0x7F3, "00");
    CHECK(cs.dashkitPreheat == 0);
    // Bit 1: kick-down injecting Standard.
    CHECK(cs.dashkitKickdown == 0);
    feed(1, 0x7F3, "02");
    CHECK(cs.dashkitKickdown == 1 && cs.dashkitPreheat == 0);
    feed(1, 0x7F3, "03");
    CHECK(cs.dashkitKickdown == 1 && cs.dashkitPreheat == 1);
    feed(1, 0x3A1, "29fa408f3c88d02a");             // preconditioning, 6.8 kW
    CHECK(near(cs.batteryHeatPower, 6.8));
    feed(1, 0x3A1, "e84d00ca0078425d");             // mux 0 (other signals on the same bits): value kept
    CHECK(near(cs.batteryHeatPower, 6.8));
    CHECK(cs.ratedRange == 0);
    feed(1, 0x33A, "e2c049c2bf8f0000");             // 226 mi (bits 0-9)
    CHECK(near(cs.ratedRange, 226 * 1.609344));

    if (failures) {
        std::printf("%d check(s) failed\n", failures);
        return 1;
    }
    std::printf("tesla_drive_mapper_test: all checks passed\n");
    return 0;
}
