package com.example.capture

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sqrt

data class MotionSample(
    val gyroMagnitudeRadPerSec: Float = 0f,
    val isSteady: Boolean = true,
    val yawDeg: Float = 0f,
    val pitchDeg: Float = 0f,
    val rollDeg: Float = 0f,
    val aimYawDeg: Float = 0f,
    val aimUpDeg: Float = 0f,
    val rollRightDeg: Float = 0f,
    val normTiltX: Float = 0f,
    val normTiltY: Float = 0f,
    val tiltOffsetX: Float = 0f,
    val tiltOffsetY: Float = 0f,
    val tiltDeviationDeg: Float = 0f,
    val rollDeviationDeg: Float = 0f
)

class MotionSensorMonitor(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private val gyroSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val rotationSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    private val _motion = MutableStateFlow(MotionSample())
    val motion: StateFlow<MotionSample> = _motion.asStateFlow()

    private val rawRotationMatrix = FloatArray(9)
    private val remappedRotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)

    private var refRight: FloatArray? = null
    private var refUp: FloatArray? = null
    private var refNormal: FloatArray? = null

    private var isRunning = false
    private var lastDisplayRotation: Int = -1
    private var smoothedTiltX = 0f
    private var smoothedTiltY = 0f
    private var smoothedMeterX = 0f
    private var smoothedMeterY = 0f
    private var lastGyroMag = 0f

    fun start(includeGyro: Boolean = true) {
        if (isRunning) return
        isRunning = true
        // 30_000 us (~33Hz) balances ultra-smooth 3D tilt & low battery drain
        val samplingPeriodUs = 30_000
        if (includeGyro) {
            gyroSensor?.let {
                sensorManager.registerListener(this, it, samplingPeriodUs)
            }
        }
        rotationSensor?.let {
            sensorManager.registerListener(this, it, samplingPeriodUs)
        }
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false
        sensorManager.unregisterListener(this)
    }

    fun recenterTilt() {
        refRight = null
        refUp = null
        refNormal = null
        smoothedTiltX = 0f
        smoothedTiltY = 0f
        smoothedMeterX = 0f
        smoothedMeterY = 0f
        _motion.value = _motion.value.copy(
            aimYawDeg = 0f,
            aimUpDeg = 0f,
            rollRightDeg = 0f,
            normTiltX = 0f,
            normTiltY = 0f,
            tiltOffsetX = 0f,
            tiltOffsetY = 0f,
            tiltDeviationDeg = 0f,
            rollDeviationDeg = 0f
        )
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> {
                val gx = event.values[0]
                val gy = event.values[1]
                val gz = event.values[2]
                val mag = sqrt(gx * gx + gy * gy + gz * gz)
                lastGyroMag = lastGyroMag * 0.45f + mag * 0.55f
                _motion.value = _motion.value.copy(
                    gyroMagnitudeRadPerSec = lastGyroMag,
                    isSteady = lastGyroMag < 0.75f
                )
            }

            Sensor.TYPE_GAME_ROTATION_VECTOR,
            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rawRotationMatrix, event.values)

                val displayRotation = displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.rotation
                    ?: Surface.ROTATION_0

                if (displayRotation != lastDisplayRotation) {
                    lastDisplayRotation = displayRotation
                    refRight = null
                    refUp = null
                    refNormal = null
                }

                when (displayRotation) {
                    Surface.ROTATION_90 -> SensorManager.remapCoordinateSystem(
                        rawRotationMatrix,
                        SensorManager.AXIS_Y,
                        SensorManager.AXIS_MINUS_X,
                        remappedRotationMatrix
                    )
                    Surface.ROTATION_180 -> SensorManager.remapCoordinateSystem(
                        rawRotationMatrix,
                        SensorManager.AXIS_MINUS_X,
                        SensorManager.AXIS_MINUS_Y,
                        remappedRotationMatrix
                    )
                    Surface.ROTATION_270 -> SensorManager.remapCoordinateSystem(
                        rawRotationMatrix,
                        SensorManager.AXIS_MINUS_Y,
                        SensorManager.AXIS_X,
                        remappedRotationMatrix
                    )
                    else -> System.arraycopy(rawRotationMatrix, 0, remappedRotationMatrix, 0, 9)
                }

                SensorManager.getOrientation(remappedRotationMatrix, orientationAngles)
                val yaw = orientationAngles[0] * 180f / PI.toFloat()
                val pitch = orientationAngles[1] * 180f / PI.toFloat()
                val roll = orientationAngles[2] * 180f / PI.toFloat()

                // Columns of remappedRotationMatrix: device Right (+X), Up (+Y), ScreenNormal (+Z) in world space
                val curRightX = remappedRotationMatrix[0]
                val curRightY = remappedRotationMatrix[3]
                val curRightZ = remappedRotationMatrix[6]

                val curUpX = remappedRotationMatrix[1]
                val curUpY = remappedRotationMatrix[4]
                val curUpZ = remappedRotationMatrix[7]

                val curNormX = remappedRotationMatrix[2]
                val curNormY = remappedRotationMatrix[5]
                val curNormZ = remappedRotationMatrix[8]

                if (refRight == null || refUp == null || refNormal == null) {
                    refRight = floatArrayOf(curRightX, curRightY, curRightZ)
                    refUp = floatArrayOf(curUpX, curUpY, curUpZ)
                    refNormal = floatArrayOf(curNormX, curNormY, curNormZ)
                }

                val rRef = refRight!!
                val uRef = refUp!!

                // Gimbal-lock-free tilt of screen normal & up axis relative to starting neutral orientation
                val screenFaceRightSin = (curNormX * rRef[0] + curNormY * rRef[1] + curNormZ * rRef[2]).coerceIn(-1f, 1f)
                val screenFaceUpSin = (curNormX * uRef[0] + curNormY * uRef[1] + curNormZ * uRef[2]).coerceIn(-1f, 1f)
                val horizonRollRightSin = (curUpX * rRef[0] + curUpY * rRef[1] + curUpZ * rRef[2]).coerceIn(-1f, 1f)

                val faceRightDeg = asin(screenFaceRightSin) * 180f / PI.toFloat()
                val faceUpDeg = asin(screenFaceUpSin) * 180f / PI.toFloat()
                val rollRightDeg = asin(horizonRollRightSin) * 180f / PI.toFloat()

                // Back-camera look vector (-curNormal) relative to locked capture start orientation:
                // aimRightDeg > 0 when camera aims Right; aimUpDeg > 0 when camera aims Up
                val aimRightDeg = -faceRightDeg
                val aimUpDeg = -faceUpDeg

                // 3D Viewer / Review diorama parallax (zero gimbal lock at vertical, matches drag polarity):
                // Responsive non-linear curve reaches vivid depth under natural 2°-4.5° wrist tilts
                val rawTiltX = ((faceRightDeg + rollRightDeg * 0.75f) / 4.5f).coerceIn(-1f, 1f)
                val rawTiltY = (-faceUpDeg / 4.5f).coerceIn(-1f, 1f)
                val targetX = sign(rawTiltX) * abs(rawTiltX).pow(0.72f)
                val targetY = sign(rawTiltY) * abs(rawTiltY).pow(0.72f)

                smoothedTiltX += (targetX - smoothedTiltX) * 0.34f
                smoothedTiltY += (targetY - smoothedTiltY) * 0.34f

                // Sensitive Tilt Meter: ±5.5° reaches full edge; inner sweet-spot ring (58% radius) = ±3.2°
                val meterTargetX = ((rollRightDeg * 0.90f - faceRightDeg * 0.55f) / 5.5f).coerceIn(-1f, 1f)
                val meterTargetY = (faceUpDeg / 5.5f).coerceIn(-1f, 1f)
                smoothedMeterX += (meterTargetX - smoothedMeterX) * 0.40f
                smoothedMeterY += (meterTargetY - smoothedMeterY) * 0.40f

                val rollDevDeg = abs(rollRightDeg)
                val totalTiltDevDeg = hypot(
                    hypot(faceUpDeg, rollRightDeg),
                    faceRightDeg * 0.85f
                )

                _motion.value = _motion.value.copy(
                    yawDeg = yaw,
                    pitchDeg = pitch,
                    rollDeg = roll,
                    aimYawDeg = aimRightDeg,
                    aimUpDeg = aimUpDeg,
                    rollRightDeg = rollRightDeg,
                    normTiltX = smoothedTiltX,
                    normTiltY = smoothedTiltY,
                    tiltOffsetX = smoothedMeterX,
                    tiltOffsetY = smoothedMeterY,
                    tiltDeviationDeg = totalTiltDevDeg,
                    rollDeviationDeg = rollDevDeg
                )
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        fun wrapAngleDelta(currentDeg: Float, referenceDeg: Float): Float {
            var diff = currentDeg - referenceDeg
            while (diff > 180f) diff -= 360f
            while (diff < -180f) diff += 360f
            return diff
        }
    }
}
