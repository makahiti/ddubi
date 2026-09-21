import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../core/constants/api_constants.dart';
import '../core/network/dio_provider.dart';
import '../data/mock_repository.dart';
import '../domain/auth.dart';
import '../domain/models.dart';
import '../services/auth_api.dart';
import '../services/api_ddubi_repository.dart';
import '../services/api_schedule_repository.dart';
import '../services/api_auth_repository.dart';
import '../services/app_api.dart';
import 'unread_notifications.dart';
import '../services/device_token_service.dart';
import '../services/kakao_login_service.dart';
import '../services/google_login_service.dart';
import '../services/naver_login_service.dart';
import '../services/place_api.dart';
import '../services/recommendation_api.dart';
import '../services/schedule_api.dart';
import '../core/config/app_environment.dart';

/// Mock 저장소를 쓸지. 운영 빌드에서는 어떤 플래그로도 가짜 데이터로 되돌아가지 않는다.
///
/// `USE_SCHEDULE_API=false` 가 운영 빌드에 섞이면 조용히 Mock 으로 출시되는 대신 여기서 바로 멈춘다.
bool get _useMockData {
  if (ApiConstants.useScheduleApi) return false;
  if (!AppEnvironment.allowMockData) {
    throw StateError('운영 빌드에서는 USE_SCHEDULE_API=false 로 Mock 데이터를 쓸 수 없습니다.');
  }
  return true;
}

final scheduleRepositoryProvider = Provider<ScheduleRepository>((ref) {
  if (_useMockData) {
    return MockDdubiRepository();
  }
  return ApiScheduleRepository(ref.watch(scheduleApiProvider));
});

final ddubiRepositoryProvider = Provider<DdubiRepository>((ref) {
  if (_useMockData) return MockDdubiRepository();
  return ApiDdubiRepository(
    ref.watch(scheduleRepositoryProvider),
    ref.watch(repeatScheduleRepositoryProvider),
    ref.watch(recommendationApiProvider),
  );
});

final authRepositoryProvider = Provider<AuthRepository>((ref) {
  return ApiAuthRepository(
    api: ref.watch(authApiProvider),
    sessionStore: ref.watch(authSessionStoreProvider),
    deviceTokenService: ref.watch(deviceTokenServiceProvider),
  );
});

final deviceTokenServiceProvider = Provider<DeviceTokenService>((ref) {
  final service = DeviceTokenService(
    ref.watch(dioProvider),
    ref.watch(authSessionStoreProvider),
  );
  unawaited(service.start());
  ref.onDispose(service.dispose);
  return service;
});

final kakaoLoginServiceProvider = Provider<KakaoLoginService>((ref) {
  return const KakaoSdkLoginService();
});

final googleLoginServiceProvider = Provider<GoogleLoginService>((ref) {
  return const GoogleSdkLoginService();
});

final naverLoginServiceProvider = Provider<NaverLoginService>((ref) {
  return const NaverWebLoginService();
});

final authApiProvider = Provider<AuthApi>((ref) {
  return AuthApi(ref.watch(dioProvider));
});

final scheduleApiProvider = Provider<ScheduleApi>((ref) {
  return ScheduleApi(ref.watch(dioProvider));
});

final placeApiProvider = Provider<PlaceSearchService>((ref) {
  if (_useMockData) {
    return const MockPlaceSearchService();
  }
  return PlaceApi(ref.watch(dioProvider));
});

final savedPlaceApiProvider = Provider<PlaceApi>((ref) {
  return PlaceApi(ref.watch(dioProvider));
});

final recommendationApiProvider = Provider<RecommendationApi>((ref) {
  return RecommendationApi(ref.watch(dioProvider));
});

final homeApiProvider = Provider<HomeApi>((ref) {
  return HomeApi(ref.watch(dioProvider), ref.watch(authSessionStoreProvider));
});

final repeatScheduleRepositoryProvider = Provider<RepeatScheduleRepository>((
  ref,
) {
  if (_useMockData) return MockDdubiRepository();
  return RepeatScheduleApi(ref.watch(dioProvider));
});

/// 종 아이콘의 안 읽은 알림 개수. 앱 전체가 같은 값을 본다.
final unreadNotificationsProvider = Provider<UnreadNotifications>((ref) {
  final counter = UnreadNotifications(
    ref.watch(notificationApiProvider),
    pushes: ref.watch(deviceTokenServiceProvider),
  );
  ref.onDispose(counter.dispose);
  return counter;
});

final notificationApiProvider = Provider<NotificationApi>((ref) {
  return NotificationApi(
    ref.watch(dioProvider),
    ref.watch(authSessionStoreProvider),
  );
});

final userApiProvider = Provider<UserApi>((ref) {
  return UserApi(ref.watch(dioProvider), ref.watch(authSessionStoreProvider));
});

final arrivalApiProvider = Provider<ArrivalApi>((ref) {
  return ArrivalApi(ref.watch(dioProvider));
});

final reportApiProvider = Provider<ReportApi>((ref) {
  return ReportApi(ref.watch(dioProvider));
});
