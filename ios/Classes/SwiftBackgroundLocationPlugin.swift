import Flutter
import UIKit
import CoreLocation

// Locally adapted from upstream manager reuse and simplylizz's payload cleanup.
public class SwiftBackgroundLocationPlugin: NSObject, FlutterPlugin, CLLocationManagerDelegate {
    private var locationManager: CLLocationManager?
    private let channel: FlutterMethodChannel

    private init(channel: FlutterMethodChannel) {
        self.channel = channel
        super.init()
    }

    public static func register(with registrar: FlutterPluginRegistrar) {
        let channel = FlutterMethodChannel(name: "com.almoullim.background_location/methods", binaryMessenger: registrar.messenger())
        let instance = SwiftBackgroundLocationPlugin(channel: channel)
        registrar.addMethodCallDelegate(instance, channel: channel)
    }

    public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        switch call.method {
        case "start_location_service":
            if locationManager == nil {
                locationManager = CLLocationManager()
                locationManager?.delegate = self
            }
            let args = call.arguments as? [String: Any]
            let distanceFilter = args?["distance_filter"] as? Double ?? 0
            locationManager?.distanceFilter = distanceFilter
            locationManager?.requestAlwaysAuthorization()
            locationManager?.allowsBackgroundLocationUpdates = true
            if #available(iOS 11.0, *) {
                locationManager?.showsBackgroundLocationIndicator = true
            }
            locationManager?.pausesLocationUpdatesAutomatically = false
            locationManager?.startUpdatingLocation()
            result(true)
        case "stop_location_service":
            stopLocationUpdates()
            result(true)
        default:
            result(FlutterMethodNotImplemented)
        }
    }

    private func stopLocationUpdates() {
        // Stop the same manager that was started; stopping must not request permission.
        locationManager?.stopUpdatingLocation()
        locationManager?.allowsBackgroundLocationUpdates = false
        if #available(iOS 11.0, *) {
            locationManager?.showsBackgroundLocationIndicator = false
        }
    }

    public func detachFromEngine(for registrar: FlutterPluginRegistrar) {
        stopLocationUpdates()
        locationManager?.delegate = nil
        locationManager = nil
    }

    public func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let latest = locations.last else { return }
        let location: [String: Any] = [
            "speed": latest.speed,
            "altitude": latest.altitude,
            "latitude": latest.coordinate.latitude,
            "longitude": latest.coordinate.longitude,
            "accuracy": latest.horizontalAccuracy,
            "bearing": latest.course,
            "time": latest.timestamp.timeIntervalSince1970 * 1000,
            "is_mock": false
        ]
        channel.invokeMethod("location", arguments: location)
    }
}
