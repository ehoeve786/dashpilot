import Foundation

struct CarState: Codable {
    // Party bus (ADAS)
    var egoSteeringAngle: Float = 0
    var egoSpeed: Float = 0
    var leftBlinker: Float = 0
    var rightBlinker: Float = 0
    var gear: Float = 0
    var adasOn: Bool = false
    var leftBlindSpot: Float = 0
    var rightBlindSpot: Float = 0
    var fusedSpeedLimit: Float = 0
    var stopLineDist: Float = 0
    var trafficLightColor: Float = 0
    var anyDoorOpen: Float = 0
    var buckleStatus: Float = 0
    var accSetSpeed: Float = 0

    // Vehicle bus (BMS/Power)
    var fullPackEnergy: Float = 0
    var nominalEnergyRemaining: Float = 0
    var energyBuffer: Float = 0
    var maxRegenPower: Float = 0
    var maxDischargePower: Float = 0
    var packVoltage: Float = 0
    var packCurrent: Float = 0
    var packTMin: Float = 0
    var packTMax: Float = 0
    var odometer: Float = 0
    var acTemp: Float = 0
    var acTempRight: Float = 0
    var hvacFanLevel: Float = 0
    var hvacPowerState: Float = 0
    var hvacAcMode: Float = 0
    var hvacRecirc: Float = 0
    var hvacKeepClimateOn: Float = 0
    var powerLevel: Float = 0

    // Openpilot state
    var madsActive: Bool = false
    var selfdriveActive: Bool = false
    var experimentalMode: Bool = false
    var changingLane: Bool = false

    // DAS_object vehicles ahead, meters; Dx -1 = no vehicle.
    var leadVehDx: Float = -1
    var leadVehDy: Float = 0
    var leadVehType: Float = 0
    var leftVehDx: Float = -1
    var leftVehDy: Float = 0
    var leftVehType: Float = 0
    var rightVehDx: Float = -1
    var rightVehDy: Float = 0
    var rightVehType: Float = 0
    var cutinVehDx: Float = -1
    var cutinVehDy: Float = 0
    var cutinVehType: Float = 0
    var leadVehVxRel: Float = 0
    var leftVehVxRel: Float = 0
    var rightVehVxRel: Float = 0
    var cutinVehVxRel: Float = 0
    var leadVehHeading: Float = 0
    var leftVehHeading: Float = 0
    var rightVehHeading: Float = 0
    var cutinVehHeading: Float = 0
    var roadSignId: Float = -1
    var roadSignColor: Float = 0
    var roadSignArrow: Float = 0
    var roadSignStopLineDist: Float = -1
    var roadSignControlActive: Float = 0
    var laneWidth: Float = 0
    var laneViewRange: Float = 0
    var laneC0: Float = 0
    var laneC1: Float = 0
    var laneC2: Float = 0
    var laneC3: Float = 0
    var forwardCollisionWarning: Float = 0
    var laneDepartureWarning: Float = 0
    var sideCollisionWarning: Float = 0

    init() {}

