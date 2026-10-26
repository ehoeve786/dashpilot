#pragma once

#include <cstddef>
#include <cstring>

struct CarState {
    // Party bus
    double egoSteeringAngle = 0;
    double egoSpeed = 0;
    double leftBlinker = 0;
    double rightBlinker = 0;
    double gear = 0;
    double adasOn = 0;
    double leftBlindSpot = 0;
    double rightBlindSpot = 0;
    double fusedSpeedLimit = 0;
    double stopLineDist = 0;
    double trafficLightColor = 0;
    double anyDoorOpen = 0;
    double buckleStatus = 0;
    double accSetSpeed = 0;

    // Vehicle bus
    double fullPackEnergy = 0;
    double nominalEnergyRemaining = 0;
    double energyBuffer = 0;
    double maxRegenPower = 0;
    double maxDischargePower = 0;
    double packVoltage = 0;
    double packCurrent = 0;
    double packTMin = 0;
    double packTMax = 0;
    double odometer = 0;
    double acTemp = 0;
    double acTempRight = 0;
    double hvacFanLevel = 0;
    double hvacPowerState = 0;
    double hvacAcMode = 0;
    double hvacRecirc = 0;
    double hvacKeepClimateOn = 0;
    double powerLevel = 0;

    // Openpilot state
    double madsActive = 0;
    double selfdriveActive = 0;
    double experimentalMode = 0;
    double changingLane = 0;

    // DAS_object vehicles ahead. Dx/Dy in meters (Dy sign unverified);
    // Dx -1 = no vehicle. Type: 0 unknown, 1 truck, 2 car, 3 motorcycle,
    // 4 bicycle, 5 pedestrian.
    double leadVehDx = -1;
    double leadVehDy = 0;
    double leadVehType = 0;
    double leftVehDx = -1;
    double leftVehDy = 0;
    double leftVehType = 0;
    double rightVehDx = -1;
    double rightVehDy = 0;
    double rightVehType = 0;
    double cutinVehDx = -1;
    double cutinVehDy = 0;
    double cutinVehType = 0;
    // Relative speed (m/s, + = pulling away) and heading (rad, sign
    // unverified) of the DAS_object vehicles; 0 when unknown.
    double leadVehVxRel = 0;
    double leftVehVxRel = 0;
    double rightVehVxRel = 0;
    double cutinVehVxRel = 0;
    double leadVehHeading = 0;
    double leftVehHeading = 0;
    double rightVehHeading = 0;
    double cutinVehHeading = 0;

    // DAS_object road sign: id -1 none, 0 stop sign, 1 traffic light.
    // Color 0 none, 1 red, 2 yellow, 3 green, 4 red+yellow. Arrow 0 circle,
    // 1 left, 2 right, 3 straight, 4 unknown. Stop line meters, -1 = unknown.
    double roadSignId = -1;
    double roadSignColor = 0;
    double roadSignArrow = 0;
    double roadSignStopLineDist = -1;
    double roadSignControlActive = 0;

    // DAS_lanes virtual lane, y = C0 + C1 x + C2 x^2 + C3 x^3 in meters
    // (sign unverified). laneViewRange 0 = no lane data.
    double laneWidth = 0;
    double laneViewRange = 0;
    double laneC0 = 0;
    double laneC1 = 0;
    double laneC2 = 0;
    double laneC3 = 0;

    // DAS_status warnings. FCW 0/1; lane departure 0 none, 1 left,
    // 2 right, 3 left severe, 4 right severe; side collision 0 none,
    // 1 left, 2 right, 3 both.
    double forwardCollisionWarning = 0;
    double laneDepartureWarning = 0;
    double sideCollisionWarning = 0;

    // Vehicle VIN, empty until fully assembled by the mapper.
    static constexpr size_t VIN_LENGTH = 17;
    char vin[VIN_LENGTH + 1] = {};

    static constexpr size_t VIN_DOUBLE_COUNT = 3;
    static constexpr size_t VIN_OFFSET = 70;
    static constexpr size_t FIELD_COUNT = VIN_OFFSET + VIN_DOUBLE_COUNT;

    void toArray(double* out) const {
        // Party bus
        out[0] = egoSteeringAngle;
        out[1] = egoSpeed;
        out[2] = leftBlinker;
        out[3] = rightBlinker;
        out[4] = gear;
        out[5] = adasOn;
        out[6] = leftBlindSpot;
        out[7] = rightBlindSpot;
        out[8] = fusedSpeedLimit;
        out[9] = stopLineDist;
        out[10] = trafficLightColor;
        out[11] = anyDoorOpen;
        out[12] = buckleStatus;
        out[13] = accSetSpeed;

        // Vehicle bus
        out[14] = fullPackEnergy;
        out[15] = nominalEnergyRemaining;
        out[16] = energyBuffer;
        out[17] = maxRegenPower;
        out[18] = maxDischargePower;
        out[19] = packVoltage;
        out[20] = packCurrent;
        out[21] = packTMin;
        out[22] = packTMax;
        out[23] = odometer;

        // Openpilot
        out[24] = selfdriveActive;
        out[25] = experimentalMode;
        out[26] = madsActive;
        out[27] = changingLane;

        out[28] = acTemp;
        out[29] = acTempRight;
        out[30] = hvacFanLevel;
        out[31] = hvacPowerState;
        out[32] = hvacAcMode;
        out[33] = hvacRecirc;
        out[34] = hvacKeepClimateOn;
        out[35] = powerLevel;

        // DAS_object
        out[36] = leadVehDx;
        out[37] = leadVehDy;
        out[38] = leadVehType;
        out[39] = leftVehDx;
        out[40] = leftVehDy;
        out[41] = leftVehType;
        out[42] = rightVehDx;
        out[43] = rightVehDy;
        out[44] = rightVehType;
        out[45] = cutinVehDx;
        out[46] = cutinVehDy;
        out[47] = cutinVehType;
        out[48] = leadVehVxRel;
        out[49] = leftVehVxRel;
        out[50] = rightVehVxRel;
        out[51] = cutinVehVxRel;
        out[52] = leadVehHeading;
        out[53] = leftVehHeading;
        out[54] = rightVehHeading;
        out[55] = cutinVehHeading;
        out[56] = roadSignId;
        out[57] = roadSignColor;
        out[58] = roadSignArrow;
        out[59] = roadSignStopLineDist;
        out[60] = roadSignControlActive;
        out[61] = laneWidth;
        out[62] = laneViewRange;
        out[63] = laneC0;
        out[64] = laneC1;
        out[65] = laneC2;
        out[66] = laneC3;
        out[67] = forwardCollisionWarning;
        out[68] = laneDepartureWarning;
        out[69] = sideCollisionWarning;

        // VIN chars ride the double array as raw bit patterns, 8 bytes per
        // double; decoded by CanFrameDecoder.arrayToCarState.
        char padded[VIN_DOUBLE_COUNT * sizeof(double)] = {};
        std::memcpy(padded, vin, VIN_LENGTH);
        std::memcpy(out + VIN_OFFSET, padded, sizeof(padded));
    }
};
