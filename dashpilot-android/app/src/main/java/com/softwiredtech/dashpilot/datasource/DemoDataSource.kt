package com.softwiredtech.dashpilot.datasource

import com.softwiredtech.dashpilot.datamodel.dash.CarState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class DemoDataSource : IDataSource {

    private val _incoming = MutableSharedFlow<CarState>(replay = 1)
    override val incomingMessages: Flow<CarState> = _incoming
    private var job: Job? = null

    override fun connect(address: String) {
        job = CoroutineScope(Dispatchers.Default).launch {
            var elapsed = 0.0
            while (isActive) {
                _incoming.emit(frame(elapsed))
                elapsed += STEP_S
                delay((STEP_S * 1000).toLong())
            }
        }
    }

    override fun disconnect() {
        job?.cancel()
        job = null
    }

    private companion object {
        const val STEP_S = 0.1
        const val CYCLE = 90.0
        const val TURN_START = 25.0
        const val TURN_END = 33.0
        const val CRUISE2_END = 50.0
        const val APPROACH_END = 68.0
        const val STOPPED_END = 75.0
        const val GO_END = 78.0
        val LIMITS = floatArrayOf(50f, 90f, 130f)
        const val LEFT_PERIOD = 23.0
        const val RIGHT_PERIOD = 29.0
        const val BSM_WINDOW = 3.0
        const val HANDOFF_S = 1.0
        const val LEFT_AHEAD_END = 11.0
        const val RIGHT_AHEAD_START = 19.0
        const val LANE_WIDTH = 3.5f
        const val TYPE_TRUCK = 1f
        const val TYPE_CAR = 2f
        const val LDW_PERIOD = 37.0
        const val CUTIN_PERIOD = 41.0
        const val CUTIN_S = 4.0
        const val LANE_VIEW_RANGE = 60f
        const val LANE_C2_TURN = 0.003

        // Cut-in from the right lane: slides from Dy -3.5 to 0 at ~18 m, with
        // a forward collision warning while it's closest.
        fun cutin(t: Double): Pair<Float, Float>? {
            val m = t % CUTIN_PERIOD
            if (m >= CUTIN_S) return null
            val progress = m / CUTIN_S
            return Pair((22 - 8 * progress).toFloat(), (-LANE_WIDTH * (1 - progress)).toFloat())
        }

        fun cruise(t: Double): Float = (70 + 20 * sin(t * 0.02)).toFloat()

        // A left-lane car passes us: blind spot first, then pulls away ahead.
        // It's reported at 0 m for the last HANDOFF_S of the blind spot window.
        fun leftVehDx(t: Double): Float {
            val m = t % LEFT_PERIOD
            return if (m in (BSM_WINDOW - HANDOFF_S)..LEFT_AHEAD_END) {
                (max(0.0, m - BSM_WINDOW) * 7.5).toFloat()
            } else -1f
        }

        // We overtake a right-lane truck: it closes in from ahead, then sits in
        // the blind spot, still reported at 0 m for the first HANDOFF_S.
        fun rightVehDx(t: Double): Float {
            val m = t % RIGHT_PERIOD
            return when {
                m >= RIGHT_AHEAD_START -> ((RIGHT_PERIOD - m) * 5).toFloat()
                m < HANDOFF_S -> 0f
                else -> -1f
            }
        }

        fun leadVehDx(t: Double, phase: Double): Float = when {
            phase < TURN_START || (phase >= TURN_END && phase < CRUISE2_END) ->
                (35 + 10 * sin(t * 0.15)).toFloat()
            phase < TURN_END -> -1f
            phase < APPROACH_END ->
                (8 + 35 * (1 - (phase - CRUISE2_END) / (APPROACH_END - CRUISE2_END))).toFloat()
            phase < STOPPED_END -> 8f
            else -> min(45.0, 8 + (phase - STOPPED_END) * 3).toFloat()
        }

        fun frame(t: Double): CarState {
            val lap = (t / CYCLE).toInt()
            val phase = t % CYCLE
            val turningLeft = lap % 2 == 0
            val blink = if (t % 1.0 < 0.5) 2f else 1f
            val wiggle = (sin(t * 0.3) * 15).toFloat()

            var speed: Float
            var steering = 0f
            var left = 0f
            var right = 0f
            var light = 0f
            var stopLine = 0f
            var braking = false

            when {
                phase < TURN_START || (phase >= TURN_END && phase < CRUISE2_END) -> {
                    speed = cruise(t)
                    steering = wiggle
                }
                phase < TURN_END -> {
                    val ease = sin((phase - TURN_START) / (TURN_END - TURN_START) * PI)
                    steering = ((if (turningLeft) -1 else 1) * ease * 28).toFloat()
                    speed = cruise(t) * (1 - 0.5 * ease).toFloat()
                    if (turningLeft) left = blink else right = blink
                }
                phase < APPROACH_END -> {
                    val progress = (phase - CRUISE2_END) / (APPROACH_END - CRUISE2_END)
                    speed = cruise(t - (phase - CRUISE2_END)) * (1 - progress).toFloat()
                    light = 1f
                    stopLine = (45 * (1 - progress)).toFloat()
                    braking = true
                }
                phase < STOPPED_END -> {
                    speed = 0f
                    light = 1f
                }
                phase < GO_END -> {
                    speed = cruise(t) * ((phase - STOPPED_END) / (GO_END - STOPPED_END)).toFloat() * 0.3f
                    light = 2f
                }
                else -> {
                    speed = cruise(t) * (0.3 + 0.7 * (phase - GO_END) / (CYCLE - GO_END)).toFloat()
                }
            }

            val packVoltage = (380 + sin(t * 0.2) * 5).toFloat()
            val packCurrent = if (braking) -speed * 1.2f else speed * 0.4f
            val kw = packVoltage * packCurrent / 1000
            val maxRegen = 60f
            val maxDischarge = 300f
            val lead = leadVehDx(t, phase)
            val leftDx = leftVehDx(t)
            val rightDx = rightVehDx(t)
            val cut = if (phase < TURN_START || (phase >= TURN_END && phase < CRUISE2_END)) cutin(t) else null
            // Lane bends with the turn; sign follows the steering direction.
            val laneCurve = if (phase >= TURN_START && phase < TURN_END) {
                (if (turningLeft) 1 else -1) * LANE_C2_TURN *
                    sin((phase - TURN_START) / (TURN_END - TURN_START) * PI)
            } else 0.0
            val ldwPhase = t % LDW_PERIOD
            val signVisible = phase >= CRUISE2_END && phase < GO_END

            return CarState(
                egoSteeringAngle = steering,
                egoSpeed = speed,
                leftBlinker = left,
                rightBlinker = right,
                gear = 4f,
                adasOn = true,
                leftBlindSpot = if (t % LEFT_PERIOD < BSM_WINDOW) 1f else 0f,
                rightBlindSpot = if (t % RIGHT_PERIOD < BSM_WINDOW) 1f else 0f,
                fusedSpeedLimit = LIMITS[lap % LIMITS.size],
                stopLineDist = stopLine,
                trafficLightColor = light,
                buckleStatus = 1f,
                accSetSpeed = cruise(t) + 5,
                fullPackEnergy = 75f,
                nominalEnergyRemaining = max(10.0, 60 - t * 0.01).toFloat(),
                energyBuffer = 1.5f,
                maxRegenPower = maxRegen,
                maxDischargePower = maxDischarge,
                packVoltage = packVoltage,
                packCurrent = packCurrent,
                packTMin = 22f,
                packTMax = 28f,
                odometer = (42000 + t * 0.01).toFloat(),
                acTemp = 22f,
                acTempRight = 21f,
                hvacFanLevel = 11f,
                hvacPowerState = 1f,
                powerLevel = max(-1f, min(1f, kw / if (kw >= 0) maxDischarge else maxRegen)) * 100,
                selfdriveActive = true,
                madsActive = true,
                leadVehDx = lead,
                leadVehDy = if (lead >= 0) (sin(t * 0.4) * 0.3).toFloat() else 0f,
                leadVehType = if (lead >= 0) TYPE_CAR else 0f,
                leftVehDx = leftDx,
                leftVehDy = if (leftDx >= 0) LANE_WIDTH else 0f,
                leftVehType = if (leftDx >= 0) TYPE_CAR else 0f,
                rightVehDx = rightDx,
                rightVehDy = if (rightDx >= 0) -LANE_WIDTH else 0f,
                rightVehType = if (rightDx >= 0) TYPE_TRUCK else 0f,
                cutinVehDx = cut?.first ?: -1f,
                cutinVehDy = cut?.second ?: 0f,
                cutinVehType = if (cut != null) TYPE_CAR else 0f,
                cutinVehVxRel = if (cut != null) -2f else 0f,
                cutinVehHeading = if (cut != null) 0.15f else 0f,
                leftVehVxRel = if (leftDx >= 0) 7.5f else 0f,
                rightVehVxRel = if (rightDx >= 0) -5f else 0f,
                roadSignId = if (signVisible) 1f else -1f,
                roadSignColor = if (!signVisible) 0f else if (light == 2f) 3f else 1f,
                roadSignArrow = if (signVisible) 3f else 0f,
                roadSignStopLineDist = if (signVisible) stopLine else -1f,
                roadSignControlActive = if (signVisible) 1f else 0f,
                laneWidth = 3.6f,
                laneViewRange = LANE_VIEW_RANGE,
                laneC2 = laneCurve.toFloat(),
                forwardCollisionWarning = if (cut != null && cut.second > -1f) 1f else 0f,
                laneDepartureWarning = if (ldwPhase < 1.5) 1f else 0f,
                sideCollisionWarning = if (t % RIGHT_PERIOD < HANDOFF_S) 2f else 0f,
            )
        }
    }
}
