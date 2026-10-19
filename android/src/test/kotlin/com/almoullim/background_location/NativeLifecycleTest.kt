package com.almoullim.background_location

import android.Manifest
import android.app.Activity
import android.content.*
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
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
@Config(sdk = [28])
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
        shadowOf(manager).simulateLocation(Location(LocationManager.GPS_PROVIDER).apply { latitude = 1.0; longitude = 2.0 })
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
}
