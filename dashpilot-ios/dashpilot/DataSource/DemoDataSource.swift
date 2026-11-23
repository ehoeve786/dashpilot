import Foundation

final class DemoDataSource: IDataSource {

    private var continuation: AsyncStream<CarState>.Continuation?
    private var task: Task<Void, Never>?

    let incomingMessages: AsyncStream<CarState>

    init() {
        var capturedContinuation: AsyncStream<CarState>.Continuation?
        incomingMessages = AsyncStream(bufferingPolicy: .bufferingNewest(1)) { continuation in
            capturedContinuation = continuation
        }
        self.continuation = capturedContinuation
    }

    /// `address` is unused — demo mode has nothing to connect to.
    func connect(address: String) {
        task = Task { [weak self] in
            var elapsed: TimeInterval = 0
            let dt: TimeInterval = 0.1
            while !Task.isCancelled {
                self?.continuation?.yield(Self.frame(at: elapsed))
                elapsed += dt
                try? await Task.sleep(for: .seconds(dt))
            }
        }
    }

    func disconnect() {
        task?.cancel()
        task = nil
        continuation?.finish()
    }

    /// One lap through cruise -> a gentle turn -> more cruise -> a red light
    /// that's actually approached and stopped at -> green -> back to cruise.
    private static let cycleLength: TimeInterval = 90
    private static let turnStart: TimeInterval = 25, turnEnd: TimeInterval = 33
    private static let cruise2End: TimeInterval = 50
    private static let approachEnd: TimeInterval = 68
    private static let stoppedEnd: TimeInterval = 75
    private static let goEnd: TimeInterval = 78
    // cycleLength is the end of the accelerate-back-to-cruise phase.

    /// Slowly drifting base speed, continuous across cycle boundaries so
    /// cruise phases never jump.
    private static func cruiseSpeed(at t: TimeInterval) -> Float {
        Float(70 + 20 * sin(t * 0.02))
    }

    // After the drive: 20 s parked (rear-left door opens, the driver leaves), then 60 s on a Supercharger.
    private static let parkLength: TimeInterval = 20
    private static let fullLength: TimeInterval = cycleLength + parkLength + 60
    // kW into the battery and into battery heating, per minute of a real 43-minute Supercharger session.
    private static let chargeKw: [Float] = [3.4, 49.4, 40, 38, 37.6, 37.6, 38.4, 39.6, 40.8, 41.6, 42.4, 42.8, 43.2,
        43.6, 43.6, 43.6, 43.6, 43, 42.2, 41.2, 40, 38.8, 37.4, 36, 34.4, 32.8, 31, 29.2, 27.2, 25.4,
        23.6, 21.6, 19.4, 17.2, 14.8, 12, 8.4, 7.4, 7.4, 7.6, 4, 1, 0.6]
    private static let heatKw: [Float] = [5.6, 5.6, 5.6, 5.6, 5.8, 5.6, 4.4, 3.8, 3.6, 3.6, 3.6, 3.6, 3.4, 3.2,
        3.2, 3, 2.8, 2.8, 2.6, 2.4, 2.4, 2, 2, 2, 2, 1.8, 1.6, 1.6, 1.6, 1.6, 1.6, 1.2, 0.8, 0.8,
        1.4, 1, 0.2, 0, 0, 0, 0, 0, 0]
    private static func rangeKm(_ nominal: Float) -> Float { (nominal - 1.5) * 1000 / 147 }
    private static func at(_ a: [Float], _ u: Double) -> Float {
        let x = u * Double(a.count - 1)
        let i = min(Int(x), a.count - 2)
        return a[i] + (a[i + 1] - a[i]) * Float(x - Double(i))
    }

