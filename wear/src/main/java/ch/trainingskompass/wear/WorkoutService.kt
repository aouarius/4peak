package ch.trainingskompass.wear

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import androidx.health.services.client.ExerciseUpdateCallback
import androidx.health.services.client.HealthServices
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.ExerciseConfig
import androidx.health.services.client.data.ExerciseLapSummary
import androidx.health.services.client.data.ExerciseType
import androidx.health.services.client.data.ExerciseUpdate
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Läuft während eines Trainings im Vordergrund:
 * - misst Puls und Kalorien über Health Services (wie die Samsung-Trainings-App),
 * - zeigt das Training als laufende Aktivität auf dem Zifferblatt,
 * - vibriert am Ende der Pause auch bei ausgeschaltetem Bildschirm.
 */
class WorkoutService : Service(), SensorEventListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    /**
     * Hält den Prozessor während des ganzen Trainings wach. Ohne ihn schläft die Uhr bei dunklem
     * Bildschirm ein: Der Bewegungssensor verliert dann Daten (Lücken bis 100 s in den Exporten)
     * und der Satz beginnt für den Zähler erst, wenn man wieder auf die Uhr schaut.
     */
    private var workoutLock: PowerManager.WakeLock? = null
    private var exerciseRunning = false
    private var sensorFallback = false

    private val restEnd = Runnable {
        buzz(longArrayOf(0, 350, 150, 350, 150, 350))
        restUntil.value = 0
        releaseWake()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> begin(intent.getStringExtra(EXTRA_TITLE) ?: "Training")
            ACTION_REST -> rest(intent.getLongExtra(EXTRA_UNTIL, 0))
            ACTION_STOP -> finish()
        }
        return START_NOT_STICKY
    }

    private fun begin(title: String) {
        hr.value = 0; kcal.value = 0; hrSum = 0; hrCount = 0; hrMax = 0
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Training", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, WatchActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText("Training läuft")
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setOngoing(true)
            .setContentIntent(open)
        // Laufende Aktivität: Symbol auf dem Zifferblatt, ein Tipp führt zurück ins Training
        val status = Status.Builder().addTemplate("#t#").addPart("t", Status.TextPart(title)).build()
        OngoingActivity.Builder(this, NOTIFICATION_ID, builder)
            .setStaticIcon(R.drawable.ic_notify)
            .setTouchIntent(open)
            .setStatus(status)
            .build()
            .apply(this)
        val ok = runCatching {
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, builder.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
            else startForeground(NOTIFICATION_ID, builder.build())
        }.isSuccess
        if (!ok) {
            // Ohne Sensor-Erlaubnis darf kein Gesundheits-Dienst laufen; das Training geht trotzdem.
            stopSelf()
            return
        }
        active.value = true
        if (workoutLock?.isHeld != true) workoutLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "4peak:training")
            .apply { setReferenceCounted(false); acquire(3 * 60 * 60 * 1000L) }
        scope.launch { startExercise() }
    }

    private suspend fun startExercise() {
        val client = HealthServices.getClient(this).exerciseClient
        runCatching {
            val caps = client.getCapabilitiesAsync().await()
            val type = listOf(ExerciseType.STRENGTH_TRAINING, ExerciseType.WEIGHTLIFTING, ExerciseType.WORKOUT)
                .firstOrNull { it in caps.supportedExerciseTypes } ?: error("kein Trainingstyp")
            val supported = caps.getExerciseTypeCapabilities(type).supportedDataTypes
            val wanted = setOf<DataType<*, *>>(DataType.HEART_RATE_BPM, DataType.CALORIES_TOTAL).filter { it in supported }.toSet()
            client.setUpdateCallback(callback)
            client.startExerciseAsync(
                ExerciseConfig.builder(type)
                    .setDataTypes(wanted)
                    .setIsAutoPauseAndResumeEnabled(false)
                    .setIsGpsEnabled(false)
                    .build(),
            ).await()
            exerciseRunning = true
        }.onFailure { startSensorFallback() }
    }

    private val callback = object : ExerciseUpdateCallback {
        override fun onExerciseUpdateReceived(update: ExerciseUpdate) {
            update.latestMetrics.getData(DataType.HEART_RATE_BPM).lastOrNull()?.value?.let { addHr(it.roundToInt()) }
            update.latestMetrics.getData(DataType.CALORIES_TOTAL)?.total?.let { kcal.value = it.roundToInt() }
        }
        override fun onLapSummaryReceived(lapSummary: ExerciseLapSummary) {}
        override fun onRegistered() {}
        override fun onRegistrationFailed(throwable: Throwable) {}
        override fun onAvailabilityChanged(dataType: DataType<*, *>, availability: Availability) {}
    }

    /** Ältere Uhren ohne Health Services: Pulssensor direkt lesen. */
    private fun startSensorFallback() {
        val sm = getSystemService(SensorManager::class.java) ?: return
        sm.getDefaultSensor(Sensor.TYPE_HEART_RATE)?.let {
            sensorFallback = sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        event.values.firstOrNull()?.roundToInt()?.let { addHr(it) }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun addHr(v: Int) {
        if (v <= 0) return
        hr.value = v
        hrSum += v; hrCount++
        if (v > hrMax) hrMax = v
    }

    private fun rest(until: Long) {
        handler.removeCallbacks(restEnd)
        restUntil.value = until
        val delay = until - System.currentTimeMillis()
        if (until <= 0 || delay <= 0) { releaseWake(); return }
        // Kurzer Wake-Lock nur für die Pause, damit die Vibration pünktlich kommt
        releaseWake()
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "4peak:pause")
            .apply { acquire(delay + 5_000) }
        handler.postDelayed(restEnd, delay)
    }

    private fun releaseWake() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun releaseWorkout() {
        workoutLock?.let { if (it.isHeld) it.release() }
        workoutLock = null
    }

    private fun buzz(pattern: LongArray) {
        getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    private fun finish() {
        handler.removeCallbacks(restEnd)
        releaseWake()
        releaseWorkout()
        if (sensorFallback) getSystemService(SensorManager::class.java)?.unregisterListener(this)
        sensorFallback = false
        val wasRunning = exerciseRunning
        exerciseRunning = false
        active.value = false
        scope.launch {
            if (wasRunning) runCatching {
                val client = HealthServices.getClient(this@WorkoutService).exerciseClient
                client.endExerciseAsync().await()
                client.clearUpdateCallbackAsync(callback).await()
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(restEnd)
        releaseWake()
        releaseWorkout()
        active.value = false
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "training"
        private const val NOTIFICATION_ID = 7
        private const val ACTION_START = "start"
        private const val ACTION_REST = "rest"
        private const val ACTION_STOP = "stop"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_UNTIL = "until"

        val active = MutableStateFlow(false)
        val hr = MutableStateFlow(0)
        val kcal = MutableStateFlow(0)
        val restUntil = MutableStateFlow(0L)
        @Volatile private var hrSum = 0L
        @Volatile private var hrCount = 0L
        @Volatile var hrMax = 0
            private set

        val hrAvg: Int get() = if (hrCount > 0) (hrSum / hrCount).toInt() else 0

        fun start(ctx: Context, title: String) = send(ctx, Intent(ctx, WorkoutService::class.java).setAction(ACTION_START).putExtra(EXTRA_TITLE, title), foreground = true)
        fun rest(ctx: Context, until: Long) { if (active.value) send(ctx, Intent(ctx, WorkoutService::class.java).setAction(ACTION_REST).putExtra(EXTRA_UNTIL, until)) }
        fun stop(ctx: Context) { if (active.value) send(ctx, Intent(ctx, WorkoutService::class.java).setAction(ACTION_STOP)) }

        private fun send(ctx: Context, i: Intent, foreground: Boolean = false) {
            runCatching { if (foreground) ctx.startForegroundService(i) else ctx.startService(i) }
        }
    }
}
