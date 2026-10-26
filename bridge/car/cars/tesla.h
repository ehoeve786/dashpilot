#pragma once

#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <string>

#include "car/car_state_mapper.h"

// Assembles CarState::vin from VIN_info (0x405) on the vehicle bus. The VIN
// arrives as three muxed segments in any order; each is read as unscaled bits
// because the 56-bit VIN signals are wider than double's 53-bit mantissa, so
// the normal cp.get() path would silently corrupt the top byte(s).
class TeslaVinUpdater {
public:
    void update(const CANParsers& cp, CarState& cs) {
        if (seen_ == kAllSegments) return;
        for (size_t i = 0; i < kSegments.size(); i++) {
            if (seen_ & (1u << i)) continue;
            const Segment& seg = kSegments[i];

            uint64_t raw;
            if (!cp.getRaw(1, "VIN_info", seg.signal, &raw)) continue;

            char chars[7];
            for (int b = 0; b < 7; b++) chars[b] = static_cast<char>(raw >> (8 * b));

            bool valid = true;
            for (int b = 0; b < seg.textStart; b++) valid &= chars[b] == '\0';
            for (int b = seg.textStart; b < 7; b++) valid &= isVinChar(chars[b]);
            if (!valid) continue;

            std::memcpy(pending_ + seg.vinOffset, chars + seg.textStart, 7 - seg.textStart);
            seen_ |= 1u << i;
        }
        if (seen_ == kAllSegments) {
            std::memcpy(cs.vin, pending_, CarState::VIN_LENGTH);
        }
    }

private:
    struct Segment {
        const char* signal;
        int textStart;   // first payload byte; earlier bytes must be zero
        int vinOffset;
    };

    static constexpr std::array<Segment, 3> kSegments{{
        {"VIN_A405", 4, 0},
        {"VIN_B405", 0, 3},
        {"VIN_C405", 0, 10},
    }};
    static constexpr uint8_t kAllSegments = 0b111;

    // ISO 3779: digits and letters except I, O, Q.
    static bool isVinChar(char c) {
        if (c >= '0' && c <= '9') return true;
        if (c >= 'A' && c <= 'Z') return c != 'I' && c != 'O' && c != 'Q';
        return false;
    }

    uint8_t seen_ = 0;
    char pending_[CarState::VIN_LENGTH] = {};
};

static inline void updatePartyBus(const CANParsers& cp, CarState& cs) {
    cs.egoSteeringAngle = cp.get(2, "SCCM_steeringAngleSensor", "SCCM_steeringAngle");
    cs.gear = cp.get(2, "DI_systemStatus", "DI_gear");
    cs.egoSpeed = cp.get(2, "DI_speed", "DI_uiSpeed");
    cs.adasOn = cp.get(2, "DI_state", "DI_cruiseState") == 2.0 ? 1.0 : 0.0;
    cs.leftBlindSpot = cp.get(2, "DAS_status", "DAS_blindSpotRearLeft");
    cs.rightBlindSpot = cp.get(2, "DAS_status", "DAS_blindSpotRearRight");
    cs.fusedSpeedLimit = cp.get(2, "DAS_status", "DAS_fusedSpeedLimit");
    cs.trafficLightColor = cp.get(2, "DAS_road", "DAS_trafficLightColor");
    cs.stopLineDist = cp.get(2, "DAS_road", "DAS_stopLineDist");
    cs.leftBlinker = cp.get(2, "UI_warning", "leftBlinkerBlinking");
    cs.rightBlinker = cp.get(2, "UI_warning", "rightBlinkerBlinking");
    cs.anyDoorOpen = cp.get(2, "UI_warning", "anyDoorOpen");
    cs.buckleStatus = cp.get(2, "UI_warning", "buckleStatus");
    cs.accSetSpeed = cp.get(2, "DI_state", "DI_digitalSpeed");
}

static inline double powerLevel(const CarState& cs) {
    if (cs.packVoltage <= 0 || cs.maxDischargePower <= 0 || cs.maxRegenPower <= 0) return 0;
    double kw = cs.packVoltage * cs.packCurrent / 1000.0;
    double limit = kw >= 0 ? cs.maxDischargePower : cs.maxRegenPower;
    return std::clamp(kw / limit, -1.0, 1.0) * 100.0;
}