    private static func frame(at t: TimeInterval) -> CarState {
        let lap = Int(t / fullLength)
        let cyc = t.truncatingRemainder(dividingBy: fullLength)
        // Tyres: all four warm up on the drive and the rear right runs hot at the end; the rear left loses air.
        let leak = 0.3 * min(cyc, cycleLength) / cycleLength
        if cyc < cycleLength { return drive(at: t, lap: lap, phase: cyc, warm: cyc / cycleLength, leak: leak) }
        let st = cyc - cycleLength
        var s = drive(at: t, lap: lap, phase: 0, warm: max(0, 1 - st / 40), leak: leak)
        s.gear = 1
        s.egoSpeed = 0
        s.egoSteeringAngle = 0
        s.leftBlinker = 0
        s.rightBlinker = 0
        s.adasOn = false
        s.selfdriveActive = false
        s.madsActive = false
        s.leftBlindSpot = 0
        s.rightBlindSpot = 0
        s.powerLevel = 0
        if st < parkLength {
            s.doorOpenRL = st > 4 && st < 10 ? 1 : 0
            s.anyDoorOpen = s.doorOpenRL
            s.packCurrent = 0.8
            s.nominalEnergyRemaining = 30
            s.ratedRange = rangeKm(30)
            s.lvPower = 0.3
            s.userPresent = st < 10 ? 1 : 0
            s.hvacPowerState = s.userPresent
            return s
        }
        let u = (st - parkLength) / 60
        let kw = at(chargeKw, u), heat = at(heatKw, u)
        s.chargeStatus = 3
        s.chargerPower = kw + heat + 2.2
        s.batteryHeatPower = heat
        s.chargeLimit = kw + 1.5
        s.packVoltage = Float(355 + 30 * u)
        s.packCurrent = -kw * 1000 / s.packVoltage
        s.packTMin = Float(6 + 24 * u)
        s.nominalEnergyRemaining = Float(30 + 21.3 * u)
        s.ratedRange = rangeKm(s.nominalEnergyRemaining)
        // Someone sits in the car for the first half, then the dash goes to the glance view on its own.
        s.userPresent = u < 0.5 ? 1 : 0
        s.hvacPowerState = s.userPresent
        return s
    }

