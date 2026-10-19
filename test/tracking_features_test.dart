import 'dart:async';
import 'package:background_location/background_location.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('com.almoullim.background_location/methods');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  Future<void> emit(String method, Map<String, Object?> args) async {
    await messenger.handlePlatformMessage(
        channel.name,
        const StandardMethodCodec().encodeMethodCall(MethodCall(method, args)),
        (_) {});
    await Future<void>.delayed(Duration.zero);
  }

  setUp(() async {
    BackgroundLocation.clearDiagnostics();
    final sub = BackgroundLocation.serviceStateStream.listen((_) {});
    await emit('service_state', {'state': 'stopped'});
    await sub.cancel();
  });
  tearDown(() => messenger.setMockMethodCallHandler(channel, null));
  final now = DateTime.utc(2026, 10, 19);
  Location fix({double lat = 1, double accuracy = 5, int seconds = 0}) =>
      Location(
          latitude: lat,
          longitude: 2,
          accuracy: accuracy,
          time: now
              .add(Duration(seconds: seconds))
              .millisecondsSinceEpoch
              .toDouble());

  test('quality rejects old, future, uncertain and unordered observations', () {
    final filter = LocationQualityFilter();
    expect(filter.rejection(fix(seconds: -31), now), 'stale_timestamp');
    expect(filter.rejection(fix(seconds: 2), now), 'stale_timestamp');
    expect(filter.rejection(fix(accuracy: double.nan), now), 'poor_accuracy');
    expect(filter.rejection(fix(accuracy: 200), now), 'poor_accuracy');
    expect(filter.rejection(fix(), now), isNull);
    expect(filter.rejection(fix(seconds: -1), now), 'out_of_order');
    filter.reset();
    expect(filter.rejection(fix(seconds: -1), now), isNull);
  });
  test('quality rejects jumps but permits uncertainty and actual travel', () {
    final filter = LocationQualityFilter();
    expect(filter.rejection(fix(), now), isNull);
    expect(filter.rejection(fix(lat: 2, seconds: 1), now), 'implied_speed');
    expect(filter.rejection(fix(lat: 1.0005, seconds: 1), now), isNull);
    expect(filter.rejection(fix(lat: double.nan), now), 'invalid_coordinate');
    expect(filter.rejection(Location(latitude: 1, longitude: 2), now),
        'missing_timestamp');
  });
  test(
      'configuration validates before native work and preserves recording priority',
      () async {
    final calls = <MethodCall>[];
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      return true;
    });
    await BackgroundLocation.startLocationService(
        config: TrackingConfig.sharing);
    await BackgroundLocation.configure(TrackingConfig.recording);
    expect(calls.first.arguments, containsPair('interval_ms', 15000));
    expect(calls.first.arguments, containsPair('adaptive', true));
    expect(calls.last.arguments, containsPair('adaptive', false));
    expect(calls.last.arguments, containsPair('interval_ms', 1000));
    await expectLater(
        BackgroundLocation.configure(
            const TrackingConfig(interval: Duration.zero)),
        throwsArgumentError);
    expect(calls.length, 2);
  });
  test('provider snapshot and events keep lifecycle separate', () async {
    final events = <LocationProviderState>[];
    final sub = BackgroundLocation.providerStateStream.listen(events.add);
    await emit('provider_state',
        {'enabled': true, 'authorization': 'whenInUse', 'precise': false});
    messenger.setMockMethodCallHandler(
        channel,
        (_) async => {
              'state': 'running',
              'motion': 'stationary',
              'provider': {'enabled': true, 'precise': false},
              'configuration': {'adaptive': true},
            });
    final state = await BackgroundLocation.getState();
    expect(state.state, 'running');
    expect(state.motion, 'stationary');
    expect(state.provider.precise, false);
    expect(events.single.authorization, 'whenInUse');
    await sub.cancel();
  });
  test('diagnostics bounded and filter logs omit raw coordinates', () {
    for (var i = 0; i < 250; i++) {
      LocationQualityFilter().rejection(fix(accuracy: 1000), now);
    }
    expect(BackgroundLocation.diagnostics.length, 200);
    expect(BackgroundLocation.diagnostics.last['reason'], 'poor_accuracy');
    expect(
        BackgroundLocation.diagnostics.toString(), isNot(contains('latitude')));
  });
  test('quality one-shot selects best sample without stealing listeners',
      () async {
    final seen = <Location>[];
    final sub = BackgroundLocation.locationStream.listen(seen.add);
    final request =
        BackgroundLocation().getCurrentPosition(samples: 2, desiredAccuracy: 5);
    final time = DateTime.now().millisecondsSinceEpoch;
    await emit('location',
        {'latitude': 1, 'longitude': 2, 'accuracy': 1, 'time': time - 60000});
    await emit('location',
        {'latitude': 1, 'longitude': 2, 'accuracy': 50, 'time': time});
    await emit('location',
        {'latitude': 1, 'longitude': 2, 'accuracy': 20, 'time': time});
    expect((await request).accuracy, 20);
    expect(seen.length, 3);
    await sub.cancel();
  });
  test('quality one-shot timeout returns best or throws when none qualifies',
      () async {
    final request = BackgroundLocation().getCurrentPosition(
        timeout: const Duration(milliseconds: 20), desiredAccuracy: 1);
    await emit('location', {
      'latitude': 1,
      'longitude': 2,
      'accuracy': 20,
      'time': DateTime.now().millisecondsSinceEpoch
    });
    expect((await request).accuracy, 20);
    await emit('service_state', {'state': 'stopped'});
    await expectLater(
        BackgroundLocation()
            .getCurrentPosition(timeout: const Duration(milliseconds: 10)),
        throwsA(isA<TimeoutException>()));
  });
  test('one-shot does not return a best sample that expired while waiting',
      () async {
    final request = BackgroundLocation().getCurrentPosition(
        timeout: const Duration(milliseconds: 250),
        maximumAge: const Duration(milliseconds: 100),
        desiredAccuracy: 1);
    final check = expectLater(request, throwsA(isA<TimeoutException>()));
    await emit('location', {
      'latitude': 1,
      'longitude': 2,
      'accuracy': 20,
      'time': DateTime.now().millisecondsSinceEpoch
    });
    await check;
  });

  test('quality one-shot cancels on stop', () async {
    final request = BackgroundLocation().getCurrentPosition();
    final check = expectLater(request, throwsA(isA<PlatformException>()));
    await emit('service_state', {'state': 'stopped'});
    await check;
  });
}
