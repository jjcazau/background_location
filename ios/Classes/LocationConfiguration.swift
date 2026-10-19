import Foundation
import CoreLocation

internal enum LocationConfiguration {
    static func isStopped(_ location: CLLocation?, now: Date = Date()) -> Bool {
        guard let location = location else { return false }
        let age = now.timeIntervalSince(location.timestamp)
        return age >= 0 && age <= 30 && location.horizontalAccuracy.isFinite &&
            location.horizontalAccuracy >= 0 && location.horizontalAccuracy <= 25 &&
            location.speed.isFinite && location.speed >= 0 && location.speed <= 0.5
    }

    static func desiredAccuracy(_ setting: String) -> CLLocationAccuracy {
        switch setting {
        case "balanced": return kCLLocationAccuracyHundredMeters
        case "lowPower": return kCLLocationAccuracyKilometer
        default: return kCLLocationAccuracyBest
        }
    }

    static func metadata(_ location: CLLocation) -> [String: Any] {
        func uncertainty(_ value: Double) -> Any { value.isFinite && value >= 0 ? value as Any : NSNull() }
        var result: [String: Any] = [
            "speed_accuracy": uncertainty(location.speedAccuracy),
            "vertical_accuracy": uncertainty(location.verticalAccuracy),
            "bearing_accuracy": NSNull(), "provider": NSNull()
        ]
        if #available(iOS 13.4, macOS 10.15.4, *) {
            result["bearing_accuracy"] = uncertainty(location.courseAccuracy)
        }
        return result
    }
}
