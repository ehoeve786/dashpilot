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
import kotlin.math.cos
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
        // After the drive: 20 s parked (rear-left door opens, the driver leaves), then 60 s on a Supercharger.
        const val PARK_S = 20.0
        const val FULL = CYCLE + PARK_S + 60.0
        // kW into the battery and into battery heating, per minute of a real 43-minute Supercharger session.
        val CHARGE_KW = floatArrayOf(3.4f, 49.4f, 40f, 38f, 37.6f, 37.6f, 38.4f, 39.6f, 40.8f, 41.6f, 42.4f, 42.8f, 43.2f,
            43.6f, 43.6f, 43.6f, 43.6f, 43f, 42.2f, 41.2f, 40f, 38.8f, 37.4f, 36f, 34.4f, 32.8f, 31f, 29.2f, 27.2f, 25.4f,
            23.6f, 21.6f, 19.4f, 17.2f, 14.8f, 12f, 8.4f, 7.4f, 7.4f, 7.6f, 4f, 1f, 0.6f)
        val HEAT_KW = floatArrayOf(5.6f, 5.6f, 5.6f, 5.6f, 5.8f, 5.6f, 4.4f, 3.8f, 3.6f, 3.6f, 3.6f, 3.6f, 3.4f, 3.2f,
            3.2f, 3f, 2.8f, 2.8f, 2.6f, 2.4f, 2.4f, 2f, 2f, 2f, 2f, 1.8f, 1.6f, 1.6f, 1.6f, 1.6f, 1.6f, 1.2f, 0.8f, 0.8f,
            1.4f, 1f, 0.2f, 0f, 0f, 0f, 0f, 0f, 0f)
        fun rangeKm(nominal: Float) = (nominal - 1.5f) * 1000 / 147
        fun at(a: FloatArray, u: Double): Float {
            val x = u * (a.size - 1)
            val i = min(x.toInt(), a.size - 2)
            return (a[i] + (a[i + 1] - a[i]) * (x - i)).toFloat()
        }

        fun cruise(t: Double): Float = (70 + 20 * sin(t * 0.02)).toFloat()

        fun frame(t: Double): CarState {
            val lap = (t / FULL).toInt()
            val cyc = t % FULL
            // Tyres: all four warm up on the drive and the rear right runs hot at the end; the rear left loses air.
            val leak = 0.3 * min(cyc, CYCLE) / CYCLE
            if (cyc < CYCLE) return drive(t, lap, cyc, cyc / CYCLE, leak)
            val s = cyc - CYCLE
            val base = drive(t, lap, 0.0, max(0.0, 1 - s / 40), leak)
            if (s < PARK_S) {
                val door = if (s > 4 && s < 10) 1f else 0f
                return base.copy(
                    gear = 1f, egoSpeed = 0f, egoSteeringAngle = 0f, leftBlinker = 0f, rightBlinker = 0f,
                    adasOn = false, selfdriveActive = false, madsActive = false, leftBlindSpot = 0f, rightBlindSpot = 0f,
                    doorOpenRL = door, anyDoorOpen = door, packCurrent = 0.8f, powerLevel = 0f,
                    nominalEnergyRemaining = 30f, ratedRange = rangeKm(30f), lvPower = 0.3f,
                    userPresent = if (s < 10) 1f else 0f, hvacPowerState = if (s < 10) 1f else 0f,
                )
            }
            val u = (s - PARK_S) / 60
            val kw = at(CHARGE_KW, u)
            val heat = at(HEAT_KW, u)
            val volts = (355 + 30 * u).toFloat()
            val nominal = (30 + 21.3 * u).toFloat()
            return base.copy(
                gear = 1f, egoSpeed = 0f, egoSteeringAngle = 0f, leftBlinker = 0f, rightBlinker = 0f,
                adasOn = false, selfdriveActive = false, madsActive = false, leftBlindSpot = 0f, rightBlindSpot = 0f,
                chargeStatus = 3f, chargerPower = kw + heat + 2.2f, batteryHeatPower = heat, chargeLimit = kw + 1.5f,
                packVoltage = volts, packCurrent = -kw * 1000 / volts, powerLevel = 0f, packTMin = (6 + 24 * u).toFloat(),
                nominalEnergyRemaining = nominal, ratedRange = rangeKm(nominal),
                // Someone sits in the car for the first half, then the dash goes to the glance view on its own.
                userPresent = if (u < 0.5) 1f else 0f, hvacPowerState = if (u < 0.5) 1f else 0f,
            )
        }

        fun drive(t: Double, lap: Int, phase: Double, warm: Double, leak: Double): CarState {
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

            // Autopilot's lead car: follows through the cruise, gone in the turn, stops ahead of us at the light.
            val lead = when {
                phase >= TURN_START && phase < TURN_END -> 0.0
                phase >= CRUISE2_END && phase < APPROACH_END -> 8 + 27 * (APPROACH_END - phase) / (APPROACH_END - CRUISE2_END)
                phase >= APPROACH_END && phase < GO_END -> 8.0
                else -> 35 + 10 * sin(t * 0.15)
            }
            val leadRel = when {
                lead == 8.0 || lead == 0.0 -> 0.0
                phase >= CRUISE2_END && phase < APPROACH_END -> -1.5
                else -> 1.5 * cos(t * 0.15)
            }

            // While stopped at the light: FL, FR, RL, RR, frunk, trunk open one after another.
            val opening = if (phase >= APPROACH_END && phase < STOPPED_END)
                ((phase - APPROACH_END) / (STOPPED_END - APPROACH_END) * 6).toInt() else -1
            fun open(i: Int) = if (opening == i) 1f else 0f

            val hot = max(0.0, (warm - 0.6) / 0.4)
            val packVoltage = (380 + sin(t * 0.2) * 5).toFloat()
            val packCurrent = if (braking) -speed * 1.2f else speed * 0.4f
            val kw = packVoltage * packCurrent / 1000
            val maxRegen = 60f
            val maxDischarge = 300f

            return CarState(
                egoSteeringAngle = steering,
                egoSpeed = speed,
                leftBlinker = left,
                rightBlinker = right,
                gear = 4f,
                adasOn = true,
                leftBlindSpot = if (t % 23 < 3) 1f else 0f,
                rightBlindSpot = if (t % 29 < 3) 1f else 0f,
                fusedSpeedLimit = LIMITS[lap % LIMITS.size],
                stopLineDist = stopLine,
                trafficLightColor = light,
                anyDoorOpen = if (opening in 0..3) 1f else 0f,
                doorOpenFL = open(0),
                doorOpenFR = open(1),
                doorOpenRL = open(2),
                doorOpenRR = open(3),
                frunkOpen = open(4),
                trunkOpen = open(5),
                trunkPosition = if (opening == 5) 85f else 0f,
                // Mirrors fold with the liftgate open; the passenger unbuckles to get out.
                mirrorFoldedL = open(5),
                mirrorFoldedR = open(5),
                seatHeatFL = 2f,
                seatHeatFR = 1f,
                seatBeltFL = 2f,
                seatBeltFR = if (opening == 1) 1f else 2f,
                buckleStatus = 1f,
                accSetSpeed = cruise(t) + 5,
                fullPackEnergy = 75f,
                // A 2 km drive at about 160 Wh/km ends at 30 kWh, where the charge starts.
                nominalEnergyRemaining = (30.32 - 0.32 * phase / CYCLE).toFloat(),
                ratedRange = rangeKm((30.32 - 0.32 * phase / CYCLE).toFloat()),
                energyBuffer = 1.5f,
                maxRegenPower = maxRegen,
                maxDischargePower = maxDischarge,
                packVoltage = packVoltage,
                packCurrent = packCurrent,
                packTMin = 22f,
                packTMax = 28f,
                tpmsPressureFL = (2.7 + 0.1 * warm).toFloat(),
                tpmsPressureFR = (2.775 + 0.1 * warm).toFloat(),
                tpmsPressureRL = (2.6 + 0.1 * warm - leak).toFloat(),
                tpmsPressureRR = (2.85 + 0.1 * warm + 0.12 * hot).toFloat(),
                tpmsTempFL = (12 + 10 * warm).toFloat(),
                tpmsTempFR = (12 + 10 * warm).toFloat(),
                tpmsTempRL = (13 + 10 * warm).toFloat(),
                tpmsTempRR = (13 + 10 * warm + 50 * hot).toFloat(),
                tpmsBattFL = 2.99f,
                tpmsBattFR = 2.98f,
                tpmsBattRL = 2.99f,
                tpmsBattRR = 2.71f,
                outsideTemp = 7f,
                cabinTemp = 20f,
                cabinHumidity = 20f,
                lvVoltage = 15.9f,
                lvPower = 0.7f,
                userPresent = 1f,
                odometer = (42000 + lap * 2 + 2 * min(t % FULL, CYCLE) / CYCLE).toFloat(),
                coolantBatIn = 24f,
                leadDist = lead.toFloat(),
                leadRelSpeed = leadRel.toFloat(),
                // Cars in the next lanes come and go.
                leadLeftDist = if (t % 20 < 12) (25 + 8 * sin(t * 0.2)).toFloat() else 0f,
                leadRightDist = if (t % 17 < 7) (60 + 15 * cos(t * 0.1)).toFloat() else 0f,
                acTemp = 22f,
                acTempRight = 21f,
                hvacFanLevel = 11f,
                hvacPowerState = 1f,
                powerLevel = max(-1f, min(1f, kw / if (kw >= 0) maxDischarge else maxRegen)) * 100,
                selfdriveActive = true,
                madsActive = true,
            )
        }
    }
}
