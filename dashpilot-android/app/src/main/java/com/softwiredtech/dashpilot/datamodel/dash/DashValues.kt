package com.softwiredtech.dashpilot.datamodel.dash

import java.time.Instant
import java.time.ZoneId
import java.util.Locale

fun DashState.dashValues(systemDarkMode: Boolean? = null): Map<String, Any> {
    val c = carState
    val d = displaySettings
    val values = mutableMapOf<String, Any>(
        "ambientLightingAvailable" to c.ambientLightingAvailable,
        "ambientLightingBrightness" to c.ambientLightingBrightness,
        "egoSpeed" to c.egoSpeed,
        "gear" to c.gear,
        "egoSteeringAngle" to c.egoSteeringAngle,
        "leftBlinker" to c.leftBlinker,
        "rightBlinker" to c.rightBlinker,
        "anyDoorOpen" to c.anyDoorOpen,
        "doorOpenFL" to c.doorOpenFL,
        "doorOpenFR" to c.doorOpenFR,
        "doorOpenRL" to c.doorOpenRL,
        "doorOpenRR" to c.doorOpenRR,
        "trunkOpen" to c.trunkOpen,
        "trunkPosition" to c.trunkPosition,
        "frunkOpen" to c.frunkOpen,
        "mirrorFoldedL" to c.mirrorFoldedL,
        "mirrorFoldedR" to c.mirrorFoldedR,
        "seatHeatFL" to c.seatHeatFL,
        "seatHeatFR" to c.seatHeatFR,
        "seatHeatRL" to c.seatHeatRL,
        "seatHeatRC" to c.seatHeatRC,
        "seatHeatRR" to c.seatHeatRR,
        "seatBeltFL" to c.seatBeltFL,
        "seatBeltFR" to c.seatBeltFR,
        "seatBeltRL" to c.seatBeltRL,
        "seatBeltRC" to c.seatBeltRC,
        "seatBeltRR" to c.seatBeltRR,
        "cellVoltMin" to c.cellVoltMin,
        "cellVoltMax" to c.cellVoltMax,
        "coolantBatIn" to c.coolantBatIn,
        "outsideTemp" to c.outsideTemp,
        "cabinTemp" to c.cabinTemp,
        "cabinHumidity" to c.cabinHumidity,
        "wheelTorque" to c.wheelTorque,
        "accelLong" to c.accelLong,
        "accelLat" to c.accelLat,
        "accelPedal" to c.accelPedal,
        "chargeStatus" to c.chargeStatus,
        "chargeLimit" to c.chargeLimit,
        "trafficControlType" to c.trafficControlType,
        "batteryHeatPower" to c.batteryHeatPower,
        "dashkitKickdown" to c.dashkitKickdown,
        "ratedRange" to c.ratedRange,
        "chargerPower" to c.chargerPower,
        "lvVoltage" to c.lvVoltage,
        "lvPower" to c.lvPower,
        "userPresent" to c.userPresent,
        "leadDist" to c.leadDist,
        "leadRelSpeed" to c.leadRelSpeed,
        "buckleStatus" to c.buckleStatus,
        "odometer" to c.odometer,
        "adasOn" to c.adasOn,
        "accSetSpeed" to c.accSetSpeed,
        "fusedSpeedLimit" to c.fusedSpeedLimit,
        "leftBlindSpot" to c.leftBlindSpot,
        "rightBlindSpot" to c.rightBlindSpot,
        "trafficLightColor" to c.trafficLightColor,
        "stopLineDist" to c.stopLineDist,
        "changingLane" to c.changingLane,
        "madsActive" to c.madsActive,
        "experimentalMode" to c.experimentalMode,
        "selfdriveActive" to c.selfdriveActive,
        "nominalEnergyRemaining" to c.nominalEnergyRemaining,
        "fullPackEnergy" to c.fullPackEnergy,
        "energyBuffer" to c.energyBuffer,
        "powerLevel" to c.powerLevel,
        "packVoltage" to c.packVoltage,
        "packCurrent" to c.packCurrent,
        "maxRegenPower" to c.maxRegenPower,
        "maxDischargePower" to c.maxDischargePower,
        "packTMin" to c.packTMin,
        "packTMax" to c.packTMax,
        "tpmsPressureFL" to c.tpmsPressureFL,
        "tpmsPressureFR" to c.tpmsPressureFR,
        "tpmsPressureRL" to c.tpmsPressureRL,
        "tpmsPressureRR" to c.tpmsPressureRR,
        "tpmsTempFL" to c.tpmsTempFL,
        "tpmsTempFR" to c.tpmsTempFR,
        "tpmsTempRL" to c.tpmsTempRL,
        "tpmsTempRR" to c.tpmsTempRR,
        "tpmsBattFL" to c.tpmsBattFL,
        "tpmsBattFR" to c.tpmsBattFR,
        "tpmsBattRL" to c.tpmsBattRL,
        "tpmsBattRR" to c.tpmsBattRR,
        // Rive numbers are 32-bit floats, too coarse for epoch milliseconds.
        "currentTime" to secondsSinceLocalMidnight(currentTime),
        "phoneBattery" to phoneBattery.toFloat(),
        "isImperial" to d.useImperial,
        "darkMode" to (systemDarkMode ?: d.darkMode),
        "darkModeBackgroundGray" to d.darkModeBackgroundGray.toFloat(),
        "showPhoneBattery" to d.showPhoneBattery,
        "showCarBattery" to d.showCarBattery,
        "showOdometer" to d.showOdometer,
        "alwaysOnBlindSpotMonitor" to d.alwaysOnBlindSpotMonitor,
        "renderQuality" to d.renderQuality.toFloat(),
        "dataSourceType" to dataSourceType,
        "speedCameraDistance" to speedCameraDistance.toFloat(),
        "speedLimitSignType" to speedLimitSignType(),
    )
    if (c.ambientLightingAvailable) {
        values["ambientLightingColor"] = c.ambientLightingColor or 0xFF000000.toInt()
    }
    return values
}

// 0 = round (Vienna convention), 1 = US, 2 = Canada; from the phone's region, like the speed limit units.
internal fun speedLimitSignType(): Float = when (Locale.getDefault().country) {
    "US" -> 1f
    "CA" -> 2f
    else -> 0f
}

private fun secondsSinceLocalMidnight(epochMillis: Long): Float =
    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalTime().toSecondOfDay().toFloat()
