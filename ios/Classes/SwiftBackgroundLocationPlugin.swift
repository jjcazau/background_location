import Flutter
import UIKit
import CoreLocation

public class SwiftBackgroundLocationPlugin: NSObject, FlutterPlugin, CLLocationManagerDelegate {
    private var locationManager: CLLocationManager?
    private let channel: FlutterMethodChannel
    private var timeout: DispatchWorkItem?
    private var distance = 0.0
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
    }

    public static func register(with registrar: FlutterPluginRegistrar) {
        let channel = FlutterMethodChannel(name: "com.almoullim.background_location/methods", binaryMessenger: registrar.messenger())
        registrar.addMethodCallDelegate(SwiftBackgroundLocationPlugin(channel: channel), channel: channel)
    }

    public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        switch call.method {
        case "start_location_service":
            let args = call.arguments as? [String: Any]
            let requestedDistance = args?["distance_filter"] as? Double ?? 0
            guard requestedDistance.isFinite && requestedDistance >= 0 else {
                result(FlutterError(code: "invalid_argument", message: "distanceFilter must be finite and non-negative", details: nil))
                return
            }
            if lifecycle.state != "starting" && lifecycle.state != "running" { distance = requestedDistance }
            lifecycle.start(reply: { code in
                if let code = code { result(FlutterError(code: code, message: "Location startup failed: \(code)", details: nil)) }
                else { result(true) }
            }, begin: { [weak self] token in self?.begin(token) })
        case "stop_location_service":
            lifecycle.stop()
            result(true)
        case "is_service_running":
            result(lifecycle.state == "running")
        default:
            result(FlutterMethodNotImplemented)
        }
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
        locationManager?.distanceFilter = distance
        let modes = Bundle.main.object(forInfoDictionaryKey: "UIBackgroundModes") as? [String] ?? []
        locationManager?.allowsBackgroundLocationUpdates = modes.contains("location")
        if #available(iOS 11.0, *) { locationManager?.showsBackgroundLocationIndicator = true }
        locationManager?.pausesLocationUpdatesAutomatically = false
        locationManager?.startUpdatingLocation()
        timeout?.cancel(); timeout = nil
        lifecycle.ready(lifecycle.token)
    }

    private func stopLocationUpdates() {
        timeout?.cancel(); timeout = nil
        locationManager?.stopUpdatingLocation()
        locationManager?.allowsBackgroundLocationUpdates = false
        if #available(iOS 11.0, *) { locationManager?.showsBackgroundLocationIndicator = false }
    }

    public func detachFromEngine(for registrar: FlutterPluginRegistrar) {
        lifecycle.stop()
        locationManager?.delegate = nil
        locationManager = nil
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
        channel.invokeMethod("location", arguments: [
            "speed": latest.speed, "altitude": latest.altitude,
            "latitude": latest.coordinate.latitude, "longitude": latest.coordinate.longitude,
            "accuracy": latest.horizontalAccuracy, "bearing": latest.course,
            "time": latest.timestamp.timeIntervalSince1970 * 1000, "is_mock": false
        ] as [String: Any])
    }
}
