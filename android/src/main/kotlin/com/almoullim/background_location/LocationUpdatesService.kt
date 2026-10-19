package com.almoullim.background_location

import android.Manifest
import android.content.pm.PackageManager
import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.*

class LocationUpdatesService : Service() {
    companion object {
        var NOTIFICATION_TITLE = "Background service is running"
        var NOTIFICATION_MESSAGE = "Background service is running"
        var NOTIFICATION_ICON = "@mipmap/ic_launcher"
        var UPDATE_INTERVAL_IN_MILLISECONDS: Long = 1000
        private const val CHANNEL_ID = "channel_01"
        private const val NOTIFICATION_ID = 12345678
        private const val STOP_SERVICE = "stop_service"
    }
    internal var onLocation: ((Location) -> Unit)? = null
    internal var onStopped: ((String) -> Unit)? = null
    private var fused: FusedLocationProviderClient? = null
    private var fusedCallback: LocationCallback? = null
    private var manager: LocationManager? = null
    private var managerCallback: LocationListener? = null
    private var active = false
    private var generation = 0
    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == STOP_SERVICE) stopTracking("service_stopped")
        }
    }

    inner class LocalBinder : Binder() { val service get() = this@LocationUpdatesService }
    override fun onBind(intent: Intent?): IBinder = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Location tracking", NotificationManager.IMPORTANCE_LOW)
            channel.setSound(null, null)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        ContextCompat.registerReceiver(this, stopReceiver, IntentFilter(STOP_SERVICE), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private val handler = Handler(Looper.getMainLooper())
    private var requested = TrackingConfig()
    private var effective = TrackingConfig()
    private var forceManager = false
    private var requestNumber = 0
    private var pendingFused: Pair<FusedLocationProviderClient, LocationCallback>? = null
    private var pendingReply: ((String?) -> Unit)? = null
    private var pendingTimeout: Runnable? = null
    internal var wakeSource: MotionWakeSource? = null
    private var sensorArmed = false
    private var stationaryAnchor: Location? = null
    private var stationarySince = 0L
    internal var motion = "moving"
        private set
    internal var onDiagnostic: ((Map<String, Any?>) -> Unit)? = null
    internal fun state() = mapOf("configuration" to requested.toMap(), "effective_configuration" to effective.toMap(),
        "motion" to motion, "motion_available" to sensorArmed)
    private val recover = Runnable { markMoving() }
    private fun armSensor() {
        if (!requested.adaptive || !active || sensorArmed) return
        if (wakeSource == null) wakeSource = SignificantMotionWakeSource(this)
        sensorArmed = wakeSource?.arm {
            sensorArmed = false
            if (active && requested.adaptive) { markMoving(); armSensor() }
        } == true
    }
    private fun resetMotion() {
        handler.removeCallbacks(recover)
        wakeSource?.cancel()
        sensorArmed = false
        stationaryAnchor = null; stationarySince = 0; motion = "moving"
    }
    private fun markMoving() {
        stationaryAnchor = null; stationarySince = 0
        handler.removeCallbacks(recover)
        if (motion != "moving" && active) {
            motion = "moving"
            // Never interrupt an explicit configuration already being applied.
            if (pendingReply == null) subscribe(requested) { error -> if (error != null) stopTracking(error) }
        }
    }
    private fun observe(location: Location) {
        onLocation?.invoke(location)
        if (!requested.adaptive || !sensorArmed || pendingReply != null) return
        val age = SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos
        if (age < 0 || age > 30_000_000_000L || !location.hasAccuracy() || !location.accuracy.isFinite() || location.accuracy < 0 || location.accuracy > 25 ||
            !location.hasSpeed() || !location.speed.isFinite() || location.speed < 0) return
        if (location.speed > 0.5f || (stationaryAnchor?.distanceTo(location) ?: 0f) > 15) { markMoving(); return }
        if (motion == "stationary") return
        if (stationaryAnchor == null) { stationaryAnchor = Location(location); stationarySince = SystemClock.elapsedRealtime(); return }
        if (SystemClock.elapsedRealtime() - stationarySince < 120000) return
        // GPS-only hardware must retain GPS; a balanced fallback needs network.
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        if (manager != null && !lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) return
        motion = "stationary"
        subscribe(requested.stationary()) { error ->
            if (error != null) stopTracking(error)
            else handler.postDelayed(recover, 120000)
        }
    }

    internal fun startTracking(distance: Double, forceManager: Boolean, accuracy: String = "high", reply: (String?) -> Unit) {
        startTracking(TrackingConfig(accuracy, UPDATE_INTERVAL_IN_MILLISECONDS, distance), forceManager, reply)
    }
    internal fun startTracking(config: TrackingConfig, forceManager: Boolean, reply: (String?) -> Unit) {
        active = true
        this.forceManager = forceManager
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            else startForeground(NOTIFICATION_ID, notification())
            configure(config, reply)
        } catch (error: RuntimeException) {
            stopTracking()
            reply(if (error is SecurityException) "permission_denied" else "startup_failed")
        }
    }
    internal fun configure(config: TrackingConfig, reply: (String?) -> Unit) {
        if (!active) { reply("service_stopped"); return }
        resetMotion()
        requested = config
        armSensor()
        subscribe(config, reply)
    }
    private fun finishRequest(error: String?) {
        if (error != null) pendingFused?.let { (client, callback) ->
            try { client.removeLocationUpdates(callback) } catch (_: SecurityException) { }
        }
        pendingFused = null
        pendingTimeout?.let(handler::removeCallbacks); pendingTimeout = null
        val reply = pendingReply; pendingReply = null
        reply?.invoke(error)
    }
    private fun subscribe(config: TrackingConfig, reply: (String?) -> Unit) {
        val number = ++requestNumber
        finishRequest("cancelled")
        pendingReply = reply
        pendingTimeout = Runnable {
            if (requestNumber == number) { requestNumber++; finishRequest("subscription_timeout"); stopTracking("subscription_timeout") }
        }.also { handler.postDelayed(it, 30000) }
        val token = generation
        try {
            if (!forceManager && GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(this) == ConnectionResult.SUCCESS) {
                val client = LocationServices.getFusedLocationProviderClient(this)
                val callback = object : LocationCallback() {
                    override fun onLocationResult(result: LocationResult) {
                        if (active && generation == token && fusedCallback === this) result.lastLocation?.let { observe(it) }
                    }
                }
                val priority = when (config.accuracy) {
                    "balanced" -> Priority.PRIORITY_BALANCED_POWER_ACCURACY
                    "lowPower" -> Priority.PRIORITY_LOW_POWER
                    else -> Priority.PRIORITY_HIGH_ACCURACY
                }
                val request = LocationRequest.Builder(priority, config.interval)
                    .setMinUpdateIntervalMillis(config.interval / 2)
                    .setMinUpdateDistanceMeters(config.distance.toFloat()).build()
                pendingFused = Pair(client, callback)
                client.requestLocationUpdates(request, callback, Looper.getMainLooper())
                    .addOnSuccessListener {
                        if (!active || generation != token || number != requestNumber) { client.removeLocationUpdates(callback); return@addOnSuccessListener }
                        removeProviders()
                        fused = client; fusedCallback = callback
                        effective = config; emitConfiguration(); finishRequest(null)
                    }.addOnFailureListener { error ->
                        if (number == requestNumber) finishRequest(if (error is SecurityException) "permission_denied" else "subscription_failed")
                    }
            } else {
                val provider = getSystemService(LOCATION_SERVICE) as LocationManager
                val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                val candidates = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                    .filter { it in provider.allProviders && (it != LocationManager.GPS_PROVIDER || (fine && config.accuracy == "high")) }
                if (candidates.none { provider.isProviderEnabled(it) }) { finishRequest("location_services_disabled"); return }
                val filter = FallbackFixFilter()
                val callback = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        if (active && generation == token && managerCallback === this && filter.accept(location, SystemClock.elapsedRealtimeNanos())) observe(location)
                    }
                    override fun onProviderDisabled(name: String) {
                        if (active && managerCallback === this && candidates.none { provider.isProviderEnabled(it) }) stopTracking("location_services_disabled")
                    }
                    override fun onProviderEnabled(provider: String) { }
                    @Deprecated("Required for older Android")
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) { }
                }
                try {
                    for (name in candidates) provider.requestLocationUpdates(name, config.interval, config.distance.toFloat(), callback, Looper.getMainLooper())
                } catch (error: RuntimeException) { provider.removeUpdates(callback); throw error }
                removeProviders()
                manager = provider; managerCallback = callback
                effective = config; emitConfiguration(); finishRequest(null)
            }
        } catch (error: RuntimeException) {
            finishRequest(if (error is SecurityException) "permission_denied" else "subscription_failed")
        }
    }
    private fun emitConfiguration() {
        onDiagnostic?.invoke(mapOf("event" to "configuration", "motion" to motion,
            "backend" to if (manager != null) "location_manager" else "fused",
            "interval_ms" to effective.interval, "accuracy" to effective.accuracy,
            "motion_available" to sensorArmed))
    }
    private fun removeProviders() {
        val client = fused; val callback = fusedCallback
        val provider = manager; val listener = managerCallback
        fused = null; fusedCallback = null; manager = null; managerCallback = null
        try { if (client != null && callback != null) client.removeLocationUpdates(callback) } catch (_: SecurityException) { }
        try { if (provider != null && listener != null) provider.removeUpdates(listener) } catch (_: SecurityException) { }
    }
    internal fun stopTracking(reason: String? = null) {
        val wasActive = active
        active = false; generation++; requestNumber++
        resetMotion(); finishRequest("cancelled"); removeProviders()
        stopForeground(true); stopSelf()
        if (wasActive && reason != null) onStopped?.invoke(reason)
    }

    internal fun updateNotification() {
        if (active) (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification())
    }

    private fun notification(): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(NOTIFICATION_TITLE).setContentText(NOTIFICATION_MESSAGE)
            .setStyle(NotificationCompat.BigTextStyle().bigText(NOTIFICATION_MESSAGE))
            .setOngoing(true).setSound(null).setPriority(NotificationCompat.PRIORITY_LOW)
            .setSmallIcon(notificationIcon())
        packageManager.getLaunchIntentForPackage(packageName)?.let { intent ->
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
            builder.setContentIntent(PendingIntent.getActivity(this, 1, intent, flags))
        }
        return builder.build()
    }

    private fun notificationIcon(): Int {
        val name = NOTIFICATION_ICON.removePrefix("@").substringAfter('/')
        val preferredType = if (NOTIFICATION_ICON.startsWith("@drawable/")) "drawable" else "mipmap"
        val preferred = resources.getIdentifier(name, preferredType, packageName)
        if (preferred != 0) return preferred
        val fallback = resources.getIdentifier(name, if (preferredType == "drawable") "mipmap" else "drawable", packageName)
        return if (fallback != 0) fallback else applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.ic_menu_mylocation
    }

    override fun onDestroy() {
        stopTracking("service_destroyed")
        onStopped = null; onLocation = null
        unregisterReceiver(stopReceiver)
        super.onDestroy()
    }
}