static inline void updateVehicleBus(const CANParsers& cp, CarState& cs) {
    // Vehicle bus specific signals
    // BMS_energyStatus is multiplexed: mux 0 has pack/remaining energy, mux 1 has energyBuffer
    int muxIdx = static_cast<int>(cp.get(1, "BMS_energyStatus", "BMS_energyStatusIndex"));
    if (muxIdx == 0) {
        cs.fullPackEnergy = cp.get(1, "BMS_energyStatus", "BMS_nominalFullPackEnergy");
        cs.nominalEnergyRemaining = cp.get(1, "BMS_energyStatus", "BMS_nominalEnergyRemaining");
    } else if (muxIdx == 1) {
        cs.energyBuffer = cp.get(1, "BMS_energyStatus", "BMS_energyBuffer");
    }
    cs.maxRegenPower = cp.get(1, "BMS_powerAvailable", "BMS_maxRegenPower");
    cs.maxDischargePower = cp.get(1, "BMS_powerAvailable", "BMS_maxDischargePower");
    cs.packVoltage = cp.get(1, "BMS_hvBusStatus", "BMS_packVoltage");
    cs.packCurrent = cp.get(1, "BMS_hvBusStatus", "BMS_packCurrent");
    cs.powerLevel = powerLevel(cs);
    // BMS_bmbMinMax is multiplexed: mux 0 (THERM) carries the thermistor min/max
    // temps. Only update on that mux so other muxes don't zero out the last reading.
    if (static_cast<int>(cp.get(1, "BMS_bmbMinMax", "BMS_bmbMinMaxMultiplexer")) == 0) {
        cs.packTMin = cp.get(1, "BMS_bmbMinMax", "BMS_thermistorTMin");
        cs.packTMax = cp.get(1, "BMS_bmbMinMax", "BMS_thermistorTMax");
    }
    cs.odometer = cp.get(1, "DI_odometerStatus", "DI_odometer");
}


// Turns the lamp-only turn-signal status (0=OFF, 1=ON, 2=FAULT, 3=SNA) into
// the party-bus tri-state (0=off, 1=blinking/lamp off, 2=blinking/lamp on).
struct BlinkerHold {
    static constexpr std::chrono::milliseconds HOLD{1000};

    std::chrono::steady_clock::time_point lastOn{};
    bool seenOn = false;

    double update(double rawStatus) {
        bool lampOn = static_cast<int>(rawStatus) == 1;  // 1=ON
        auto now = std::chrono::steady_clock::now();
        if (lampOn) {
            lastOn = now;
            seenOn = true;
        }
        bool blinking = seenOn && (now - lastOn) <= HOLD;
        if (!blinking) return 0.0;
        return lampOn ? 2.0 : 1.0;
    }
};

// VCSEC_TPMSData (0x219, vehicle bus) carries one tyre sensor per frame,
// selected by VCSEC_TPMSDataIndex, with that sensor's wheel position. Keep
// the last reading per wheel: sensors report rarely (tens of seconds apart
// while driving), so readings are not expired.
class TpmsLatch {
public:
    void update(const CANParsers& cp, CarState& cs) {
        uint32_t seq = cp.frameSeq(1, "VCSEC_TPMSData");
        if (seq != lastSeq_) {
            lastSeq_ = seq;
            const std::string n = std::to_string(static_cast<int>(get(cp, "VCSEC_TPMSDataIndex")));
            int location = static_cast<int>(get(cp, "VCSEC_TPMSLocation" + n));
            // 0 FL, 1 FR, 2 RL, 3 RR; anything else is an unlocated sensor.
            if (location >= 0 && location < 4) {
                double pressure = get(cp, "VCSEC_TPMSPressure" + n);
                double temp = get(cp, "VCSEC_TPMSTemperature" + n);
                if (pressure < PRESSURE_SNA) pressure_[location] = pressure;
                if (temp < TEMP_SNA) temp_[location] = temp;
            }
        }
        cs.tpmsPressureFL = pressure_[0];
        cs.tpmsPressureFR = pressure_[1];
        cs.tpmsPressureRL = pressure_[2];
        cs.tpmsPressureRR = pressure_[3];
        cs.tpmsTempFL = temp_[0];
        cs.tpmsTempFR = temp_[1];
        cs.tpmsTempRL = temp_[2];
        cs.tpmsTempRR = temp_[3];
    }

private:
    // SNA after DBC scaling: raw 255.
    static constexpr double PRESSURE_SNA = 6.36;   // raw 255 * 0.025 bar = 6.375
    static constexpr double TEMP_SNA = 214.5;      // raw 255 - 40 = 215 degC

    static double get(const CANParsers& cp, const std::string& sig) {
        return cp.get(1, "VCSEC_TPMSData", sig);
    }

    uint32_t lastSeq_ = 0;
    double pressure_[4] = {-1, -1, -1, -1};
    double temp_[4] = {-100, -100, -100, -100};
};

// DAS_lanes (0x239, chassis bus, 10 Hz): the Autopilot's virtual lane as a
// cubic y = C0 + C1 x + C2 x^2 + C3 x^3. Converted to SI here (C0 cm -> m,
// C1 deg -> slope) so dash apps get one unit system. laneViewRange 0 = none.
class DasLanesLatch {
public:
    static constexpr std::chrono::milliseconds STALE{1000};
    static constexpr double DEG_TO_RAD = 3.14159265358979323846 / 180.0;

