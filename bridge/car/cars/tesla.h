#pragma once

#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstring>

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

// DAS_object (0x309, chassis bus) carries one group per frame, selected by
// DAS_objectId (0 lead, 1 left, 2 right, 3 cut-in, 4 road sign, 5 headings),
// so the parser's single latest frame only ever holds one group. Latch each
// group as it arrives and expire it when the car stops refreshing it, so stale
// vehicles don't stay on screen.
class DasObjectLatch {
public:
    static constexpr std::chrono::milliseconds STALE{500};

    void update(const CANParsers& cp, CarState& cs,
                std::chrono::steady_clock::time_point now = std::chrono::steady_clock::now()) {
        uint32_t seq = cp.frameSeq(0, "DAS_object");
        if (seq != lastSeq_) {
            lastSeq_ = seq;
            switch (static_cast<int>(get(cp, "DAS_objectId"))) {
            case 0: latchVehicle(cp, lead_, "DAS_leadVeh", now); break;
            case 1: latchVehicle(cp, left_, "DAS_leftVeh", now); break;
            case 2: latchVehicle(cp, right_, "DAS_rightVeh", now); break;
            case 3: latchVehicle(cp, cutin_, "DAS_cutinVeh", now); break;
            case 4:
                sign_ = {get(cp, "DAS_roadSignId"), get(cp, "DAS_roadSignColor"),
                         get(cp, "DAS_roadSignArrow"), get(cp, "DAS_roadSignStopLineDist"),
                         get(cp, "DAS_roadSignControlActive"), now, true};
                break;
            case 5:
                headings_ = {{get(cp, "DAS_leadVehHeading"), get(cp, "DAS_leftVehHeading"),
                              get(cp, "DAS_rightVehHeading"), get(cp, "DAS_cutinVehHeading")},
                             now, true};
                break;
            default:
                break;
            }
        }
        bool headingsFresh = fresh(headings_.seen, headings_.valid, now);
        apply(lead_, now, headingsFresh ? headings_.rad[0] : HEADING_SNA,
              cs.leadVehDx, cs.leadVehDy, cs.leadVehType, cs.leadVehVxRel, cs.leadVehHeading);
        apply(left_, now, headingsFresh ? headings_.rad[1] : HEADING_SNA,
              cs.leftVehDx, cs.leftVehDy, cs.leftVehType, cs.leftVehVxRel, cs.leftVehHeading);
        apply(right_, now, headingsFresh ? headings_.rad[2] : HEADING_SNA,
              cs.rightVehDx, cs.rightVehDy, cs.rightVehType, cs.rightVehVxRel, cs.rightVehHeading);
        apply(cutin_, now, headingsFresh ? headings_.rad[3] : HEADING_SNA,
              cs.cutinVehDx, cs.cutinVehDy, cs.cutinVehType, cs.cutinVehVxRel, cs.cutinVehHeading);

        bool sign = fresh(sign_.seen, sign_.valid, now) && sign_.id < SIGN_ID_SNA;
        cs.roadSignId = sign ? sign_.id : -1.0;
        cs.roadSignColor = sign ? sign_.color : 0.0;
        cs.roadSignArrow = sign ? sign_.arrow : 0.0;
        cs.roadSignStopLineDist = sign && sign_.stopLineDist < STOP_LINE_SNA ? sign_.stopLineDist : -1.0;
        cs.roadSignControlActive = sign ? sign_.controlActive : 0.0;
    }

private:
    struct Vehicle {
        double dx = 0, dy = 0, type = 0, vxRel = 0;
        std::chrono::steady_clock::time_point seen{};
        bool valid = false;
    };
    struct Sign {
        double id = 0, color = 0, arrow = 0, stopLineDist = 0, controlActive = 0;
        std::chrono::steady_clock::time_point seen{};
        bool valid = false;
    };
    struct Headings {
        double rad[4] = {};
        std::chrono::steady_clock::time_point seen{};
        bool valid = false;
    };

    // SNA (signal not available) values after DBC scaling.
    static constexpr double DX_SNA = 127.5;          // raw 255 * 0.5 m
    static constexpr double VXREL_SNA = 30.0;        // raw 15 * 4 - 30 m/s
    static constexpr double HEADING_SNA = 3.1;       // raw 255 ~ +pi rad
    static constexpr double SIGN_ID_SNA = 255.0;
    static constexpr double STOP_LINE_SNA = 184.5;   // raw 1023 * 0.2 - 20 = 184.6 m

    static double get(const CANParsers& cp, const char* sig) {
        return cp.get(0, "DAS_object", sig);
    }

    static void latchVehicle(const CANParsers& cp, Vehicle& v, const std::string& prefix,
                             std::chrono::steady_clock::time_point now) {
        v = {cp.get(0, "DAS_object", prefix + "Dx"), cp.get(0, "DAS_object", prefix + "Dy"),
             cp.get(0, "DAS_object", prefix + "Type"), cp.get(0, "DAS_object", prefix + "VxRel"),
             now, true};
    }

    static bool fresh(std::chrono::steady_clock::time_point seen, bool valid,
                      std::chrono::steady_clock::time_point now) {
        return valid && (now - seen) <= STALE;
    }

    // Dx -1 = no vehicle; 0 m is a valid distance.
    static void apply(const Vehicle& v, std::chrono::steady_clock::time_point now, double heading,
                      double& dx, double& dy, double& type, double& vxRel, double& headingOut) {
        bool present = fresh(v.seen, v.valid, now) && v.dx < DX_SNA;
        dx = present ? v.dx : -1.0;
        dy = present ? v.dy : 0.0;
        type = present ? v.type : 0.0;
        vxRel = present && v.vxRel < VXREL_SNA ? v.vxRel : 0.0;
        headingOut = present && heading < HEADING_SNA ? heading : 0.0;
    }

    uint32_t lastSeq_ = 0;
    Vehicle lead_, left_, right_, cutin_;
    Sign sign_;
    Headings headings_;
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

        das_.update(cp, cs);
        lanes_.update(cp, cs);
        updateDasWarnings(cp, cs);

        updateVehicleBus(cp, cs);
        vin_.update(cp, cs);
    }

private:
    BlinkerHold leftBlinkerHold_;
    BlinkerHold rightBlinkerHold_;
    DasObjectLatch das_;
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