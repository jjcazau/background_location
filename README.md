# Ember Map maintained background_location

Version `0.9.6+embermap.1` is maintained in `jjcazau/background_location` for
Ember Map. See [PATCHES.md](PATCHES.md) for source attribution, scope, validation,
and remaining work. Use an exact Git commit in consuming applications.

This release preserves the existing public API and method-channel name. It does
not add reboot recovery, headless Dart execution, or native location storage.
Native startup requests location permission from the attached Activity and
completes after permission and subscription succeed. Android foreground-start
restrictions still apply when starting from the background. Native platform changes require a full app rebuild by the
consumer; hot reload cannot load them.


## Shared streams and failures

```dart
final locations = BackgroundLocation.locationStream.listen(onLocation);
final states = BackgroundLocation.serviceStateStream.listen((state) {
  // Reconcile UI/recording state when state.isTerminal is true.
  // state.code identifies failures such as permission_denied.
});
await BackgroundLocation.startLocationService();
final fix = await BackgroundLocation().getCurrentLocation(
  timeout: const Duration(seconds: 20),
); // Does not replace the ongoing stream.
await BackgroundLocation.stopLocationService();
await locations.cancel();
await states.cancel();
```

`isServiceRunning()` queries subscription state; it does not promise a fresh fix.
An explicit stop completes pending starts with a `cancelled` PlatformException.
State events are broadcast to current listeners, not replayed to late subscribers.
The legacy `getLocationUpdates` callback remains supported; use
`removeLocationUpdates()` to cancel that callback without stopping the service.

The historical upstream usage documentation follows.

# Background Location

A Flutter plugin to get location updates in the background for both Android and iOS (Requires iOS 10.0+). Uses `CoreLocation` for iOS and `FusedLocationProvider` for Android

## Getting Started

**1:** Add this to your package's pubspec.yaml file:

```yaml
dependencies:
  background_location: ^0.9.0
```

**2:** Install packages from the command line:

```bash
$ flutter packages get
```

Alternatively, your editor might support flutter packages get. Check the docs for your editor to learn more.

## How to use

Import the package where you wanna use it.

```dart
import 'package:background_location/background_location.dart';
```

