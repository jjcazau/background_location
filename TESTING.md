# Targeted validation

From this package checkout:

```sh
flutter pub get
flutter test
dart analyze lib test
swiftc ios/Classes/TrackingLifecycle.swift ios/Classes/LocationConfiguration.swift ios/Classes/TrackingConfig.swift ios/Tests/main.swift -o /tmp/location-lifecycle-tests
/tmp/location-lifecycle-tests
```

The Swift harness exercises the actual lifecycle component without CoreLocation
or Flutter. It does not simulate operating-system permission dialogs or background
scheduling. Type-check all `ios/Classes/*.swift` with the installed iOS simulator
SDK and Flutter framework as a separate check.

For Android, use a Flutter host that resolves this checkout as its
background_location dependency. From its `android` directory:

```sh
./gradlew :background_location:testDebugUnitTest
```

This compiles/tests the plugin, not a full Flutter application build. Set the
host's Java home to a compatible JDK if its local Gradle configuration differs.
NativeLifecycleTest uses Robolectric API 28 and an in-memory Flutter messenger.
TrackingSessionTest exercises asynchronous startup tokens without Android.

Device acceptance: initial grant/deny, stop during authorization/startup,
permission revocation, repeated start/stop, Activity recreation, screen-off
recording, GPS fallback, actual Play Services subscription failures, iOS
background/foreground transitions and stop/restart. Test modern Android foreground
restrictions on a device; JVM tests cannot establish those OS guarantees.


For native iOS simulator execution (without a full Flutter app build), compile
the same Swift harness with the simulator SDK and spawn it on a booted simulator:

```sh
xcrun --sdk iphonesimulator swiftc -target arm64-apple-ios17.0-simulator \
  -sdk "$(xcrun --sdk iphonesimulator --show-sdk-path)" \
  ios/Classes/TrackingLifecycle.swift ios/Classes/LocationConfiguration.swift \
  ios/Classes/TrackingConfig.swift ios/Tests/main.swift -o /tmp/location-ios-tests
# Use the intended simulator's UDID from `xcrun simctl list devices`.
xcrun simctl spawn "$LOCATION_TEST_SIMULATOR" /tmp/location-ios-tests
```

NativeLifecycleTest now runs Android API 28 and 35. Motion tests inject the wake
signal while exercising the real service subscriptions, listener replacement,
stationary timing and teardown. They do not test physical sensor delivery.
The Swift harness tests real CoreLocation metadata plus the pure configuration,
fresh stationary evidence, hysteresis, recovery and lifecycle components. Plugin
Swift type-checking separately covers the CoreMotion adapter; simulator execution
does not validate real Motion & Fitness permissions or sensors.

Device matrix before release: both platforms with motion allowed/denied/unavailable,
GPS plus sharing overlap, moving after two minutes stationary with screen locked,
GPS/network disabled, approximate location, force-stop, process death, thermal and
battery-saver modes. Compare baseline versus sharing energy use on the same route
and stationary duration; verify recording geometry and movement-onset delay.
