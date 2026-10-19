import 'dart:async';
import 'dart:io' show Platform;

import 'package:flutter/services.dart';

/// BackgroundLocation plugin to get background
/// lcoation updates in iOS and Android
class BackgroundLocation {
  // The channel to be used for communication.
  // This channel is also refrenced inside both iOS and Abdroid classes
  static const MethodChannel _channel =
      MethodChannel('com.almoullim.background_location/methods');

  /// Stop receiving location updates
  static Future<dynamic> stopLocationService() async {
    return await _channel.invokeMethod('stop_location_service');
  }

  /// Start receiving location updated
  static Future<dynamic> startLocationService(
      {double distanceFilter = 0.0,
      bool forceAndroidLocationManager = false}) async {
    return await _channel
        .invokeMethod('start_location_service', <String, dynamic>{
      'distance_filter': distanceFilter,
      'force_location_manager': forceAndroidLocationManager
    });
  }

  static Future<dynamic> setAndroidNotification(
      {String? title, String? message, String? icon}) async {
    if (Platform.isAndroid) {
      return await _channel.invokeMethod('set_android_notification',
          <String, dynamic>{'title': title, 'message': message, 'icon': icon});
    } else {
      //return Promise.resolve();
    }
  }

  static Future<dynamic> setAndroidConfiguration(int interval) async {
    if (Platform.isAndroid) {
      return await _channel.invokeMethod('set_configuration', <String, dynamic>{
        'interval': interval.toString(),
      });
    } else {
      //return Promise.resolve();
    }
  }

  /// Get the current location once.
  Future<Location> getCurrentLocation() async {
    var completer = Completer<Location>();

    var _location = Location();
    getLocationUpdates((location) {
      if (completer.isCompleted) return;
      _location.latitude = location.latitude;
      _location.longitude = location.longitude;
      _location.accuracy = location.accuracy;
      _location.altitude = location.altitude;
      _location.bearing = location.bearing;
      _location.speed = location.speed;
      _location.time = location.time;
      _location.isMock = location.isMock;
      completer.complete(_location);
    });

    return completer.future;
  }

  /// Register a function to recive location updates as long as the location
  /// service has started
  static void getLocationUpdates(Function(Location) location) {
    // add a handler on the channel to recive updates from the native classes
    _channel.setMethodCallHandler((MethodCall methodCall) async {
      if (methodCall.method == 'location' && methodCall.arguments is Map) {
        var locationData = Map.from(methodCall.arguments);
        // Call the user passed function
        location(
          Location(
              latitude: (locationData['latitude'] as num?)?.toDouble(),
              longitude: (locationData['longitude'] as num?)?.toDouble(),
              altitude: (locationData['altitude'] as num?)?.toDouble(),
              accuracy: (locationData['accuracy'] as num?)?.toDouble(),
              bearing: (locationData['bearing'] as num?)?.toDouble(),
              speed: (locationData['speed'] as num?)?.toDouble(),
              time: (locationData['time'] as num?)?.toDouble(),
              isMock: locationData['is_mock']),
        );
      }
    });
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
  bool? isMock;

  Location(
      {this.longitude,
      this.latitude,
      this.altitude,
      this.accuracy,
      this.bearing,
      this.speed,
      this.time,
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
      'is_mock': isMock
    };
    return obj;
  }
}
