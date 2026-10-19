# Targeted validation

From this package checkout:

```sh
flutter pub get
flutter test
dart analyze lib test
swiftc ios/Classes/TrackingLifecycle.swift ios/Tests/main.swift -o /tmp/location-lifecycle-tests
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
