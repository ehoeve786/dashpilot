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

    /// Blind spot windows and warnings.
    private static let leftPeriod: TimeInterval = 23, rightPeriod: TimeInterval = 29
    private static let bsmWindow: TimeInterval = 3
    private static let sideWarnDuration: TimeInterval = 1
    private static let ldwPeriod: TimeInterval = 37
    private static let fcwPeriod: TimeInterval = 41, fcwDuration: TimeInterval = 1.5
    /// Rear right tyre leaks down to a warning and is reinflated.
    private static let leakPeriod: TimeInterval = 120
    private static let laneViewRange: Float = 60
    private static let laneC2Turn = 0.003

    private static func tyrePressure(_ base: Double, at t: TimeInterval) -> Float {
        Float(base + 0.03 * sin(t * 0.05))
    }

    private static func leakingPressure(at t: TimeInterval) -> Float {
        let m = t.truncatingRemainder(dividingBy: leakPeriod)
        return Float(2.9 - 0.8 * min(1, m / (leakPeriod * 0.6)))
    }

    /// Tyres warm up from 18 C toward 35 C as the drive goes on.
    private static func tyreTemp(at t: TimeInterval, offset: Double) -> Float {
        Float(35 - 17 * exp(-t / 300) + offset)
    }

    private static func frame(at t: TimeInterval) -> CarState {
        var s = CarState()
        let cycleIndex = Int(t / cycleLength)
        let phase = t.truncatingRemainder(dividingBy: cycleLength)
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
            // The driver takes over for the turn: lane keeping off, so the
            // dashboard shows the steering path.
            s.adasOn = false
            s.madsActive = false
            s.selfdriveActive = false
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

        s.leftBlindSpot = t.truncatingRemainder(dividingBy: leftPeriod) < bsmWindow ? 1 : 0
        s.rightBlindSpot = t.truncatingRemainder(dividingBy: rightPeriod) < bsmWindow ? 1 : 0

        s.tpmsPressureFL = tyrePressure(2.90, at: t)
        s.tpmsPressureFR = tyrePressure(2.88, at: t + 7)
        s.tpmsPressureRL = tyrePressure(2.86, at: t + 13)
        s.tpmsPressureRR = leakingPressure(at: t)
        s.tpmsTempFL = tyreTemp(at: t, offset: 0)
        s.tpmsTempFR = tyreTemp(at: t, offset: 0.5)
        s.tpmsTempRL = tyreTemp(at: t, offset: -1)
        s.tpmsTempRR = tyreTemp(at: t, offset: -0.5)

        let manual = phase >= turnStart && phase < turnEnd
        s.forwardCollisionWarning = !manual && t.truncatingRemainder(dividingBy: fcwPeriod) < fcwDuration ? 1 : 0

        // Lane bends with the turn; sign follows the steering direction.
        s.laneWidth = 3.6
        s.laneViewRange = laneViewRange
        if phase >= turnStart && phase < turnEnd {
            let ease = sin((phase - turnStart) / (turnEnd - turnStart) * .pi)
            s.laneC2 = Float((turningLeft ? 1 : -1) * laneC2Turn * ease)
        }
        s.laneDepartureWarning = t.truncatingRemainder(dividingBy: ldwPeriod) < 1.5 ? 1 : 0
        s.sideCollisionWarning = t.truncatingRemainder(dividingBy: rightPeriod) < sideWarnDuration ? 2 : 0

        // BMS / power — Tesla-ish pack, slowly discharging.
        s.fullPackEnergy = 75
        s.nominalEnergyRemaining = Float(max(10, 60 - t * 0.01))
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
        s.acTemp = 22
        s.acTempRight = 21
        s.hvacFanLevel = 11
        s.hvacPowerState = 1
        s.odometer = Float(42000 + t * 0.01)

        return s
    }
}
