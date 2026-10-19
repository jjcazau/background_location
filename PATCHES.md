# Ember Map maintained fork

The `codex/ember-map-location-fixes` branch retains jjcazau repository history.
Native and Dart sources were first aligned with Ember Map's previously locked
AlexanderHorielko/background_location commit
`9db60e0ee6690dce9c9377f2e0c39a09b484893f`.
This preserves its namespace, Play Services 21.0.1, removed legacy Registrar,
and private stop receiver fixes. Original Apache-2.0 attribution is retained.

Local modifications and adapted patches are recorded below as they are applied.

## 0.9.5+embermap.1

Modified in this fork, 2026-10-19:

- Reuse an instance-owned iOS CLLocationManager and stop the actual active
  manager; no manager creation or permission request on stop. Adapted from
  [BWMuller/upstream cc5e68b](https://github.com/Almoullim/background_location/commit/cc5e68b).
- Remove string events from the location channel, adapted from
  [simplylizz a0964b2](https://github.com/simplylizz/background_location/commit/a0964b2).
  Unknown methods return FlutterMethodNotImplemented; empty location batches
  are ignored; engine detachment stops and releases the manager.
- Pair iOS background flags with start/stop, adapting
  [maks-epowar 283cfbe](https://github.com/maks-epowar/background_location/commit/283cfbe).
  Retain the visible background indicator and existing authorization policy.
- Keep the Android service private, based on
  [upstream b258772](https://github.com/Almoullim/background_location/commit/b258772).
- Ignore absent fused-provider locations, based on
  [cadivus/upstream 33d85b5](https://github.com/Almoullim/background_location/commit/33d85b5).
- Declare FOREGROUND_SERVICE_LOCATION and pass the location foreground type on
  Android Q+, adapting [fe07f7b](https://github.com/Almoullim/background_location/commit/fe07f7b)
  and [63f6098](https://github.com/Almoullim/background_location/commit/63f6098).
- Keep fastest interval consistent with a positive configured interval, based
  on [3e7bf62](https://github.com/Almoullim/background_location/commit/3e7bf62)
  and [9f3f9cd](https://github.com/Almoullim/background_location/commit/9f3f9cd).
  Configuration still applies to the next request, not dynamically to an active one.
- Guard null teardown context/receiver and clear the service reference, adapting
  [Elmurodjon01 ad3dc1a](https://github.com/Elmurodjon01/background_location/commit/ad3dc1a).
  Register the local receiver once, unregister on stop, and clean up before
  clearing engine context. Catch only an already-unbound service operation.
- Resolve notification icons through the requested resource type, alternate
  drawable/mipmap, app icon and system fallback. Inspired by
  [mathAndCats b0cbb50](https://github.com/mathAndCats/background_location/commit/b0cbb50);
  no database, recording state, sticky restart or persistence changes imported.
- Dart decoder ignores non-map events and normalizes numeric fields to doubles.
  One-shot location ignores later fixes after completion and preserves isMock.
  Remove legacy @required annotations on nullable fields and add return types.
- Declare AndroidX dependencies used directly; compile against SDK 36 as Ember
  Map does. Allow Dart 3. Preserve existing native dependency versions otherwise.

## Recommended next fixes (not implemented in this release)

1. Native startup state machine: wait for binding, permission and subscription
   success; cancel in-flight starts; report denial and failure to Dart. Current
   start returns before these finish, and foreground promotion requires the
   consumer to have obtained location permission first.
2. Separate Activity recreation from tracking lifetime; choose the fallback
   location backend before initializing it; honor intervals/distance on both
   backends. Close the unused HandlerThread during cleanup or remove it.
3. A shared Dart stream with cancellable subscriptions and a timeout for one-shot
   reads. getCurrentLocation still owns/replaces the same method handler as
   getLocationUpdates; Ember Map uses its own handler and Geolocator first fix.
4. Explicit service-state events and permission-revocation handling, so application
   state cannot incorrectly stay running after native shutdown/failure.
5. Only then consider persistent restart configuration, typed accuracy settings,
   significant-change mode, native buffering or headless/reboot execution.

Before shipping, device-test repeated/overlapping start-stop, stop during bind,
permission denial/revocation, Activity recreation, screen-off/background recording,
Android provider fallback and iOS stop/restart. Source/type checks do not establish
these lifecycle guarantees. No complete Flutter platform build is run by this task.

## Validation performed

- Package `flutter test`: 4 passing channel/decoder/one-shot regression tests.
- `dart analyze lib test`: no issues.
- Swift `swiftc -typecheck` against the installed iOS simulator SDK and Flutter
  framework: passed (arm64, iOS 13 target).
- Ember Map Gradle `:background_location:compileDebugKotlin`: passed with existing
  deprecated-API warnings. Only the plugin target was compiled, not the app.
- Ember Map background service, GPS recorder and live-sharing service tests:
  47 passed using this package through a temporary local override.
- No running Dart app was available for hot reload; native changes require a
  fresh platform build by the user anyway.