    init(from bridge: BridgeCarState) {
        egoSteeringAngle = Float(bridge.egoSteeringAngle)
        egoSpeed = Float(bridge.egoSpeed)
        leftBlinker = Float(bridge.leftBlinker)
        rightBlinker = Float(bridge.rightBlinker)
        gear = Float(bridge.gear)
        adasOn = bridge.adasOn > 0
        leftBlindSpot = Float(bridge.leftBlindSpot)
        rightBlindSpot = Float(bridge.rightBlindSpot)
        fusedSpeedLimit = Float(bridge.fusedSpeedLimit)
        stopLineDist = Float(bridge.stopLineDist)
        trafficLightColor = Float(bridge.trafficLightColor)
        anyDoorOpen = Float(bridge.anyDoorOpen)
        buckleStatus = Float(bridge.buckleStatus)
        accSetSpeed = Float(bridge.accSetSpeed)
        fullPackEnergy = Float(bridge.fullPackEnergy)
        nominalEnergyRemaining = Float(bridge.nominalEnergyRemaining)
        energyBuffer = Float(bridge.energyBuffer)
        maxRegenPower = Float(bridge.maxRegenPower)
        maxDischargePower = Float(bridge.maxDischargePower)
        packVoltage = Float(bridge.packVoltage)
        packCurrent = Float(bridge.packCurrent)
        packTMin = Float(bridge.packTMin)
        packTMax = Float(bridge.packTMax)
        odometer = Float(bridge.odometer)
        acTemp = Float(bridge.acTemp)
        acTempRight = Float(bridge.acTempRight)
        hvacFanLevel = Float(bridge.hvacFanLevel)
        hvacPowerState = Float(bridge.hvacPowerState)
        hvacAcMode = Float(bridge.hvacAcMode)
        hvacRecirc = Float(bridge.hvacRecirc)
        hvacKeepClimateOn = Float(bridge.hvacKeepClimateOn)
        powerLevel = Float(bridge.powerLevel)
        madsActive = bridge.madsActive > 0
        selfdriveActive = bridge.selfdriveActive > 0
        experimentalMode = bridge.experimentalMode > 0
        changingLane = bridge.changingLane > 0
        leadVehDx = Float(bridge.leadVehDx)
        leadVehDy = Float(bridge.leadVehDy)
        leadVehType = Float(bridge.leadVehType)
        leftVehDx = Float(bridge.leftVehDx)
        leftVehDy = Float(bridge.leftVehDy)
        leftVehType = Float(bridge.leftVehType)
        rightVehDx = Float(bridge.rightVehDx)
        rightVehDy = Float(bridge.rightVehDy)
        rightVehType = Float(bridge.rightVehType)
        cutinVehDx = Float(bridge.cutinVehDx)
        cutinVehDy = Float(bridge.cutinVehDy)
        cutinVehType = Float(bridge.cutinVehType)
        leadVehVxRel = Float(bridge.leadVehVxRel)
        leftVehVxRel = Float(bridge.leftVehVxRel)
        rightVehVxRel = Float(bridge.rightVehVxRel)
        cutinVehVxRel = Float(bridge.cutinVehVxRel)
        leadVehHeading = Float(bridge.leadVehHeading)
        leftVehHeading = Float(bridge.leftVehHeading)
        rightVehHeading = Float(bridge.rightVehHeading)
        cutinVehHeading = Float(bridge.cutinVehHeading)
        roadSignId = Float(bridge.roadSignId)
        roadSignColor = Float(bridge.roadSignColor)
        roadSignArrow = Float(bridge.roadSignArrow)
        roadSignStopLineDist = Float(bridge.roadSignStopLineDist)
        roadSignControlActive = Float(bridge.roadSignControlActive)
        laneWidth = Float(bridge.laneWidth)
        laneViewRange = Float(bridge.laneViewRange)
        laneC0 = Float(bridge.laneC0)
        laneC1 = Float(bridge.laneC1)
        laneC2 = Float(bridge.laneC2)
        laneC3 = Float(bridge.laneC3)
        forwardCollisionWarning = Float(bridge.forwardCollisionWarning)
        laneDepartureWarning = Float(bridge.laneDepartureWarning)
        sideCollisionWarning = Float(bridge.sideCollisionWarning)
    }

    private static let kmToMiles: Float = 0.621371

    private static let milesCountries: Set<String> = ["US", "GB", "MM", "LR"]

    private static func speedLimitSignsInKm() -> Bool {
        let country = Locale.current.region?.identifier ?? ""
        return !milesCountries.contains(country)
    }

