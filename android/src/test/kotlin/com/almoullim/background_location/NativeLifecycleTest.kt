package com.almoullim.background_location

import android.Manifest
import android.app.Activity
import android.content.*
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import android.os.Looper
import io.flutter.plugin.common.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35])
class NativeLifecycleTest {
    private class Reply : MethodChannel.Result {
        var calls = 0
        var error: String? = null
        var value: Any? = null
        override fun success(result: Any?) { calls++; value = result }
        override fun error(code: String, message: String?, details: Any?) { calls++; error = code }
        override fun notImplemented() { throw AssertionError("Unexpected method") }
    }
    private class Messenger : BinaryMessenger {
        val events = mutableListOf<MethodCall>()
        override fun send(channel: String, message: ByteBuffer?) {
            if (message != null) {
                message.flip()
                events.add(StandardMethodCodec.INSTANCE.decodeMethodCall(message))
            }
        }
        override fun send(channel: String, message: ByteBuffer?, callback: BinaryMessenger.BinaryReply?) = send(channel, message)
        override fun setMessageHandler(channel: String, handler: BinaryMessenger.BinaryMessageHandler?) { }
    }
    private class BindingContext : ContextWrapper(RuntimeEnvironment.getApplication()) {
        var binds = 0
        var unbinds = 0
        var pending: ServiceConnection? = null
        override fun bindService(intent: Intent, connection: ServiceConnection, flags: Int): Boolean {
            binds++; pending = connection; return true
        }
        override fun unbindService(connection: ServiceConnection) { unbinds++ }
    }
    private fun enableLocation() {
        val app = RuntimeEnvironment.getApplication()
        // The signature permission is granted automatically on a device; the
        // library unit-test application has a different generated package ID.
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION,
            "${app.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        val manager = RuntimeEnvironment.getApplication().getSystemService(Context.LOCATION_SERVICE) as LocationManager
        shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
    }
    private fun start(bridge: BackgroundLocationService): Reply = Reply().also {
        bridge.onMethodCall(MethodCall("start_location_service", mapOf("distance_filter" to 12.0, "force_location_manager" to true)), it)
    }

    @Test fun bindingIsCancelledBeforeConnectAndLateConnectCannotStartTracking() {
        enableLocation()
        val context = BindingContext()
        val bridge = BackgroundLocationService()
        bridge.onAttachedToEngine(context, Messenger())
        val reply = start(bridge)
        assertEquals(0, reply.calls)
        assertEquals(1, context.binds)
        val connection = context.pending!!
        bridge.onMethodCall(MethodCall("stop_location_service", null), Reply())
        assertEquals("cancelled", reply.error)
        assertEquals(1, context.unbinds)
        val controller = Robolectric.buildService(LocationUpdatesService::class.java).create()
        connection.onServiceConnected(ComponentName(context, LocationUpdatesService::class.java), controller.get().LocalBinder())
        assertEquals(1, reply.calls)
        val running = Reply()
        bridge.onMethodCall(MethodCall("is_service_running", null), running)
        assertEquals(false, running.value)
        bridge.onDetachedFromEngine()
        controller.destroy()
    }

