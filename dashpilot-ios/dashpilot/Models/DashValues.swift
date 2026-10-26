import Foundation

enum DashValue {
    case number(Float)
    case bool(Bool)
    case string(String)
}

extension DashState {
    func dashValues(systemDarkMode: Bool? = nil) -> [(String, DashValue)] {
        let c = carState
        let d = displaySettings
        return [
            ("egoSpeed", .number(c.egoSpeed)),
            ("gear", .number(c.gear)),
            ("egoSteeringAngle", .number(c.egoSteeringAngle)),
            ("leftBlinker", .number(c.leftBlinker)),
            ("rightBlinker", .number(c.rightBlinker)),
            ("anyDoorOpen", .number(c.anyDoorOpen)),
            ("buckleStatus", .number(c.buckleStatus)),
            ("odometer", .number(c.odometer)),
            ("adasOn", .bool(c.adasOn)),
            ("accSetSpeed", .number(c.accSetSpeed)),
            ("fusedSpeedLimit", .number(c.fusedSpeedLimit)),
            ("leftBlindSpot", .number(c.leftBlindSpot)),
            ("rightBlindSpot", .number(c.rightBlindSpot)),
            ("trafficLightColor", .number(c.trafficLightColor)),
            ("stopLineDist", .number(c.stopLineDist)),
            ("changingLane", .bool(c.changingLane)),
            ("leadVehDx", .number(c.leadVehDx)),
            ("leadVehDy", .number(c.leadVehDy)),
            ("leadVehType", .number(c.leadVehType)),
            ("leftVehDx", .number(c.leftVehDx)),
            ("leftVehDy", .number(c.leftVehDy)),
            ("leftVehType", .number(c.leftVehType)),
            ("rightVehDx", .number(c.rightVehDx)),
            ("rightVehDy", .number(c.rightVehDy)),
            ("rightVehType", .number(c.rightVehType)),
            ("cutinVehDx", .number(c.cutinVehDx)),
            ("cutinVehDy", .number(c.cutinVehDy)),
            ("cutinVehType", .number(c.cutinVehType)),
            ("leadVehVxRel", .number(c.leadVehVxRel)),
            ("leftVehVxRel", .number(c.leftVehVxRel)),
            ("rightVehVxRel", .number(c.rightVehVxRel)),
            ("cutinVehVxRel", .number(c.cutinVehVxRel)),
            ("leadVehHeading", .number(c.leadVehHeading)),
            ("leftVehHeading", .number(c.leftVehHeading)),
            ("rightVehHeading", .number(c.rightVehHeading)),
            ("cutinVehHeading", .number(c.cutinVehHeading)),
            ("roadSignId", .number(c.roadSignId)),
            ("roadSignColor", .number(c.roadSignColor)),
            ("roadSignArrow", .number(c.roadSignArrow)),
            ("roadSignStopLineDist", .number(c.roadSignStopLineDist)),
            ("roadSignControlActive", .number(c.roadSignControlActive)),
            ("laneWidth", .number(c.laneWidth)),
            ("laneViewRange", .number(c.laneViewRange)),
            ("laneC0", .number(c.laneC0)),
            ("laneC1", .number(c.laneC1)),
            ("laneC2", .number(c.laneC2)),
            ("laneC3", .number(c.laneC3)),
            ("forwardCollisionWarning", .number(c.forwardCollisionWarning)),
            ("laneDepartureWarning", .number(c.laneDepartureWarning)),
            ("sideCollisionWarning", .number(c.sideCollisionWarning)),
            ("madsActive", .bool(c.madsActive)),
            ("experimentalMode", .bool(c.experimentalMode)),
            ("selfdriveActive", .bool(c.selfdriveActive)),
            ("nominalEnergyRemaining", .number(c.nominalEnergyRemaining)),
            ("fullPackEnergy", .number(c.fullPackEnergy)),
            ("energyBuffer", .number(c.energyBuffer)),
            ("powerLevel", .number(c.powerLevel)),
            ("packVoltage", .number(c.packVoltage)),
            ("packCurrent", .number(c.packCurrent)),
            ("maxRegenPower", .number(c.maxRegenPower)),
            ("maxDischargePower", .number(c.maxDischargePower)),
            ("packTMin", .number(c.packTMin)),
            ("packTMax", .number(c.packTMax)),
            // Rive numbers are 32-bit floats, too coarse for epoch milliseconds.
            ("currentTime", .number(Self.secondsSinceLocalMidnight(currentTime))),
            ("phoneBattery", .number(Float(phoneBattery))),
            ("isImperial", .bool(d.useImperial)),
            ("darkMode", .bool(systemDarkMode ?? d.darkMode)),
            ("darkModeBackgroundGray", .number(Float(d.darkModeBackgroundGray))),
            ("showPhoneBattery", .bool(d.showPhoneBattery)),
            ("showCarBattery", .bool(d.showCarBattery)),
            ("showOdometer", .bool(d.showOdometer)),
            ("alwaysOnBlindSpotMonitor", .bool(d.alwaysOnBlindSpotMonitor)),
            ("renderQuality", .number(Float(d.renderQuality))),
            ("dataSourceType", .string(dataSourceType?.rawValue ?? "")),
        ]
    }

    private static func secondsSinceLocalMidnight(_ epochMillis: Int64) -> Float {
        let date = Date(timeIntervalSince1970: Double(epochMillis) / 1000)
        return Float(date.timeIntervalSince(Calendar.current.startOfDay(for: date)))
    }
}