    func toImperial() -> CarState {
        var c = self
        c.odometer = odometer * Self.kmToMiles
        c.packTMin = packTMin != 0 ? packTMin * 1.8 + 32 : 0
        c.packTMax = packTMax != 0 ? packTMax * 1.8 + 32 : 0
        c.acTemp = acTemp != 0 ? acTemp * 1.8 + 32 : 0
        c.acTempRight = acTempRight != 0 ? acTempRight * 1.8 + 32 : 0
        if Self.speedLimitSignsInKm() {
            c.fusedSpeedLimit = fusedSpeedLimit * Self.kmToMiles
        }
        return c
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        egoSteeringAngle     = try c.decodeIfPresent(Float.self, forKey: .egoSteeringAngle)     ?? 0
        egoSpeed             = try c.decodeIfPresent(Float.self, forKey: .egoSpeed)             ?? 0
        leftBlinker          = try c.decodeIfPresent(Float.self, forKey: .leftBlinker)          ?? 0
        rightBlinker         = try c.decodeIfPresent(Float.self, forKey: .rightBlinker)         ?? 0
        gear                 = try c.decodeIfPresent(Float.self, forKey: .gear)                 ?? 0
        adasOn               = try c.decodeIfPresent(Bool.self,  forKey: .adasOn)               ?? false
        leftBlindSpot        = try c.decodeIfPresent(Float.self, forKey: .leftBlindSpot)        ?? 0
        rightBlindSpot       = try c.decodeIfPresent(Float.self, forKey: .rightBlindSpot)       ?? 0
        fusedSpeedLimit      = try c.decodeIfPresent(Float.self, forKey: .fusedSpeedLimit)      ?? 0
        stopLineDist         = try c.decodeIfPresent(Float.self, forKey: .stopLineDist)         ?? 0
        trafficLightColor    = try c.decodeIfPresent(Float.self, forKey: .trafficLightColor)    ?? 0
        anyDoorOpen          = try c.decodeIfPresent(Float.self, forKey: .anyDoorOpen)          ?? 0
        buckleStatus         = try c.decodeIfPresent(Float.self, forKey: .buckleStatus)         ?? 0
        accSetSpeed          = try c.decodeIfPresent(Float.self, forKey: .accSetSpeed)          ?? 0
        fullPackEnergy       = try c.decodeIfPresent(Float.self, forKey: .fullPackEnergy)       ?? 0
        nominalEnergyRemaining = try c.decodeIfPresent(Float.self, forKey: .nominalEnergyRemaining) ?? 0
        energyBuffer         = try c.decodeIfPresent(Float.self, forKey: .energyBuffer)         ?? 0
        maxRegenPower        = try c.decodeIfPresent(Float.self, forKey: .maxRegenPower)        ?? 0
        maxDischargePower    = try c.decodeIfPresent(Float.self, forKey: .maxDischargePower)    ?? 0
        packVoltage          = try c.decodeIfPresent(Float.self, forKey: .packVoltage)          ?? 0
        packCurrent          = try c.decodeIfPresent(Float.self, forKey: .packCurrent)          ?? 0
        packTMin             = try c.decodeIfPresent(Float.self, forKey: .packTMin)             ?? 0
        packTMax             = try c.decodeIfPresent(Float.self, forKey: .packTMax)             ?? 0
        odometer             = try c.decodeIfPresent(Float.self, forKey: .odometer)             ?? 0
        acTemp               = try c.decodeIfPresent(Float.self, forKey: .acTemp)               ?? 0
        acTempRight          = try c.decodeIfPresent(Float.self, forKey: .acTempRight)          ?? 0
        hvacFanLevel         = try c.decodeIfPresent(Float.self, forKey: .hvacFanLevel)         ?? 0
        hvacPowerState       = try c.decodeIfPresent(Float.self, forKey: .hvacPowerState)       ?? 0
        hvacAcMode           = try c.decodeIfPresent(Float.self, forKey: .hvacAcMode)           ?? 0
        hvacRecirc           = try c.decodeIfPresent(Float.self, forKey: .hvacRecirc)           ?? 0
        hvacKeepClimateOn    = try c.decodeIfPresent(Float.self, forKey: .hvacKeepClimateOn)    ?? 0
        powerLevel           = try c.decodeIfPresent(Float.self, forKey: .powerLevel)           ?? 0
        madsActive           = try c.decodeIfPresent(Bool.self,  forKey: .madsActive)           ?? false
        selfdriveActive      = try c.decodeIfPresent(Bool.self,  forKey: .selfdriveActive)      ?? false
        experimentalMode     = try c.decodeIfPresent(Bool.self,  forKey: .experimentalMode)     ?? false
        changingLane         = try c.decodeIfPresent(Bool.self,  forKey: .changingLane)         ?? false
        leadVehDx            = try c.decodeIfPresent(Float.self, forKey: .leadVehDx)            ?? -1
        leadVehDy            = try c.decodeIfPresent(Float.self, forKey: .leadVehDy)            ?? 0
        leadVehType          = try c.decodeIfPresent(Float.self, forKey: .leadVehType)          ?? 0
        leftVehDx            = try c.decodeIfPresent(Float.self, forKey: .leftVehDx)            ?? -1
        leftVehDy            = try c.decodeIfPresent(Float.self, forKey: .leftVehDy)            ?? 0
        leftVehType          = try c.decodeIfPresent(Float.self, forKey: .leftVehType)          ?? 0
        rightVehDx           = try c.decodeIfPresent(Float.self, forKey: .rightVehDx)           ?? -1
        rightVehDy           = try c.decodeIfPresent(Float.self, forKey: .rightVehDy)           ?? 0
        rightVehType         = try c.decodeIfPresent(Float.self, forKey: .rightVehType)         ?? 0
        cutinVehDx           = try c.decodeIfPresent(Float.self, forKey: .cutinVehDx)           ?? -1
        cutinVehDy           = try c.decodeIfPresent(Float.self, forKey: .cutinVehDy)           ?? 0
        cutinVehType         = try c.decodeIfPresent(Float.self, forKey: .cutinVehType)         ?? 0
        leadVehVxRel         = try c.decodeIfPresent(Float.self, forKey: .leadVehVxRel)         ?? 0
        leftVehVxRel         = try c.decodeIfPresent(Float.self, forKey: .leftVehVxRel)         ?? 0
        rightVehVxRel        = try c.decodeIfPresent(Float.self, forKey: .rightVehVxRel)        ?? 0
        cutinVehVxRel        = try c.decodeIfPresent(Float.self, forKey: .cutinVehVxRel)        ?? 0
        leadVehHeading       = try c.decodeIfPresent(Float.self, forKey: .leadVehHeading)       ?? 0
        leftVehHeading       = try c.decodeIfPresent(Float.self, forKey: .leftVehHeading)       ?? 0
        rightVehHeading      = try c.decodeIfPresent(Float.self, forKey: .rightVehHeading)      ?? 0
        cutinVehHeading      = try c.decodeIfPresent(Float.self, forKey: .cutinVehHeading)      ?? 0
        roadSignId           = try c.decodeIfPresent(Float.self, forKey: .roadSignId)           ?? -1
        roadSignColor        = try c.decodeIfPresent(Float.self, forKey: .roadSignColor)        ?? 0
        roadSignArrow        = try c.decodeIfPresent(Float.self, forKey: .roadSignArrow)        ?? 0
        roadSignStopLineDist = try c.decodeIfPresent(Float.self, forKey: .roadSignStopLineDist) ?? -1
        roadSignControlActive = try c.decodeIfPresent(Float.self, forKey: .roadSignControlActive) ?? 0
        laneWidth            = try c.decodeIfPresent(Float.self, forKey: .laneWidth)            ?? 0
        laneViewRange        = try c.decodeIfPresent(Float.self, forKey: .laneViewRange)        ?? 0
        laneC0               = try c.decodeIfPresent(Float.self, forKey: .laneC0)               ?? 0
        laneC1               = try c.decodeIfPresent(Float.self, forKey: .laneC1)               ?? 0
        laneC2               = try c.decodeIfPresent(Float.self, forKey: .laneC2)               ?? 0
        laneC3               = try c.decodeIfPresent(Float.self, forKey: .laneC3)               ?? 0
        forwardCollisionWarning = try c.decodeIfPresent(Float.self, forKey: .forwardCollisionWarning) ?? 0
        laneDepartureWarning = try c.decodeIfPresent(Float.self, forKey: .laneDepartureWarning) ?? 0
        sideCollisionWarning = try c.decodeIfPresent(Float.self, forKey: .sideCollisionWarning) ?? 0
    }
}
