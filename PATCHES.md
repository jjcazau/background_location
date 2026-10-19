# Ember Map maintained fork

The maintained `master` branch retains jjcazau repository history.
The first two releases were prepared on `codex/ember-map-location-fixes`.
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

## Follow-up items identified after 0.9.5 (items 1–4 implemented in 0.9.6)

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


## 0.9.6+embermap.1 — lifecycle and shared stream

Local follow-up implementation, 2026-10-19:

- Explicit native starting/running/stopped/error states. Duplicate starts join the
  pending result; native stop cancels pending results. Generation tokens reject
  stale callbacks. Startup has a 30-second timeout and can be retried after error.
- Android asks for coarse/fine permission before binding or foreground promotion,
  rejects disabled location services, waits for the provider subscription, and
  propagates bind/foreground/subscription failures to Dart. Pending bindings are
  released even before connection. Permission requests for other plugins are
  not consumed.
- Tracking survives Activity detach/reattach; each plugin engine owns its bridge
  and removes its permission-result listener on Activity detach. Engine detach
  explicitly cleans up tracking.
- Choose the Android backend before initialization. GPS fallback honors interval
  and distance, reports provider disablement, and releases callbacks on stop.
  Fused subscription completion is checked and callbacks removed after cancelled
  requests. Remove the unused HandlerThread and LocalBroadcastManager transport.
- Android permission/location availability is checked on Activity reattachment
  and every five seconds on the main looper while running. Stop/error events
  reach Dart. This is not a hard realtime guarantee: suspended/killed processes
  cannot emit events, and OS scheduling can delay the check.
- iOS waits for authorization, reports denial and non-transient CoreLocation
  errors, ignores transient locationUnknown, and sends matching lifecycle events.
  Background updates are enabled only when the app declares location background
  mode. Running denotes an accepted subscription, not receipt of a GPS fix.
- Public broadcast locationStream and serviceStateStream share one dispatcher.
  getCurrentLocation subscribes independently, times out, and cancels its temporary
  subscriptions on success/error/stop. Legacy callback registration replaces only
  the legacy listener; removeLocationUpdates cancels it explicitly.
- Configuration interval remains a next-subscription setting. Concurrent starts
  retain the first start's configuration; stop then start to change providers.
- Minimum Android SDK is now 21 and Flutter >=3.10, consistent with the maintained
  Dart 3 dependency and Android test stack. Ember Map already exceeds these minima.

### Validation for 0.9.6

- Seven Flutter package regression tests: concurrent stream/legacy/one-shot
  delivery, cancellation, timeout, native errors and payload decoding.
- Eight Android JVM tests: four state-machine tests and four Robolectric native
  bridge/service tests. Exercise real pending binding cleanup, permission denial,
  timeout, forced GPS fallback interval/distance, Activity replacement, delivery,
  permission revocation and removal of location listeners. Test runtime: API 28.
- Swift lifecycle harness runs cancellation, coalescing, retry, late callback,
  runtime failure and timeout scenarios; actual plugin type-checks against the
  installed iOS simulator SDK and Flutter framework.
- Fifty Ember Map tests cover background service, recorder and sharing integration,
  including native-failure state reset and sharing shutdown. Native shutdown
  reaches recorder error handling; sharing ends its track and preserves its outbox.
- Dart analysis passes for the package. App analysis has existing naming and
  constructor-style infos only. No full Flutter platform builds or physical-device
  tests run. No running app was available for hot reload.

Headless execution, process/reboot restoration, native buffering and optional
significant-change tracking remain separate features, not claims of this release.
Physical-device permission/background/screen-off checks are still needed before
shipping. See TESTING.md for repeatable targeted commands.


## 0.9.7+embermap.1 — provider fallback and accuracy

Independently implemented after reviewing the published
[background_locator_2 2.0.6 archive](https://pub.dev/api/archives/background_locator_2-2.0.6.tar.gz).
No source was copied from that separate MIT-licensed package.

- Network plus GPS fallback, coarse-only network operation, recovery of disabled
  providers, and teardown of all selected listeners. A selected provider becoming
  unavailable is terminal only when all selected providers are disabled.
- Monotonic timestamps enforce a 30-second freshness limit and ordered fixes.
  Prefer a recent better provider fix for ten seconds before accepting poorer
  fallback accuracy; same-provider movement continues. Do not emit cached fixes.
- Nullable native speed/vertical/bearing uncertainty and Android provider names.
- Typed high/balanced/lowPower startup settings, mapped to fused priority,
  fallback provider selection and CoreLocation desiredAccuracy. High remains
  the default. Stop/start is required to change active settings.
- Avoid the reviewed package's incorrect wake-lock release, impossible accuracy
  predicate and unbounded-age cached startup fix. No wake lock, reboot receiver,
  headless engine or region monitoring added.

See README.md for exact policy and TESTING.md for repeatable commands.

### Validation for 0.9.7

- Nine package Flutter tests pass; package Dart analysis is clean.
- Thirteen Android JVM tests pass (four lifecycle, seven real bridge/service,
  two fix-arbitration tests), Robolectric API 28. Includes coarse-only network
  startup, loss of GPS with network continuity, loss of all selected providers,
  low-power provider selection, metadata and callback cleanup.
- Swift lifecycle and real CLLocation metadata/configuration harness passes;
  all Swift plugin sources type-check against the iOS simulator SDK/Flutter.
- Full platform builds and physical-device tests remain for the application owner.
