import Flutter
import UIKit
import CoreLocation
import CoreMotion

public class SwiftBackgroundLocationPlugin: NSObject, FlutterPlugin, CLLocationManagerDelegate {
    private var locationManager: CLLocationManager?
    private let channel: FlutterMethodChannel
    private var timeout: DispatchWorkItem?
    private var distance = 0.0
    private var accuracy = "high"
    private var config = TrackingConfig()
    private let motionManager = CMMotionActivityManager()
    private var motionPolicy = MotionPolicy()
    private var motionTimer: DispatchWorkItem?
    private var motionGeneration = 0
    private var motionSaysStill = false
    private var lastMotionLocation: CLLocation?
    private var lastProvider: NSDictionary?
    private var powerObserver: NSObjectProtocol?
    private var foregroundObserver: NSObjectProtocol?

    private lazy var lifecycle = TrackingLifecycle(cleanup: { [weak self] in
        self?.stopLocationUpdates()
    }, emit: { [weak self] state, code in
        var event: [String: Any] = ["state": state]
        if let code = code { event["code"] = code }
        self?.channel.invokeMethod("service_state", arguments: event)
    })

    private init(channel: FlutterMethodChannel) {
        self.channel = channel
        super.init()
        powerObserver = NotificationCenter.default.addObserver(forName: .NSProcessInfoPowerStateDidChange, object: nil, queue: .main) { [weak self] _ in self?.publishProvider() }
        foregroundObserver = NotificationCenter.default.addObserver(forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main) { [weak self] _ in self?.publishProvider() }
    }

    public static func register(with registrar: FlutterPluginRegistrar) {
        let channel = FlutterMethodChannel(name: "com.almoullim.background_location/methods", binaryMessenger: registrar.messenger())
        registrar.addMethodCallDelegate(SwiftBackgroundLocationPlugin(channel: channel), channel: channel)
    }

