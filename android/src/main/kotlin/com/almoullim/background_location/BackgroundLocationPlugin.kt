package com.almoullim.background_location

import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding

class BackgroundLocationPlugin : FlutterPlugin, ActivityAware {
    companion object {
        const val TAG = "background_location"
        const val PLUGIN_ID = "com.almoullim.background_location"
    }
    private val service = BackgroundLocationService()
    private var activityBinding: ActivityPluginBinding? = null

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) =
        service.onAttachedToEngine(binding.applicationContext, binding.binaryMessenger)

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        detachActivity()
        service.onDetachedFromEngine()
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activityBinding = binding
        binding.addRequestPermissionsResultListener(service)
        service.setActivity(binding.activity)
    }

    private fun detachActivity() {
        activityBinding?.removeRequestPermissionsResultListener(service)
        activityBinding = null
        service.setActivity(null)
    }

    // Activity replacement does not own the tracking lifetime.
    override fun onDetachedFromActivityForConfigChanges() = detachActivity()
    override fun onDetachedFromActivity() = detachActivity()
    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) = onAttachedToActivity(binding)
}