    void update(const CANParsers& cp, CarState& cs,
                std::chrono::steady_clock::time_point now = std::chrono::steady_clock::now()) {
        uint32_t seq = cp.frameSeq(0, "DAS_lanes");
        if (seq != lastSeq_) {
            lastSeq_ = seq;
            width_ = cp.get(0, "DAS_lanes", "DAS_virtualLaneWidth");
            viewRange_ = cp.get(0, "DAS_lanes", "DAS_virtualLaneViewRange");
            c0_ = cp.get(0, "DAS_lanes", "DAS_virtualLaneC0") / 100.0;
            c1_ = std::tan(cp.get(0, "DAS_lanes", "DAS_virtualLaneC1") * DEG_TO_RAD);
            c2_ = cp.get(0, "DAS_lanes", "DAS_virtualLaneC2");
            c3_ = cp.get(0, "DAS_lanes", "DAS_virtualLaneC3");
            seen_ = now;
            valid_ = true;
        }
        bool present = valid_ && (now - seen_) <= STALE && viewRange_ > 0;
        cs.laneWidth = present ? width_ : 0.0;
        cs.laneViewRange = present ? viewRange_ : 0.0;
        cs.laneC0 = present ? c0_ : 0.0;
        cs.laneC1 = present ? c1_ : 0.0;
        cs.laneC2 = present ? c2_ : 0.0;
        cs.laneC3 = present ? c3_ : 0.0;
    }

private:
    uint32_t lastSeq_ = 0;
    double width_ = 0, viewRange_ = 0, c0_ = 0, c1_ = 0, c2_ = 0, c3_ = 0;
    std::chrono::steady_clock::time_point seen_{};
    bool valid_ = false;
};

// DAS_status driver warnings, collapsing SNA to 0 (none).
static inline void updateDasWarnings(const CANParsers& cp, CarState& cs) {
    double fcw = cp.get(0, "DAS_status", "DAS_forwardCollisionWarning");
    cs.forwardCollisionWarning = fcw == 1.0 ? 1.0 : 0.0;  // 3 = SNA
    double ldw = cp.get(0, "DAS_status", "DAS_laneDepartureWarning");
    cs.laneDepartureWarning = ldw >= 1.0 && ldw <= 4.0 ? ldw : 0.0;  // 5 = SNA
    cs.sideCollisionWarning = cp.get(0, "DAS_status", "DAS_sideCollisionWarning");
}

class TeslaCommaPartyMapper : public CarStateMapper {
public:
    void update(const CANParsers& cp, CarState& cs) override {
        updatePartyBus(cp, cs);
    }
};

class TeslaDashKitMapper : public CarStateMapper {
public:
    void update(const CANParsers &cp, CarState &cs) override {
        cs.egoSteeringAngle = cp.get(0, "SCCM_steeringAngleSensor", "SCCM_steeringAngle");
        cs.gear = cp.get(0, "DI_systemStatus", "DI_gear");
        cs.egoSpeed = cp.get(0, "DI_speed", "DI_uiSpeed");
        cs.adasOn = cp.get(0, "DAS_status2", "DAS_lssState") >= 2.0 ? 1.0 : 0.0;

        // TODO: Invalid in case of DashKit. Look for an equivalent.
        cs.accSetSpeed = cp.get(0, "DI_state", "DI_digitalSpeed");

        cs.leftBlindSpot = cp.get(0, "DAS_status", "DAS_blindSpotRearLeft");
        cs.rightBlindSpot = cp.get(0, "DAS_status", "DAS_blindSpotRearRight");
        cs.fusedSpeedLimit = cp.get(0, "DAS_status", "DAS_fusedSpeedLimit");
        cs.trafficLightColor = cp.get(0, "DAS_road", "DAS_trafficLightColor");
        cs.stopLineDist = cp.get(0, "DAS_road", "DAS_stopLineDist");

        cs.leftBlinker = leftBlinkerHold_.update(
            cp.get(1, "VCLEFT_lightStatus", "VCLEFT_turnSignalStatus"));
        cs.rightBlinker = rightBlinkerHold_.update(
            cp.get(1, "VCRIGHT_lightStatus", "VCRIGHT_turnSignalStatus"));


        // VCFRONT_status is multiplexed: VCFRONT_anyDoorOpen lives on mux 0
        if (static_cast<int>(cp.get(1, "VCFRONT_status", "VCFRONT_statusIndex")) == 0) {
            cs.anyDoorOpen = cp.get(1, "VCFRONT_status", "VCFRONT_anyDoorOpen");
        }

        // VCLEFT_switchStatus is multiplexed: VCLEFT_frontBuckleSwitch lives on mux 0
        if (static_cast<int>(cp.get(1, "VCLEFT_switchStatus", "VCLEFT_switchStatusIndex")) == 0) {
            cs.buckleStatus = cp.get(1, "VCLEFT_switchStatus", "VCLEFT_frontBuckleSwitch") == 2.0 ? 1.0 : 0.0;
        }

        // Climate setpoint temperature (degC) from the vehicle bus.
        cs.acTemp = cp.get(1, "UI_hvacRequest", "UI_hvacReqTempSetpointLeft");
        cs.acTempRight = cp.get(1, "UI_hvacRequest", "UI_hvacReqTempSetpointRight");
        cs.hvacFanLevel = cp.get(1, "UI_hvacRequest", "UI_hvacReqBlowerSegment");
        cs.hvacPowerState = cp.get(1, "UI_hvacRequest", "UI_hvacReqUserPowerState");
        cs.hvacAcMode = cp.get(1, "UI_hvacRequest", "UI_hvacReqACDisable");
        cs.hvacRecirc = cp.get(1, "UI_hvacRequest", "UI_hvacReqRecirc");
        cs.hvacKeepClimateOn = cp.get(1, "UI_hvacRequest", "UI_hvacReqKeepClimateOn");

        lanes_.update(cp, cs);
        updateDasWarnings(cp, cs);

        updateVehicleBus(cp, cs);
        vin_.update(cp, cs);
        tpms_.update(cp, cs);
    }

private:
    BlinkerHold leftBlinkerHold_;
    BlinkerHold rightBlinkerHold_;
    TpmsLatch tpms_;
    DasLanesLatch lanes_;
    TeslaVinUpdater vin_;
};

