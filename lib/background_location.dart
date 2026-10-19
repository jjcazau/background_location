import 'dart:async';
import 'dart:io' show Platform;
import 'dart:math' as math;
import 'package:flutter/services.dart';

part 'tracking_models.dart';

/// Native tracking state. Running means subscribed, not that a GPS fix exists.
class LocationServiceState {
  const LocationServiceState(this.state, {this.code});
  final String state;
  final String? code;
  bool get isRunning => state == 'running';
  bool get isTerminal => state == 'stopped' || state == 'error';
}

/// Requested power/accuracy trade-off; actual accuracy depends on the OS.
/// Android's non-Google fallback uses network only for balanced/lowPower and
/// GPS plus network for high. Pass a TrackingConfig to configure for live changes.
enum BackgroundLocationAccuracy { high, balanced, lowPower }

class BackgroundLocation {
  static const _channel =
      MethodChannel('com.almoullim.background_location/methods');
  static final _locations = StreamController<Location>.broadcast();
  static final _states = StreamController<LocationServiceState>.broadcast();
  static StreamSubscription<Location>? _legacySubscription;
  static final _providers = StreamController<LocationProviderState>.broadcast();
  static final _diagnostics = <Map<String, Object?>>[];
  static Location? _lastLocation;
  static void _record(String event, Map<String, Object?> values) {
    _diagnostics.add({
      'time': DateTime.now().toUtc().toIso8601String(),
      'event': event,
      ...values
    });
    if (_diagnostics.length > 200) _diagnostics.removeAt(0);
  }

  static List<Map<String, Object?>> get diagnostics => List.unmodifiable(
      _diagnostics.map((e) => Map<String, Object?>.unmodifiable(e)));
  static void clearDiagnostics() => _diagnostics.clear();
  static Stream<LocationProviderState> get providerStateStream {
    _listen();
    return _providers.stream;
  }

  static Future<TrackingState> getState() async {
    _listen();
    return TrackingState(Map<String, dynamic>.from(
        await _channel.invokeMapMethod('get_state') ?? {}));
  }

  static Future<void> configure(TrackingConfig config) async {
    config.validate();
    await _channel.invokeMethod('configure', config.toMap());
    _record('configuration', config.toMap());
  }

  // Reinstalling the same dispatcher is safe and also supports test/engine reset.
  static void _listen() => _channel.setMethodCallHandler(_handle);

  static Future<void> _handle(MethodCall call) async {
    final data = call.arguments;
    if (data is! Map) return;
    if (call.method == 'provider_state') {
      _providers.add(LocationProviderState(Map<String, dynamic>.from(data)));
      _record('provider', Map<String, Object?>.from(data));
    } else if (call.method == 'diagnostic') {
      _record('native', Map<String, Object?>.from(data));
    } else if (call.method == 'service_state' && data['state'] is String) {
      _record('service', Map<String, Object?>.from(data));
      if (data['state'] == 'starting' ||
          data['state'] == 'stopped' ||
          data['state'] == 'error') {
        _lastLocation = null;
      }
      _states.add(LocationServiceState(data['state'] as String,
          code: data['code'] as String?));
    } else if (call.method == 'location') {
      double? number(String key) => (data[key] as num?)?.toDouble();
      final location = Location(
          latitude: number('latitude'),
          longitude: number('longitude'),
          altitude: number('altitude'),
          accuracy: number('accuracy'),
          bearing: number('bearing'),
          speed: number('speed'),
          time: number('time'),
          speedAccuracy: number('speed_accuracy'),
          verticalAccuracy: number('vertical_accuracy'),
          bearingAccuracy: number('bearing_accuracy'),
          provider: data['provider'] as String?,
          isMock: data['is_mock'] as bool?);
      _lastLocation = location;
      _locations.add(location);
    }
  }

  /// Independent subscriptions share one native callback handler.
  static Stream<Location> get locationStream {
    _listen();
    return _locations.stream;
  }

  static Stream<LocationServiceState> get serviceStateStream {
    _listen();
    return _states.stream;
  }

  static Future<dynamic> startLocationService(
      {double distanceFilter = 0,
      bool forceAndroidLocationManager = false,
      BackgroundLocationAccuracy accuracy = BackgroundLocationAccuracy.high,
      TrackingConfig? config}) {
    config?.validate();
    _listen();
    return _channel.invokeMethod('start_location_service', {
      'distance_filter': distanceFilter,
      'force_location_manager': forceAndroidLocationManager,
      'accuracy': accuracy.name,
      if (config != null) ...config.toMap(),
    });
  }

  static Future<dynamic> stopLocationService() {
    _listen();
    return _channel.invokeMethod('stop_location_service');
  }

  static Future<bool> isServiceRunning() async =>
      await _channel.invokeMethod<bool>('is_service_running') ?? false;

  static Future<dynamic> setAndroidNotification(
      {String? title, String? message, String? icon}) async {
    if (Platform.isAndroid) {
      return _channel.invokeMethod('set_android_notification', {
        'title': title,
        'message': message,
        'icon': icon,
      });
    }
  }

