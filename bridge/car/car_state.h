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

    // Tyre pressure (bar) and temperature (degC) per wheel from
    // VCSEC_TPMSData; pressure -1 = no reading yet, temperature -100.
    double tpmsPressureFL = -1;
    double tpmsPressureFR = -1;
    double tpmsPressureRL = -1;
    double tpmsPressureRR = -1;
    double tpmsTempFL = -100;
    double tpmsTempFR = -100;
    double tpmsTempRL = -100;
    double tpmsTempRR = -100;

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
    static constexpr size_t VIN_OFFSET = 53;
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

        // TPMS
        out[36] = tpmsPressureFL;
        out[37] = tpmsPressureFR;
        out[38] = tpmsPressureRL;
        out[39] = tpmsPressureRR;
        out[40] = tpmsTempFL;
        out[41] = tpmsTempFR;
        out[42] = tpmsTempRL;
        out[43] = tpmsTempRR;

        // DAS_lanes / DAS_status
        out[44] = laneWidth;
        out[45] = laneViewRange;
        out[46] = laneC0;
        out[47] = laneC1;
        out[48] = laneC2;
        out[49] = laneC3;
        out[50] = forwardCollisionWarning;
        out[51] = laneDepartureWarning;
        out[52] = sideCollisionWarning;

        // VIN chars ride the double array as raw bit patterns, 8 bytes per
        // double; decoded by CanFrameDecoder.arrayToCarState.
        char padded[VIN_DOUBLE_COUNT * sizeof(double)] = {};
        std::memcpy(padded, vin, VIN_LENGTH);
        std::memcpy(out + VIN_OFFSET, padded, sizeof(padded));
    }
};
