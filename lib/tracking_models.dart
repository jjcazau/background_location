part of 'background_location.dart';

/// One complete native configuration. Configure while running changes only the
/// subscription, retaining foreground service ownership and Dart listeners.
class TrackingConfig {
  const TrackingConfig(
      {this.accuracy = BackgroundLocationAccuracy.high,
      this.interval = const Duration(seconds: 1),
      this.distanceFilter = 0,
      this.adaptive = false});
  static const recording = TrackingConfig();
  static const sharing =
      TrackingConfig(interval: Duration(seconds: 15), adaptive: true);
  final BackgroundLocationAccuracy accuracy;
  final Duration interval;
  final double distanceFilter;
  final bool adaptive;
  void validate() {
    if (interval.inMilliseconds < 1000 ||
        interval.inMilliseconds > 3600000 ||
        !distanceFilter.isFinite ||
        distanceFilter < 0 ||
        distanceFilter > 100000) {
      throw ArgumentError('Invalid tracking configuration');
    }
  }

  Map<String, Object?> toMap() => {
        'accuracy': accuracy.name,
        'interval_ms': interval.inMilliseconds,
        'distance_filter': distanceFilter,
        'adaptive': adaptive
      };
}

class LocationProviderState {
  const LocationProviderState(this.values);
  final Map<String, dynamic> values;
  bool get enabled => values['enabled'] == true;
  bool get precise => values['precise'] == true;
  String get authorization => values['authorization'] as String? ?? 'unknown';
  bool? get gps => values['gps'] as bool?;
  bool? get network => values['network'] as bool?;
  bool get powerSave => values['power_save'] == true;
}

class TrackingState {
  const TrackingState(this.values);
  final Map<String, dynamic> values;
  String get state => values['state'] as String? ?? 'stopped';
  String get motion => values['motion'] as String? ?? 'moving';
  LocationProviderState get provider => LocationProviderState(
      Map<String, dynamic>.from(values['provider'] as Map? ?? {}));
  Map<String, dynamic> get configuration =>
      Map<String, dynamic>.from(values['configuration'] as Map? ?? {});
}

/// Explainable quality gate; preserve the original observation, never smooth it.
class LocationQualityFilter {
  LocationQualityFilter(
      {this.maximumAge = const Duration(seconds: 30),
      this.maximumAccuracy = 150,
      this.maximumSpeed = 100});
  final Duration maximumAge;
  final double maximumAccuracy, maximumSpeed;
  Location? _last;
  void reset() => _last = null;
  String? rejection(Location fix, DateTime now) {
    final lat = fix.latitude,
        lon = fix.longitude,
        time = fix.time,
        accuracy = fix.accuracy;
    String? reason;
    if (lat == null ||
        lon == null ||
        !lat.isFinite ||
        !lon.isFinite ||
        lat.abs() > 90 ||
        lon.abs() > 180) {
      reason = 'invalid_coordinate';
    } else if (time == null || !time.isFinite || time <= 0) {
      reason = 'missing_timestamp';
    } else if (time > now.millisecondsSinceEpoch + 1000 ||
        now.millisecondsSinceEpoch - time > maximumAge.inMilliseconds) {
      reason = 'stale_timestamp';
    } else if (accuracy == null ||
        !accuracy.isFinite ||
        accuracy < 0 ||
        accuracy > maximumAccuracy) {
      reason = 'poor_accuracy';
    } else if (_last != null) {
      final dt = (time - _last!.time!) / 1000;
      if (dt <= 0) {
        reason = 'out_of_order';
      } else {
        final radians = math.pi / 180;
        final a = math.pow(
                math.sin((lat - _last!.latitude!) * radians / 2), 2) +
            math.cos(lat * radians) *
                math.cos(_last!.latitude! * radians) *
                math.pow(math.sin((lon - _last!.longitude!) * radians / 2), 2);
        final distance = 6371000 * 2 * math.asin(math.sqrt(a.clamp(0, 1)));
        if ((distance - accuracy - _last!.accuracy!) / dt > maximumSpeed) {
          reason = 'implied_speed';
        }
      }
    }
    if (reason == null) {
      _last = Location(
          latitude: lat, longitude: lon, time: time, accuracy: accuracy);
    } else {
      BackgroundLocation._record('rejected', {'reason': reason});
    }
    return reason;
  }
}
