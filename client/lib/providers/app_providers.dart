import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../core/constants/api_constants.dart';
import '../core/network/dio_provider.dart';
import '../data/mock_repository.dart';
import '../domain/auth.dart';
import '../domain/models.dart';
import '../services/auth_api.dart';
import '../services/api_schedule_repository.dart';
import '../services/api_auth_repository.dart';
import '../services/device_token_service.dart';
import '../services/kakao_login_service.dart';
import '../services/google_login_service.dart';
import '../services/naver_login_service.dart';
import '../services/place_api.dart';
import '../services/recommendation_api.dart';
import '../services/schedule_api.dart';

final ddubiRepositoryProvider = Provider<DdubiRepository>((ref) {
  return MockDdubiRepository();
});

final scheduleRepositoryProvider = Provider<ScheduleRepository>((ref) {
  if (!ApiConstants.useScheduleApi) {
    return ref.watch(ddubiRepositoryProvider);
  }
  return ApiScheduleRepository(ref.watch(scheduleApiProvider));
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
  return const NaverSdkLoginService();
});

final authApiProvider = Provider<AuthApi>((ref) {
  return AuthApi(ref.watch(dioProvider));
});

final scheduleApiProvider = Provider<ScheduleApi>((ref) {
  return ScheduleApi(ref.watch(dioProvider));
});

final placeApiProvider = Provider<PlaceSearchService>((ref) {
  if (!ApiConstants.useScheduleApi) {
    return const MockPlaceSearchService();
  }
  return PlaceApi(ref.watch(dioProvider));
});

final recommendationApiProvider = Provider<RecommendationApi>((ref) {
  return RecommendationApi(ref.watch(dioProvider));
});