    @Test fun permissionDenialNeverBindsAndUnrelatedPermissionResultsAreNotConsumed() {
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        val context = BindingContext()
        val bridge = BackgroundLocationService()
        bridge.onAttachedToEngine(context, Messenger())
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        bridge.setActivity(activity)
        val reply = start(bridge)
        assertEquals(0, context.binds)
        assertEquals(0, reply.calls)
        assertFalse(bridge.onRequestPermissionsResult(999, emptyArray(), intArrayOf()))
        assertTrue(bridge.onRequestPermissionsResult(34, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), intArrayOf(PackageManager.PERMISSION_DENIED)))
        assertEquals("permission_denied", reply.error)
        bridge.onDetachedFromEngine()
    }

    @Test fun pendingBindTimesOutAndCleansUp() {
        enableLocation()
        val context = BindingContext()
        val bridge = BackgroundLocationService()
        bridge.onAttachedToEngine(context, Messenger())
        val reply = start(bridge)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(31))
        assertEquals("startup_timeout", reply.error)
        assertEquals(1, context.unbinds)
        bridge.onDetachedFromEngine()
    }

    @Test fun fallbackDeliversFixesSurvivesActivityReplacementAndStopsOnRevocation() {
        enableLocation()
        val context = BindingContext()
        val messenger = Messenger()
        val bridge = BackgroundLocationService()
        bridge.onAttachedToEngine(context, messenger)
        val reply = start(bridge)
        val controller = Robolectric.buildService(LocationUpdatesService::class.java).create()
        context.pending!!.onServiceConnected(ComponentName(context, LocationUpdatesService::class.java), controller.get().LocalBinder())
        assertEquals(true, reply.value)
        bridge.setActivity(null)
        val running = Reply()
        bridge.onMethodCall(MethodCall("is_service_running", null), running)
        assertEquals(true, running.value)
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val request = shadowOf(manager).getLegacyLocationRequests(LocationManager.GPS_PROVIDER).single()
        assertEquals(LocationUpdatesService.UPDATE_INTERVAL_IN_MILLISECONDS, request.intervalMillis)
        assertEquals(12f, request.minUpdateDistanceMeters, 0f)
        shadowOf(manager).simulateLocation(Location(LocationManager.GPS_PROVIDER).apply { latitude = 1.0; longitude = 2.0; accuracy = 5f; elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos() })
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(messenger.events.any { it.method == "location" })
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        assertTrue(messenger.events.any { it.method == "service_state" && it.argument<String>("code") == "permission_denied" })
        assertEquals(1, context.unbinds)
        assertTrue(shadowOf(manager).getLocationUpdateListeners(LocationManager.GPS_PROVIDER).isEmpty())
        bridge.onDetachedFromEngine()
        controller.destroy()
    }
    @Test fun networkFallbackSurvivesGpsLossAndCleansBothProviders() {
        enableLocation()
        val manager = RuntimeEnvironment.getApplication().getSystemService(Context.LOCATION_SERVICE) as LocationManager
        shadowOf(manager).setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
        val controller = Robolectric.buildService(LocationUpdatesService::class.java).create()
        val service = controller.get()
        val fixes = mutableListOf<Location>()
        val stopped = mutableListOf<String>()
        service.onLocation = { fixes.add(it) }
        service.onStopped = { stopped.add(it) }
        service.startTracking(0.0, true) { assertNull(it) }
        assertEquals(1, shadowOf(manager).getLocationUpdateListeners(LocationManager.GPS_PROVIDER).size)
        assertEquals(1, shadowOf(manager).getLocationUpdateListeners(LocationManager.NETWORK_PROVIDER).size)
        shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, false)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(stopped.isEmpty())
        shadowOf(manager).simulateLocation(Location(LocationManager.NETWORK_PROVIDER).apply {
            latitude = 1.0; longitude = 2.0; accuracy = 50f
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        })
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, fixes.size)
        shadowOf(manager).setProviderEnabled(LocationManager.NETWORK_PROVIDER, false)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("location_services_disabled"), stopped)
        assertTrue(shadowOf(manager).getLocationUpdateListeners(LocationManager.GPS_PROVIDER).isEmpty())
        assertTrue(shadowOf(manager).getLocationUpdateListeners(LocationManager.NETWORK_PROVIDER).isEmpty())
        controller.destroy()
    }

    @Test fun coarseOnlyPermissionUsesNetworkAndReportsOptionalMetadata() {
        enableLocation()
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        val manager = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, false)
        shadowOf(manager).setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
        val context = BindingContext()
        val messenger = Messenger()
        val bridge = BackgroundLocationService()
        bridge.onAttachedToEngine(context, messenger)
        val reply = start(bridge)
        val controller = Robolectric.buildService(LocationUpdatesService::class.java).create()
        context.pending!!.onServiceConnected(ComponentName(context, LocationUpdatesService::class.java), controller.get().LocalBinder())
        assertEquals(true, reply.value)
        assertTrue(shadowOf(manager).getLocationUpdateListeners(LocationManager.GPS_PROVIDER).isEmpty())
        shadowOf(manager).simulateLocation(Location(LocationManager.NETWORK_PROVIDER).apply {
            latitude = 1.0; longitude = 2.0; accuracy = 20f
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            speedAccuracyMetersPerSecond = 0.5f; verticalAccuracyMeters = 3f
        })
        shadowOf(Looper.getMainLooper()).idle()
        val event = messenger.events.single { it.method == "location" }
        assertEquals("network", event.argument<String>("provider"))
        assertEquals(0.5, event.argument<Double>("speed_accuracy")!!, 0.0)
        assertEquals(3.0, event.argument<Double>("vertical_accuracy")!!, 0.0)
        assertNull(event.argument<Double>("bearing_accuracy"))
        bridge.onDetachedFromEngine()
        assertTrue(shadowOf(manager).getLocationUpdateListeners(LocationManager.NETWORK_PROVIDER).isEmpty())
        controller.destroy()
    }

    @Test fun balancedAndLowPowerAvoidGpsAndInvalidAccuracyNeverBinds() {
        enableLocation()
        val manager = RuntimeEnvironment.getApplication().getSystemService(Context.LOCATION_SERVICE) as LocationManager
        shadowOf(manager).setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
        for (accuracy in listOf("balanced", "lowPower")) {
            val controller = Robolectric.buildService(LocationUpdatesService::class.java).create()
            controller.get().startTracking(9.0, true, accuracy) { assertNull(it) }
            assertTrue(shadowOf(manager).getLocationUpdateListeners(LocationManager.GPS_PROVIDER).isEmpty())
            assertEquals(9f, shadowOf(manager).getLegacyLocationRequests(LocationManager.NETWORK_PROVIDER).single().minUpdateDistanceMeters, 0f)
            controller.destroy()
        }
        val context = BindingContext()
        val bridge = BackgroundLocationService()
        bridge.onAttachedToEngine(context, Messenger())
        val reply = Reply()
        bridge.onMethodCall(MethodCall("start_location_service", mapOf("accuracy" to "unknown")), reply)
        assertEquals("invalid_argument", reply.error)
        assertEquals(0, context.binds)
        bridge.onDetachedFromEngine()
    }

    private class WakeSource : MotionWakeSource {
        var callback: (() -> Unit)? = null
        var armed = false
        override fun arm(onMovement: () -> Unit): Boolean { callback = onMovement; armed = true; return true }
        override fun cancel() { armed = false }
        fun move() { if (armed) { armed = false; callback?.invoke() } }
    }
    @Test fun adaptiveSharingDowngradesAfterStillnessAndMotionRestoresGpsWithoutStop() {
        enableLocation()
        val manager = RuntimeEnvironment.getApplication().getSystemService(Context.LOCATION_SERVICE) as LocationManager
        shadowOf(manager).setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
        val controller = Robolectric.buildService(LocationUpdatesService::class.java).create()
        val service = controller.get()
        val wake = WakeSource(); service.wakeSource = wake
        val stopped = mutableListOf<String>(); service.onStopped = { stopped.add(it) }
        service.startTracking(TrackingConfig(interval = 15000, adaptive = true), true) { assertNull(it) }
        fun fix() {
            shadowOf(manager).simulateLocation(Location(LocationManager.GPS_PROVIDER).apply {
                latitude = 1.0; longitude = 2.0; accuracy = 5f; speed = 0f
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(); time = System.currentTimeMillis()
            })
            shadowOf(Looper.getMainLooper()).idle()
        }
        fix()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(121))
        fix()
        assertEquals("stationary", service.motion)
        assertTrue(shadowOf(manager).getLocationUpdateListeners(LocationManager.GPS_PROVIDER).isEmpty())
        assertEquals(60000, shadowOf(manager).getLegacyLocationRequests(LocationManager.NETWORK_PROVIDER).single().intervalMillis)
        // Even a missed wake signal must schedule a high-accuracy recovery.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(121))
        assertEquals("moving", service.motion)
        fix()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(121))
        fix()
        assertEquals("stationary", service.motion)
        wake.move()
        assertEquals("moving", service.motion)
        assertEquals(15000, shadowOf(manager).getLegacyLocationRequests(LocationManager.GPS_PROVIDER).single().intervalMillis)
        assertTrue(stopped.isEmpty())
        service.configure(TrackingConfig()) { assertNull(it) }
        assertFalse(wake.armed)
        assertEquals(1000, shadowOf(manager).getLegacyLocationRequests(LocationManager.GPS_PROVIDER).single().intervalMillis)
        assertTrue(stopped.isEmpty())
        controller.destroy()
        assertFalse(wake.armed)
        assertTrue(shadowOf(manager).getLocationUpdateListeners(LocationManager.NETWORK_PROVIDER).isEmpty())
    }
    @Test fun configurationStateProviderSnapshotAndValidationRoundTrip() {
        enableLocation()
        val context = BindingContext(); val bridge = BackgroundLocationService()
        bridge.onAttachedToEngine(context, Messenger())
        val reply = start(bridge)
        val controller = Robolectric.buildService(LocationUpdatesService::class.java).create()
        context.pending!!.onServiceConnected(ComponentName(context, LocationUpdatesService::class.java), controller.get().LocalBinder())
        assertEquals(true, reply.value)
        val update = Reply()
        bridge.onMethodCall(MethodCall("configure", mapOf("interval_ms" to 15000, "adaptive" to true)), update)
        assertEquals(true, update.value)
        val snapshot = Reply(); bridge.onMethodCall(MethodCall("get_state", null), snapshot)
        val state = snapshot.value as Map<*, *>
        assertEquals("running", state["state"])
        assertEquals(15000L, (state["configuration"] as Map<*, *>)["interval_ms"])
        assertEquals(true, (state["provider"] as Map<*, *>)["precise"])
        assertEquals("moving", state["motion"])
        val invalid = Reply(); bridge.onMethodCall(MethodCall("configure", mapOf("interval_ms" to 0)), invalid)
        assertEquals("invalid_argument", invalid.error)
        bridge.onDetachedFromEngine(); controller.destroy()
    }

}
