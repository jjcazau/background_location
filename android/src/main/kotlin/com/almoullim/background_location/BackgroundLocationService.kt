package com.almoullim.background_location

import android.Manifest
import android.app.Activity
import android.content.*
import android.content.pm.PackageManager
import android.os.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import android.location.LocationManager
import io.flutter.plugin.common.*

class BackgroundLocationService : MethodChannel.MethodCallHandler, PluginRegistry.RequestPermissionsResultListener {
    companion object { private const val PERMISSION_REQUEST = 34 }
    private var context: Context? = null
    private var activity: Activity? = null
    private var channel: MethodChannel? = null
    private var connection: ServiceConnection? = null
    private var service: LocationUpdatesService? = null
    private var permissionInFlight = false
    private var distance = 0.0
    private var forceManager = false
    private val handler = Handler(Looper.getMainLooper())
    private var timeout: Runnable? = null
    private val session = TrackingSession(::cleanup) { state, code ->
        channel?.invokeMethod("service_state", mapOf("state" to state, "code" to code))
    }
    private val healthCheck = object : Runnable {
        override fun run() {
            if (session.state != "running") return
            checkHealth()
            if (session.state == "running") handler.postDelayed(this, 5000)
        }
    }

    private fun checkHealth() {
        if (!hasPermission()) session.fail(session.token, "permission_denied")
        else if (!locationEnabled()) session.fail(session.token, "location_services_disabled")
    }

    private fun locationEnabled(): Boolean {
        val provider = context?.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return LocationManagerCompat.isLocationEnabled(provider)
    }

    fun onAttachedToEngine(context: Context, messenger: BinaryMessenger) {
        this.context = context
        channel = MethodChannel(messenger, "${BackgroundLocationPlugin.PLUGIN_ID}/methods")
        channel?.setMethodCallHandler(this)
    }

    fun onDetachedFromEngine() {
        session.stop()
        permissionInFlight = false
        activity = null
        channel?.setMethodCallHandler(null)
        channel = null
        context = null
    }

    fun setActivity(activity: Activity?) {
        this.activity = activity
        if (activity != null && session.state == "running") checkHealth()
        if (activity != null && session.state == "starting" && connection == null && hasPermission()) {
            bind(session.token)
        }
    }

    private fun hasPermission(): Boolean {
        val ctx = context ?: return false
        return ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun begin(token: Int) {
        timeout = Runnable { session.fail(token, "startup_timeout") }.also { handler.postDelayed(it, 30000) }
        if (hasPermission()) { bind(token); return }
        val currentActivity = activity
        if (currentActivity == null) { session.fail(token, "activity_unavailable"); return }
        if (!permissionInFlight) {
            permissionInFlight = true
            try {
                ActivityCompat.requestPermissions(currentActivity,
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), PERMISSION_REQUEST)
            } catch (error: RuntimeException) {
                permissionInFlight = false
                session.fail(token, "permission_request_failed")
            }
        }
    }

    private fun bind(token: Int) {
        val ctx = context ?: run { session.fail(token, "engine_detached"); return }
        if (!session.current(token) || connection != null) return
        if (!hasPermission()) { session.fail(token, "permission_denied"); return }
        if (!locationEnabled()) { session.fail(token, "location_services_disabled"); return }
        val candidate = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                if (!session.current(token) || connection !== this) return
                val native = (binder as? LocationUpdatesService.LocalBinder)?.service
                if (native == null) { session.fail(token, "invalid_service"); return }
                service = native
                native.onLocation = { location ->
                    if (session.current(token)) channel?.invokeMethod("location", mapOf(
                        "latitude" to location.latitude, "longitude" to location.longitude,
                        "altitude" to location.altitude, "accuracy" to location.accuracy.toDouble(),
                        "bearing" to location.bearing.toDouble(), "speed" to location.speed.toDouble(),
                        "time" to location.time.toDouble(), "is_mock" to location.isFromMockProvider))
                }
                native.onStopped = { code -> session.fail(token, code) }
                native.startTracking(distance, forceManager) { error ->
                    if (!session.current(token)) return@startTracking
                    if (error == null) {
                        timeout?.let(handler::removeCallbacks)
                        timeout = null
                        session.ready(token)
                        handler.removeCallbacks(healthCheck)
                        handler.postDelayed(healthCheck, 5000)
                    } else session.fail(token, error)
                }
            }
            override fun onServiceDisconnected(name: ComponentName) = session.fail(token, "service_disconnected")
            override fun onBindingDied(name: ComponentName) = session.fail(token, "service_disconnected")
            override fun onNullBinding(name: ComponentName) = session.fail(token, "invalid_service")
        }
        // Record the pending binding so stop can cancel it before onServiceConnected.
        connection = candidate
        try {
            if (!ctx.bindService(Intent(ctx, LocationUpdatesService::class.java), candidate, Context.BIND_AUTO_CREATE)) {
                session.fail(token, "bind_failed")
            }
        } catch (error: RuntimeException) {
            session.fail(token, if (error is SecurityException) "permission_denied" else "bind_failed")
        }
    }

    private fun cleanup() {
        permissionInFlight = false
        handler.removeCallbacks(healthCheck)
        timeout?.let(handler::removeCallbacks)
        timeout = null
        val old = service
        service = null
        old?.onStopped = null
        old?.onLocation = null
        old?.stopTracking()
        val oldConnection = connection
        connection = null
        if (oldConnection != null) {
            try { context?.unbindService(oldConnection) } catch (_: IllegalArgumentException) { }
        }
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "start_location_service" -> {
                val requestedDistance = (call.argument<Number>("distance_filter"))?.toDouble() ?: 0.0
                if (!requestedDistance.isFinite() || requestedDistance < 0) {
                    result.error("invalid_argument", "distanceFilter must be finite and non-negative", null)
                    return
                }
                if (session.state != "starting" && session.state != "running") {
                    distance = requestedDistance
                    forceManager = call.argument<Boolean>("force_location_manager") ?: false
                }
                session.start({ code ->
                    if (code == null) result.success(true) else result.error(code, "Location startup failed: $code", null)
                }, ::begin)
            }
            "stop_location_service" -> { session.stop(); result.success(true) }
            "is_service_running" -> result.success(session.state == "running")
            "set_android_notification" -> {
                call.argument<String>("title")?.let { LocationUpdatesService.NOTIFICATION_TITLE = it }
                call.argument<String>("message")?.let { LocationUpdatesService.NOTIFICATION_MESSAGE = it }
                call.argument<String>("icon")?.let { LocationUpdatesService.NOTIFICATION_ICON = it }
                try { service?.updateNotification(); result.success(true) }
                catch (error: RuntimeException) { result.error("notification_failed", error.message, null) }
            }
            "set_configuration" -> {
                val interval = call.argument<String>("interval")?.toLongOrNull()
                if (interval == null || interval <= 0) result.error("invalid_argument", "interval must be positive", null)
                else { LocationUpdatesService.UPDATE_INTERVAL_IN_MILLISECONDS = interval; result.success(true) }
            }
            else -> result.notImplemented()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray): Boolean {
        if (requestCode != PERMISSION_REQUEST) return false
        permissionInFlight = false
        if (session.state == "starting") {
            if (hasPermission()) bind(session.token) else session.fail(session.token, "permission_denied")
        }
        return true
    }
}
