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

    /// Blind spot windows, and the DAS_object vehicles that hand off to them.
    private static let leftPeriod: TimeInterval = 23, rightPeriod: TimeInterval = 29
    private static let bsmWindow: TimeInterval = 3
    private static let handoff: TimeInterval = 1
    private static let leftAheadEnd: TimeInterval = 11
    private static let rightAheadStart: TimeInterval = 19
    private static let laneWidth: Float = 3.5
    private static let typeTruck: Float = 1, typeCar: Float = 2
    private static let ldwPeriod: TimeInterval = 37
    private static let cutinPeriod: TimeInterval = 41, cutinDuration: TimeInterval = 4
    private static let laneViewRange: Float = 60
    private static let laneC2Turn = 0.003

    /// Cut-in from the right lane: slides from Dy -3.5 to 0 at ~18 m, with a
    /// forward collision warning while it's closest.
    private static func cutin(at t: TimeInterval) -> (dx: Float, dy: Float)? {
        let m = t.truncatingRemainder(dividingBy: cutinPeriod)
        guard m < cutinDuration else { return nil }
        let progress = m / cutinDuration
        return (Float(22 - 8 * progress), -laneWidth * Float(1 - progress))
    }

    /// A left-lane car passes us: blind spot first, then pulls away ahead.
    /// It's reported at 0 m for the last `handoff` seconds of the blind spot window.
    private static func leftVehDx(at t: TimeInterval) -> Float {
        let m = t.truncatingRemainder(dividingBy: leftPeriod)
        return ((bsmWindow - handoff)...leftAheadEnd).contains(m) ? Float(max(0, m - bsmWindow) * 7.5) : -1
    }

    /// We overtake a right-lane truck: it closes in from ahead, then sits in
    /// the blind spot, still reported at 0 m for the first `handoff` seconds.
    private static func rightVehDx(at t: TimeInterval) -> Float {
        let m = t.truncatingRemainder(dividingBy: rightPeriod)
        if m >= rightAheadStart { return Float((rightPeriod - m) * 5) }
        return m < handoff ? 0 : -1
    }

    private static func leadVehDx(at t: TimeInterval, phase: TimeInterval) -> Float {
        switch phase {
        case ..<turnStart: return Float(35 + 10 * sin(t * 0.15))
        case ..<turnEnd: return -1
        case ..<cruise2End: return Float(35 + 10 * sin(t * 0.15))
        case ..<approachEnd:
            return Float(8 + 35 * (1 - (phase - cruise2End) / (approachEnd - cruise2End)))
        case ..<stoppedEnd: return 8
        default: return Float(min(45, 8 + (phase - stoppedEnd) * 3))
        }
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

        s.leadVehDx = leadVehDx(at: t, phase: phase)
        s.leadVehDy = s.leadVehDx >= 0 ? Float(sin(t * 0.4) * 0.3) : 0
        s.leadVehType = s.leadVehDx >= 0 ? typeCar : 0
        s.leftVehDx = leftVehDx(at: t)
        s.leftVehDy = s.leftVehDx >= 0 ? laneWidth : 0
        s.leftVehType = s.leftVehDx >= 0 ? typeCar : 0
        s.rightVehDx = rightVehDx(at: t)
        s.rightVehDy = s.rightVehDx >= 0 ? -laneWidth : 0
        s.rightVehType = s.rightVehDx >= 0 ? typeTruck : 0
        s.leftVehVxRel = s.leftVehDx >= 0 ? 7.5 : 0
        s.rightVehVxRel = s.rightVehDx >= 0 ? -5 : 0

        let cruising = phase < turnStart || (phase >= turnEnd && phase < cruise2End)
        if cruising, let cut = cutin(at: t) {
            s.cutinVehDx = cut.dx
            s.cutinVehDy = cut.dy
            s.cutinVehType = typeCar
            s.cutinVehVxRel = -2
            s.cutinVehHeading = 0.15
            s.forwardCollisionWarning = cut.dy > -1 ? 1 : 0
        }

        // Lane bends with the turn; sign follows the steering direction.
        s.laneWidth = 3.6
        s.laneViewRange = laneViewRange
        if phase >= turnStart && phase < turnEnd {
            let ease = sin((phase - turnStart) / (turnEnd - turnStart) * .pi)
            s.laneC2 = Float((turningLeft ? 1 : -1) * laneC2Turn * ease)
        }
        s.laneDepartureWarning = t.truncatingRemainder(dividingBy: ldwPeriod) < 1.5 ? 1 : 0
        s.sideCollisionWarning = t.truncatingRemainder(dividingBy: rightPeriod) < handoff ? 2 : 0

        if phase >= cruise2End && phase < goEnd {
            s.roadSignId = 1
            s.roadSignColor = s.trafficLightColor == 2 ? 3 : 1
            s.roadSignArrow = 3
            s.roadSignStopLineDist = s.stopLineDist
            s.roadSignControlActive = 1
        }

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