    private static func drive(at t: TimeInterval, lap cycleIndex: Int, phase: TimeInterval, warm: Double, leak: Double) -> CarState {
        var s = CarState()
        let turningLeft = cycleIndex % 2 == 0

        s.gear = 4 // Drive
        s.adasOn = true
        s.madsActive = true
        s.selfdriveActive = true
        s.experimentalMode = false
        s.changingLane = false
        s.anyDoorOpen = 0
        s.buckleStatus = 1

        // Speed limit sign only changes once per lap (rare), and only to a
        // realistic posted limit.
        let limits: [Float] = [50, 90, 130]
        s.fusedSpeedLimit = limits[cycleIndex % limits.count]

        switch phase {
        case ..<turnStart:
            // Cruise.
            s.egoSpeed = cruiseSpeed(at: t)
            s.egoSteeringAngle = Float(sin(t * 0.3) * 15) // lane-keeping wiggle, big enough to see
            s.trafficLightColor = 0
            s.stopLineDist = 0

        case ..<turnEnd:
            // Smooth, gradual turn: steering eases out and back in, speed
            // dips a little through the corner, turn signal blinks toward it.
            let progress = (phase - turnStart) / (turnEnd - turnStart)
            let ease = sin(progress * .pi) // 0 -> 1 -> 0
            let direction: Float = turningLeft ? -1 : 1
            s.egoSteeringAngle = direction * Float(ease) * 28
            s.egoSpeed = cruiseSpeed(at: t) * Float(1 - 0.5 * ease)
            let blink: Float = phase.truncatingRemainder(dividingBy: 1.0) < 0.5 ? 2 : 1
            s.leftBlinker = turningLeft ? blink : 0
            s.rightBlinker = turningLeft ? 0 : blink
            s.trafficLightColor = 0
            s.stopLineDist = 0

        case ..<cruise2End:
            s.egoSpeed = cruiseSpeed(at: t)
            s.egoSteeringAngle = Float(sin(t * 0.3) * 15)
            s.trafficLightColor = 0
            s.stopLineDist = 0

        case ..<approachEnd:
            // Approaching a red light: decelerate smoothly to a full stop
            // exactly as the stop line reaches the car.
            let progress = (phase - cruise2End) / (approachEnd - cruise2End)
            let phaseStartT = t - (phase - cruise2End) // wall-clock time this phase began
            let startSpeed = cruiseSpeed(at: phaseStartT)
            s.egoSpeed = startSpeed * Float(1 - progress)
            s.egoSteeringAngle = 0
            s.trafficLightColor = 1 // Red
            s.stopLineDist = Float(45 * (1 - progress))

        case ..<stoppedEnd:
            // Stopped at the line, waiting for green.
            s.egoSpeed = 0
            s.egoSteeringAngle = 0
            s.trafficLightColor = 1 // Red
            s.stopLineDist = 0

        case ..<goEnd:
            // Light just turned green; still moving off.
            let progress = (phase - stoppedEnd) / (goEnd - stoppedEnd)
            s.egoSpeed = cruiseSpeed(at: t) * Float(progress) * 0.3
            s.egoSteeringAngle = 0
            s.trafficLightColor = 2 // Green
            s.stopLineDist = 0

        default:
            // Accelerating back up to cruise speed.
            let progress = (phase - goEnd) / (cycleLength - goEnd)
            s.egoSpeed = cruiseSpeed(at: t) * Float(0.3 + 0.7 * progress)
            s.egoSteeringAngle = 0
            s.trafficLightColor = 0
            s.stopLineDist = 0
        }

        s.accSetSpeed = cruiseSpeed(at: t) + 5

        // openpilot's lead car: follows through the cruise, gone in the turn, stops ahead of us at the light.
        let lead: Double
        switch phase {
        case turnStart..<turnEnd: lead = 0
        case cruise2End..<approachEnd: lead = 8 + 27 * (approachEnd - phase) / (approachEnd - cruise2End)
        case approachEnd..<goEnd: lead = 8
        default: lead = 35 + 10 * sin(t * 0.15)
        }
        s.leadDist = Float(lead)
        s.leadRelSpeed = lead == 0 || lead == 8 ? 0 : Float((cruise2End..<approachEnd).contains(phase) ? -1.5 : 1.5 * cos(t * 0.15))

        // While stopped at the light: FL, FR, RL, RR, frunk, trunk open one after another.
        let opening = phase >= approachEnd && phase < stoppedEnd
            ? Int((phase - approachEnd) / (stoppedEnd - approachEnd) * 6) : -1
        func open(_ i: Int) -> Float { opening == i ? 1 : 0 }
        s.anyDoorOpen = (0...3).contains(opening) ? 1 : 0
        s.doorOpenFL = open(0)
        s.doorOpenFR = open(1)
        s.doorOpenRL = open(2)
        s.doorOpenRR = open(3)
        s.frunkOpen = open(4)
        s.trunkOpen = open(5)
        s.trunkPosition = opening == 5 ? 85 : 0
        // Mirrors fold with the liftgate open; the passenger unbuckles to get out.
        s.mirrorFoldedL = open(5)
        s.mirrorFoldedR = open(5)
        s.seatHeatFL = 2
        s.seatHeatFR = 1
        s.seatBeltFL = 2
        s.seatBeltFR = opening == 1 ? 1 : 2

        s.leftBlindSpot = t.truncatingRemainder(dividingBy: 23) < 3 ? 1 : 0
        s.rightBlindSpot = t.truncatingRemainder(dividingBy: 29) < 3 ? 1 : 0

        // BMS / power — Tesla-ish pack, slowly discharging.
        s.fullPackEnergy = 75
        // A 2 km drive at about 160 Wh/km ends at 30 kWh, where the charge starts.
        s.nominalEnergyRemaining = Float(30.32 - 0.32 * phase / cycleLength)
        s.ratedRange = rangeKm(s.nominalEnergyRemaining)
        s.energyBuffer = 1.5
        s.maxRegenPower = 60
        s.maxDischargePower = 300
        s.packVoltage = Float(380 + sin(t * 0.2) * 5)
        let braking = phase >= cruise2End && phase < approachEnd
        s.packCurrent = braking ? -s.egoSpeed * 1.2 : s.egoSpeed * 0.4
        let kw = s.packVoltage * s.packCurrent / 1000
        s.powerLevel = max(-1, min(kw / (kw >= 0 ? s.maxDischargePower : s.maxRegenPower), 1)) * 100
        s.packTMin = 22
        s.packTMax = 28
        let hot = max(0, (warm - 0.6) / 0.4)
        s.tpmsPressureFL = Float(2.7 + 0.1 * warm)
        s.tpmsPressureFR = Float(2.775 + 0.1 * warm)
        s.tpmsPressureRL = Float(2.6 + 0.1 * warm - leak)
        s.tpmsPressureRR = Float(2.85 + 0.1 * warm + 0.12 * hot)
        s.tpmsTempFL = Float(12 + 10 * warm)
        s.tpmsTempFR = Float(12 + 10 * warm)
        s.tpmsTempRL = Float(13 + 10 * warm)
        s.tpmsTempRR = Float(13 + 10 * warm + 50 * hot)
        s.tpmsBattFL = 2.99
        s.tpmsBattFR = 2.98
        s.tpmsBattRL = 2.99
        s.tpmsBattRR = 2.71
        s.outsideTemp = 7
        s.cabinTemp = 20
        s.cabinHumidity = 20
        s.lvVoltage = 15.9
        s.lvPower = 0.7
        s.userPresent = 1
        s.acTemp = 22
        s.acTempRight = 21
        s.hvacFanLevel = 11
        s.hvacPowerState = 1
        s.odometer = Float(42000 + Double(cycleIndex) * 2 + 2 * min(t.truncatingRemainder(dividingBy: fullLength), cycleLength) / cycleLength)
        s.coolantBatIn = 24

        return s
    }
}
