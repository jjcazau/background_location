package com.almoullim.background_location

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

    internal fun startTracking(distance: Double, forceManager: Boolean, reply: (String?) -> Unit) {
        val token = ++generation
        active = true
        try {
            // Permission has been resolved by the bridge before binding. Promote only
            // now, and report foreground restrictions as startup errors.
            val notification = notification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else startForeground(NOTIFICATION_ID, notification)

            // Select the provider before creating any provider-specific state.
            if (!forceManager && GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(this) == ConnectionResult.SUCCESS) {
                val client = LocationServices.getFusedLocationProviderClient(this)
                val callback = object : LocationCallback() {
                    override fun onLocationResult(result: LocationResult) {
                        if (active && generation == token) result.lastLocation?.let { onLocation?.invoke(it) }
                    }
                }
                fused = client
                fusedCallback = callback
                val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, UPDATE_INTERVAL_IN_MILLISECONDS)
                    .setMinUpdateIntervalMillis(UPDATE_INTERVAL_IN_MILLISECONDS / 2)
                    .setMinUpdateDistanceMeters(distance.toFloat()).build()
                client.requestLocationUpdates(request, callback, Looper.getMainLooper())
                    .addOnSuccessListener {
                        if (active && generation == token) reply(null)
                        else client.removeLocationUpdates(callback)
                    }
                    .addOnFailureListener { error ->
                        if (active && generation == token) {
                            stopTracking()
                            reply(if (error is SecurityException) "permission_denied" else "subscription_failed")
                        }
                    }
            } else {
                val provider = getSystemService(LOCATION_SERVICE) as LocationManager
                if (!provider.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    stopTracking()
                    reply("location_services_disabled")
                    return
                }
                val callback = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        if (active && generation == token) onLocation?.invoke(location)
                    }
                    override fun onProviderDisabled(provider: String) {
                        if (active && generation == token) stopTracking("location_services_disabled")
                    }
                    override fun onProviderEnabled(provider: String) { }
                    @Deprecated("Required for older Android LocationListener implementations")
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) { }
                }
                manager = provider
                managerCallback = callback
                provider.requestLocationUpdates(LocationManager.GPS_PROVIDER, UPDATE_INTERVAL_IN_MILLISECONDS,
                    distance.toFloat(), callback, Looper.getMainLooper())
                reply(null)
            }
        } catch (error: RuntimeException) {
            stopTracking()
            reply(if (error is SecurityException) "permission_denied" else "startup_failed")
        }
    }

    internal fun stopTracking(reason: String? = null) {
        val wasActive = active
        active = false
        generation++
        val client = fused
        val callback = fusedCallback
        val provider = manager
        val listener = managerCallback
        fused = null; fusedCallback = null; manager = null; managerCallback = null
        try { if (client != null && callback != null) client.removeLocationUpdates(callback) }
        catch (_: SecurityException) { /* Permission may already be revoked. */ }
        try { if (provider != null && listener != null) provider.removeUpdates(listener) }
        catch (_: SecurityException) { /* Still remove foreground state and references. */ }
        stopForeground(true)
        stopSelf()
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