    public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        switch call.method {
        case "start_location_service":
            let args = call.arguments as? [String: Any]
            guard let parsed = TrackingConfig.parse(args ?? [:]) else {
                result(FlutterError(code: "invalid_argument", message: "Invalid configuration", details: nil)); return
            }
            let requestedDistance = args?["distance_filter"] as? Double ?? 0
            guard requestedDistance.isFinite && requestedDistance >= 0 else {
                result(FlutterError(code: "invalid_argument", message: "distanceFilter must be finite and non-negative", details: nil))
                return
            }
            let requestedAccuracy = args?["accuracy"] as? String ?? "high"
            guard ["high", "balanced", "lowPower"].contains(requestedAccuracy) else {
                result(FlutterError(code: "invalid_argument", message: "Unknown accuracy setting", details: nil))
                return
            }
            if lifecycle.state != "starting" && lifecycle.state != "running" {
                config = parsed
                distance = requestedDistance
                accuracy = requestedAccuracy
            }
            lifecycle.start(reply: { code in
                if let code = code { result(FlutterError(code: code, message: "Location startup failed: \(code)", details: nil)) }
                else { result(true) }
            }, begin: { [weak self] token in self?.begin(token) })
        case "get_state":
            publishProvider()
            result(["state": lifecycle.state, "provider": providerState(),
                "configuration": config.dictionary,
                "effective_configuration": config.effective(stationary: motionPolicy.stationary).dictionary,
                "motion": motionPolicy.stationary ? "stationary" : "moving",
                "motion_available": motionAvailable(), "interval_supported": false])
        case "configure":
            guard let next = TrackingConfig.parse(call.arguments as? [String: Any] ?? [:]) else {
                result(FlutterError(code: "invalid_argument", message: "Invalid configuration", details: nil)); return
            }
            guard lifecycle.state == "running" else {
                result(FlutterError(code: "service_stopped", message: "Tracking must be running", details: nil)); return
            }
            config = next
            resetMotion()
            applyConfiguration()
            startMotion()
            result(true)
        case "stop_location_service":
            lifecycle.stop()
            result(true)
        case "is_service_running":
            result(lifecycle.state == "running")
        default:
            result(FlutterMethodNotImplemented)
        }
    }

    private func providerState() -> [String: Any] {
        let status = CLLocationManager.authorizationStatus()
        let authorization: String
        switch status {
        case .authorizedAlways: authorization = "always"
        case .authorizedWhenInUse: authorization = "whenInUse"
        case .notDetermined: authorization = "notDetermined"
        case .restricted: authorization = "restricted"
        default: authorization = "denied"
        }
        var precise = true
        if #available(iOS 14.0, *) { precise = locationManager?.accuracyAuthorization == .fullAccuracy }
        return ["enabled": CLLocationManager.locationServicesEnabled(), "authorization": authorization,
            "precise": precise, "power_save": ProcessInfo.processInfo.isLowPowerModeEnabled]
    }
    private func publishProvider() {
        let value = providerState()
        if lastProvider != value as NSDictionary {
            lastProvider = value as NSDictionary
            channel.invokeMethod("provider_state", arguments: value)
        }
    }
    private func motionAvailable() -> Bool {
        guard #available(iOS 11.0, *) else { return false }
        let status = CMMotionActivityManager.authorizationStatus()
        return CMMotionActivityManager.isActivityAvailable() && status != .denied && status != .restricted &&
            Bundle.main.object(forInfoDictionaryKey: "NSMotionUsageDescription") != nil
    }
    private func applyConfiguration() {
        let effective = config.effective(stationary: motionPolicy.stationary)
        locationManager?.desiredAccuracy = LocationConfiguration.desiredAccuracy(effective.accuracy)
        locationManager?.distanceFilter = effective.distance
        channel.invokeMethod("diagnostic", arguments: ["event": "configuration", "backend": "core_location",
            "accuracy": effective.accuracy, "distance_filter": effective.distance,
            "motion": motionPolicy.stationary ? "stationary" : "moving", "motion_available": motionAvailable()])
    }
    private func resetMotion() {
        motionGeneration += 1
        motionTimer?.cancel(); motionTimer = nil
        motionManager.stopActivityUpdates()
        motionPolicy = MotionPolicy()
        motionSaysStill = false; lastMotionLocation = nil
    }
    private func startMotion() {
        guard config.adaptive, lifecycle.state == "running", motionAvailable() else { return }
        let generation = motionGeneration
        motionManager.startActivityUpdates(to: .main) { [weak self] activity in
            guard let self = self, self.motionGeneration == generation, self.lifecycle.state == "running",
                  let activity = activity else { return }
            let still = activity.stationary && !activity.walking && !activity.running && !activity.cycling && !activity.automotive && activity.confidence == .high
            // Uncertain activity never initiates a downgrade; it restores moving.
            self.motionSaysStill = still
            self.updateMotion(still, generation: generation)
        }
    }
    private func updateMotion(_ requestedStill: Bool, generation: Int) {
        let still = requestedStill && (motionPolicy.stationary || LocationConfiguration.isStopped(lastMotionLocation))
        if motionPolicy.update(still: still, now: ProcessInfo.processInfo.systemUptime) {
            applyConfiguration()
            motionTimer?.cancel(); motionTimer = nil
        }
        guard still else { motionTimer?.cancel(); motionTimer = nil; return }
        // Repeated fixes must not postpone the already scheduled recovery.
        guard motionTimer == nil else { return }
        let work = DispatchWorkItem { [weak self] in
            guard let self = self, self.motionGeneration == generation, self.lifecycle.state == "running" else { return }
            self.motionTimer = nil
            if !self.motionAvailable() { self.updateMotion(false, generation: generation); return }
            // Recheck after two minutes; never leave a stale stationary signal
            // latched indefinitely if motion delivery is interrupted.
            self.updateMotion(!self.motionPolicy.stationary, generation: generation)
        }
        motionTimer = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 120, execute: work)
    }

    private func begin(_ token: Int) {
        let timeout = DispatchWorkItem { [weak self] in self?.lifecycle.fail(token, code: "startup_timeout") }
        self.timeout = timeout
        DispatchQueue.main.asyncAfter(deadline: .now() + 30, execute: timeout)
        if locationManager == nil {
            locationManager = CLLocationManager()
            locationManager?.delegate = self
        }
        let status = CLLocationManager.authorizationStatus()
        if status == .notDetermined { locationManager?.requestAlwaysAuthorization() }
        else { authorizationChanged(status) }
    }

    private func authorizationChanged(_ status: CLAuthorizationStatus) {
        publishProvider()
        guard lifecycle.state == "starting" || lifecycle.state == "running" else { return }
        if status == .denied || status == .restricted {
            lifecycle.fail(lifecycle.token, code: "permission_denied")
            return
        }
        guard lifecycle.state == "starting", status == .authorizedAlways || status == .authorizedWhenInUse else { return }
        guard CLLocationManager.locationServicesEnabled() else {
            lifecycle.fail(lifecycle.token, code: "location_services_disabled")
            return
        }
        applyConfiguration()
        let modes = Bundle.main.object(forInfoDictionaryKey: "UIBackgroundModes") as? [String] ?? []
        locationManager?.allowsBackgroundLocationUpdates = modes.contains("location")
        if #available(iOS 11.0, *) { locationManager?.showsBackgroundLocationIndicator = true }
        locationManager?.pausesLocationUpdatesAutomatically = false
        locationManager?.startUpdatingLocation()
        timeout?.cancel(); timeout = nil
        lifecycle.ready(lifecycle.token)
        startMotion()
    }

    private func stopLocationUpdates() {
        resetMotion()
        timeout?.cancel(); timeout = nil
        locationManager?.stopUpdatingLocation()
        locationManager?.allowsBackgroundLocationUpdates = false
        if #available(iOS 11.0, *) { locationManager?.showsBackgroundLocationIndicator = false }
    }

    public func detachFromEngine(for registrar: FlutterPluginRegistrar) {
        lifecycle.stop()
        locationManager?.delegate = nil
        locationManager = nil
        if let observer = powerObserver { NotificationCenter.default.removeObserver(observer) }; powerObserver = nil
        if let observer = foregroundObserver { NotificationCenter.default.removeObserver(observer) }; foregroundObserver = nil
    }

    public func locationManager(_ manager: CLLocationManager, didChangeAuthorization status: CLAuthorizationStatus) {
        authorizationChanged(status)
    }

    public func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        let nativeError = error as NSError
        // A transient missing GPS fix is not a subscription failure.
        if nativeError.domain == kCLErrorDomain && nativeError.code == CLError.locationUnknown.rawValue { return }
        let code = nativeError.domain == kCLErrorDomain && nativeError.code == CLError.denied.rawValue ? "permission_denied" : "location_failed"
        lifecycle.fail(lifecycle.token, code: code)
    }

    public func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard lifecycle.state == "running", let latest = locations.last else { return }
        lastMotionLocation = latest
        if config.adaptive && motionAvailable() {
            if latest.speed > 0.5 && latest.horizontalAccuracy >= 0 && latest.horizontalAccuracy <= 25 {
                updateMotion(false, generation: motionGeneration)
            } else if motionSaysStill { updateMotion(true, generation: motionGeneration) }
        }
        let metadata = LocationConfiguration.metadata(latest)
        let payload: [String: Any] = [
            "speed": latest.speed, "altitude": latest.altitude,
            "latitude": latest.coordinate.latitude, "longitude": latest.coordinate.longitude,
            "accuracy": latest.horizontalAccuracy, "bearing": latest.course,
            "time": latest.timestamp.timeIntervalSince1970 * 1000, "is_mock": false
        ]
        channel.invokeMethod("location", arguments: payload.merging(metadata) { _, value in value })
    }
}