  /// Applies to the next native subscription; does not restart active tracking.
  static Future<dynamic> setAndroidConfiguration(int interval) async {
    if (interval <= 0) {
      throw ArgumentError.value(interval, 'interval', 'Must be positive');
    }
    if (Platform.isAndroid) {
      return _channel
          .invokeMethod('set_configuration', {'interval': interval.toString()});
    }
  }

  /// Wait for a fresh fix while tracking, without replacing other listeners.
  /// A timeout or native stop/failure cancels both temporary subscriptions.
  Future<Location> getCurrentLocation(
      {Duration timeout = const Duration(seconds: 30)}) async {
    final result = Completer<Location>();
    final locations = locationStream.listen((location) {
      if (!result.isCompleted) result.complete(location);
    });
    final states = serviceStateStream.listen((state) {
      if (state.isTerminal && !result.isCompleted) {
        result.completeError(
            PlatformException(code: state.code ?? 'service_stopped'));
      }
    });
    try {
      return await result.future.timeout(timeout);
    } finally {
      await locations.cancel();
      await states.cancel();
    }
  }

  /// Samples the active service without changing its power profile or listeners.
  /// Returns the best valid fix on sample limit/timeout even if the target is
  /// unmet; inspect accuracy. Throws on timeout with no valid fix. Does not start
  /// tracking, persist data, or substitute receipt time for acquisition time.
  Future<Location> getCurrentPosition({
    Duration timeout = const Duration(seconds: 20),
    Duration maximumAge = const Duration(seconds: 10),
    double desiredAccuracy = 30,
    int samples = 3,
  }) async {
    if (timeout <= Duration.zero ||
        maximumAge < Duration.zero ||
        samples < 1 ||
        !desiredAccuracy.isFinite ||
        desiredAccuracy <= 0) {
      throw ArgumentError('Invalid acquisition options');
    }
    final result = Completer<Location>();
    Location? best;
    bool fresh(Location fix) {
      final age = DateTime.now().millisecondsSinceEpoch - fix.time!;
      return age >= -1000 && age <= maximumAge.inMilliseconds;
    }

    var count = 0;
    void consider(Location location) {
      final t = location.time, a = location.accuracy;
      if (result.isCompleted ||
          t == null ||
          !t.isFinite ||
          a == null ||
          !a.isFinite ||
          a < 0) {
        return;
      }
      final age = DateTime.now().millisecondsSinceEpoch - t;
      if (age < -1000 || age > maximumAge.inMilliseconds) return;
      if (location.latitude == null ||
          location.longitude == null ||
          !location.latitude!.isFinite ||
          !location.longitude!.isFinite ||
          location.latitude!.abs() > 90 ||
          location.longitude!.abs() > 180) {
        return;
      }
      count++;
      if (best == null || !fresh(best!) || a < best!.accuracy!) best = location;
      if (a <= desiredAccuracy || count >= samples) result.complete(best);
    }

    final sub = locationStream.listen(consider);
    final states = serviceStateStream.listen((s) {
      if (s.isTerminal && !result.isCompleted) {
        result.completeError(
            PlatformException(code: s.code ?? 'service_stopped'));
      }
    });
    if (_lastLocation != null) consider(_lastLocation!);
    final timer = Timer(timeout, () {
      if (!result.isCompleted) {
        if (best != null && fresh(best!)) {
          result.complete(best);
        } else {
          result.completeError(TimeoutException('No fresh location', timeout));
        }
      }
    });
    try {
      return await result.future;
    } finally {
      timer.cancel();
      await sub.cancel();
      await states.cancel();
    }
  }

  /// Compatibility callback API. Replaces only the previous legacy callback.
  /// Prefer locationStream.listen for explicitly cancellable subscriptions.
  static void getLocationUpdates(void Function(Location) callback) {
    _legacySubscription?.cancel();
    _legacySubscription = locationStream.listen(callback);
  }

  static Future<void> removeLocationUpdates() async {
    await _legacySubscription?.cancel();
    _legacySubscription = null;
  }
}

/// about the user current location
class Location {
  double? latitude;
  double? longitude;
  double? altitude;
  double? bearing;
  double? accuracy;
  double? speed;
  double? time;

  /// Unavailable native measurements are null. Accuracy values are uncertainty:
  /// m/s for speed, metres for altitude, degrees for bearing.
  double? speedAccuracy;
  double? verticalAccuracy;
  double? bearingAccuracy;

  /// Android provider name; null on iOS (CoreLocation does not expose it).
  String? provider;
  bool? isMock;

  Location(
      {this.longitude,
      this.latitude,
      this.altitude,
      this.accuracy,
      this.bearing,
      this.speed,
      this.time,
      this.speedAccuracy,
      this.verticalAccuracy,
      this.bearingAccuracy,
      this.provider,
      this.isMock});

  Map<String, dynamic> toMap() {
    var obj = {
      'latitude': latitude,
      'longitude': longitude,
      'altitude': altitude,
      'bearing': bearing,
      'accuracy': accuracy,
      'speed': speed,
      'time': time,
      'speed_accuracy': speedAccuracy,
      'vertical_accuracy': verticalAccuracy,
      'bearing_accuracy': bearingAccuracy,
      'provider': provider,
      'is_mock': isMock
    };
    return obj;
  }
}
