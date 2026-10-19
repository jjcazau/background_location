import 'package:background_location/background_location.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('com.almoullim.background_location/methods');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;

  tearDown(() {
    messenger.setMockMethodCallHandler(channel, null);
    channel.setMethodCallHandler(null);
  });

  test('start preserves requested provider and movement threshold', () async {
    final calls = <MethodCall>[];
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      return true;
    });
    await BackgroundLocation.startLocationService(
        distanceFilter: 15, forceAndroidLocationManager: true);
    await BackgroundLocation.stopLocationService();
    expect(calls.map((call) => call.method),
        ['start_location_service', 'stop_location_service']);
    expect(calls.first.arguments,
        {'distance_filter': 15.0, 'force_location_manager': true});
  });

  test('startup errors propagate to the caller', () async {
    messenger.setMockMethodCallHandler(channel, (call) async {
      throw PlatformException(code: 'permission_denied');
    });
    await expectLater(BackgroundLocation.startLocationService(),
        throwsA(isA<PlatformException>()));
  });

  test('one-shot location completes once and preserves mock metadata',
      () async {
    final first = BackgroundLocation().getCurrentLocation();
    Future<void> emit(int latitude) async {
      await messenger.handlePlatformMessage(
          channel.name,
          const StandardMethodCodec().encodeMethodCall(MethodCall('location', {
            'latitude': latitude,
            'longitude': 151,
            'is_mock': true,
          })),
          (_) {});
    }

    await emit(-33);
    final location = await first;
    await emit(-34);
    expect(location.latitude, -33.0);
    expect(location.isMock, true);
  });

  test('location callbacks ignore status strings and accept numeric payloads',
      () async {
    final locations = <Location>[];
    BackgroundLocation.getLocationUpdates(locations.add);
    Future<void> emit(String method, Object arguments) async {
      await messenger.handlePlatformMessage(
          channel.name,
          const StandardMethodCodec()
              .encodeMethodCall(MethodCall(method, arguments)),
          (_) {});
    }

    await emit('location', 'start_location_service');
    await emit('status', {'latitude': 1});
    await emit('location', {
      'latitude': -33,
      'longitude': 151.5,
      'accuracy': 5,
      'altitude': 10,
      'bearing': 0,
      'speed': 0,
      'time': 1234,
      'is_mock': false,
    });
    expect(locations, hasLength(1));
    expect(locations.single.latitude, -33.0);
    expect(locations.single.longitude, 151.5);
    expect(locations.single.time, 1234.0);
    expect(locations.single.isMock, false);
  });
}
