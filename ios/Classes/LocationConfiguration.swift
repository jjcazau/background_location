import Foundation
import CoreLocation

internal enum LocationConfiguration {
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