Request permissions from the user. You can use [permission_handler](https://pub.dev/packages/permission_handler) for this

Set the notification title, message and icon **(Android only)**. Use `await` or `.then` if you wanna start the location service immediatly after becuase its an asynchronous method

```dart
BackgroundLocation.setAndroidNotification(
	title: "Notification title",
        message: "Notification message",
        icon: "@mipmap/ic_launcher",
);
```

Set the interval between localisations in milliseconds **(Android only)**. Use `await` or `.then` if you wanna start the location service immediatly after becuase its an asynchronous method

```dart
BackgroundLocation.setAndroidConfiguration(1000);
```

Start the location service. This will also ask the user for permission if not asked previously by another package.

```dart
BackgroundLocation.startLocationService();
```

Start location service by specifying `distanceFilter`. Defaults to `0` if not specified

```dart
BackgroundLocation.startLocationService(distanceFilter : 10);
```

You can also force the use of Android `LocationManager` instead of Google's `FusedLocationProvider` by setting the `forceAndroidLocationManager` property to `true`. If not specified, this defaults to `false`, which uses `FusedLocationProvider` if it is available, treating `LocationManager` as a fallback. This setting has no effect on iOS devices.

```dart
BackgroundLocation.startLocationService(forceAndroidLocationManager: true);
```

`getLocationUpdates` will trigger everytime the location updates on the device. Provide a callback function to `getLocationUpdates` to handle location update.

```dart
BackgroundLocation.getLocationUpdates((location) {
  print(location);
});
```

location is a `Class` exposing the following properties.

```dart
double latitude;
double longitude;
double altitude;
double bearing;
double accuracy;
double speed;
double time;
bool isMock;
```

To stop listening to location changes you can execute.

```dart
BackgroundLocation.stopLocationService();
```

Make sure to delcare all required permissions for both your android and ios app

info.plist
```xml
<key>NSLocationAlwaysAndWhenInUseUsageDescription</key>
<string>This app needs access to location.</string>
<key>NSLocationAlwaysUsageDescription</key>
<string>This app needs access to location.</string>
<key>NSLocationWhenInUseUsageDescription</key>
<string>This app needs access to location.</string>
<key>UIBackgroundModes</key>
<array>
	<string>fetch</string>
	<string>location</string>
</array>
```

AndroidManifest.xml
```xml
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION"/> 
```
<!-- TODO: Fix example -->
<!-- ## Example -->
<!-- **[Complete working application Example](https://github.com/almoullim/background_location/tree/master/example)** -->


### Accuracy settings and optional metadata (0.9.7+embermap.1)

`startLocationService(accuracy: BackgroundLocationAccuracy.high)` defaults to
high accuracy. `balanced` and `lowPower` request the corresponding Android
fused priority; on iOS they request 100 metre and 1 kilometre accuracy. These
are requests, not guarantees. Changes apply after stop/start; starting an
already active service keeps its current configuration.

On the Android LocationManager fallback, high uses GPS plus network with fine
permission, or network alone with coarse permission. Balanced/lowPower use only
network, and fail with `location_services_disabled` if it is unavailable. Disabled
providers are registered when present so they can recover during a session.
Disabling one provider does not stop tracking while another selected provider
remains enabled. No last-known location is injected. Fixes older than 30 seconds,
future/invalid fixes, and non-increasing monotonic timestamps are rejected.
A poorer fix from another provider waits until the previous accepted fix is at
least 10 seconds old; same-provider movement continues normally.

`Location` exposes nullable `speedAccuracy` (m/s), `verticalAccuracy` (metres),
`bearingAccuracy` (degrees), and `provider`. Android accuracy metadata requires
API 26 and a present measurement. CoreLocation does not expose the provider;
iOS bearing accuracy requires iOS 13.4. Missing/invalid uncertainty is null,
not a claim of zero uncertainty. Existing fields and listeners are unchanged.


### Live configuration and adaptive sharing (0.10.0+embermap.1)

```dart
await BackgroundLocation.startLocationService(config: TrackingConfig.sharing);
// A simultaneous route recorder takes precedence without stopping the service:
await BackgroundLocation.configure(TrackingConfig.recording);
final state = await BackgroundLocation.getState();
final fix = await BackgroundLocation().getCurrentPosition(
  maximumAge: const Duration(seconds: 10), desiredAccuracy: 50, samples: 3);
```

`TrackingConfig.recording` requests high accuracy, 1-second Android intervals,
zero distance filter and no adaptation. `sharing` requests high accuracy with
15-second Android intervals and native adaptation. Intervals are requests, not
OS scheduling guarantees; iOS has no equivalent interval setting. Live configure
requires a running service. Android installs the replacement subscription before
removing the old one; failed replacement ends the session with an error rather
than falsely reporting the new configuration. Stop cancels pending replacements.
Legacy start arguments and next-start Android configuration remain supported.

Adaptive operation:

- Android requires a successfully armed significant-motion trigger. Two minutes
  of fresh, accurate (<=25m), low-speed (<=0.5m/s) fixes within 15m enable a
  balanced-accuracy, 60-second/25m stationary request. GPS-only fallback hardware
  without an enabled network provider retains GPS. Motion restores the moving
  request; the one-shot trigger is rearmed. Unavailable sensors retain moving
  settings. No accelerometer polling or wake locks are added.
- iOS requires CoreMotion support and `NSMotionUsageDescription` in the host.
  The OS can request Motion & Fitness permission when adaptive sharing starts.
  High-confidence stationary activity and recent accurate low-speed location
  evidence must agree for two minutes before requesting 100m accuracy/25m
  displacement. Motion or a reliable moving fix restores the original request.
  Missing/denied motion access retains moving settings. iOS <11 does not adapt.
- Both schedule a return to the moving profile after at most two minutes of
  stationary mode, then require stationary evidence again. This is a recovery
  check, not a realtime OS guarantee: suspended scheduling can delay timers.
  Neither platform stops location services or enables automatic location pauses.
  A lowPower request is never upgraded to balanced by adaptation.
- `configure(TrackingConfig.recording)` cancels motion tracking and all adaptive
  timers. The app must choose the strongest profile required by active consumers.

`getState()` returns lifecycle, requested/effective configuration, provider
capabilities and motion state. `providerStateStream` reports capability changes
separately from service lifecycle. Android checks availability during its existing
health checks; iOS also observes permission, power-state and foreground changes.
`BackgroundLocation.diagnostics` is a bounded 200-entry in-memory log of state,
configuration and rejection reasons. It contains no coordinates or credentials;
`clearDiagnostics()` clears it. It is not a durable log across process death.

`LocationQualityFilter` validates capture time (30-second default maximum age,
1-second future tolerance), horizontal accuracy, coordinates, ordering and implied
speed after subtracting measurement uncertainty. It does not smooth coordinates.
Callers choose accuracy/speed limits and call `reset()` between sessions. The raw
package stream remains compatible; consumers explicitly apply this quality gate.

`getCurrentPosition` samples the existing subscription and never starts a second
location client or changes its power settings. It uses only fixes within the
requested maximum age, returns early on the accuracy target, otherwise returns
the best still-fresh fix on sample limit/timeout. Inspect returned accuracy to
know whether the target was met. Timeout with no fresh fix throws; native stop
cancels the request. Keep the legacy `getCurrentLocation` for next-event semantics.

This release does not add reboot/headless continuation, geofencing, a native
persistence queue or HTTP uploads. Real-device screen-off motion recovery and
battery measurements remain required; unit tests do not establish battery savings.