// New-gen Model Y (Juniper) with new_gen_juniper.dbc on both buses. Comma and
// DashKit number the buses the same way here: 0 = chassis, 1 = vehicle.
class TeslaJuniperMapper : public CarStateMapper {
public:
    void update(const CANParsers &cp, CarState &cs) override {
        cs.egoSteeringAngle = cp.get(1, "SCCM_steeringAngleSensor", "SCCM_steeringAngle");
        cs.gear = cp.get(0, "DI_systemStatus", "DI_gear");
        cs.egoSpeed = cp.get(0, "DI_speed", "DI_uiSpeed");
        cs.adasOn = cp.get(1, "DI_state", "DI_cruiseState") == 2.0 ? 1.0 : 0.0;
        cs.accSetSpeed = cp.get(1, "DI_state", "DI_digitalSpeed");

        cs.leftBlindSpot = cp.get(0, "DAS_status", "DAS_blindSpotRearLeft");
        cs.rightBlindSpot = cp.get(0, "DAS_status", "DAS_blindSpotRearRight");
        cs.fusedSpeedLimit = cp.get(0, "DAS_status", "DAS_fusedSpeedLimit");
        // DAS_road is static on Juniper: no traffic light or stop line data.

        cs.leftBlinker = leftBlinkerHold_.update(
            cp.get(1, "VCLEFT_lightStatus", "VCLEFT_turnSignalStatus"));
        cs.rightBlinker = rightBlinkerHold_.update(
            cp.get(1, "VCRIGHT_lightStatus", "VCRIGHT_turnSignalStatus"));

        if (static_cast<int>(cp.get(1, "VCFRONT_status", "VCFRONT_statusIndex")) == 0) {
            cs.anyDoorOpen = cp.get(1, "VCFRONT_status", "VCFRONT_anyDoorOpen");
        }
        if (static_cast<int>(cp.get(1, "VCLEFT_switchStatus", "VCLEFT_switchStatusIndex")) == 0) {
            cs.buckleStatus = cp.get(1, "VCLEFT_switchStatus", "VCLEFT_frontBuckleSwitch") == 2.0 ? 1.0 : 0.0;
        }

        cs.acTemp = cp.get(0, "UI_hvacRequest", "UI_hvacReqTempSetpointLeft");
        cs.acTempRight = cp.get(0, "UI_hvacRequest", "UI_hvacReqTempSetpointRight");
        cs.hvacFanLevel = cp.get(0, "UI_hvacRequest", "UI_hvacReqBlowerSegment");
        cs.hvacPowerState = cp.get(0, "UI_hvacRequest", "UI_hvacReqUserPowerState");
        cs.hvacAcMode = cp.get(0, "UI_hvacRequest", "UI_hvacReqACDisable");
        cs.hvacRecirc = cp.get(0, "UI_hvacRequest", "UI_hvacReqRecirc");
        cs.hvacKeepClimateOn = cp.get(0, "UI_hvacRequest", "UI_hvacReqKeepClimateOn");

        updateVehicleBus(cp, cs);
        vin_.update(cp, cs);
    }

private:
    BlinkerHold leftBlinkerHold_;
    BlinkerHold rightBlinkerHold_;
    TeslaVinUpdater vin_;
};