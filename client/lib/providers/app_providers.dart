import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../core/network/dio_provider.dart';
import '../data/mock_repository.dart';
import '../domain/auth.dart';
import '../domain/models.dart';
import '../services/auth_api.dart';
import '../services/api_auth_repository.dart';
import '../services/kakao_login_service.dart';
import '../services/google_login_service.dart';
import '../services/naver_login_service.dart';
import '../services/recommendation_api.dart';
import '../services/schedule_api.dart';

final ddubiRepositoryProvider = Provider<DdubiRepository>((ref) {
  return MockDdubiRepository();
});

final authRepositoryProvider = Provider<AuthRepository>((ref) {
  return ApiAuthRepository(
    api: ref.watch(authApiProvider),
    sessionStore: ref.watch(authSessionStoreProvider),
  );
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

final recommendationApiProvider = Provider<RecommendationApi>((ref) {
  return RecommendationApi(ref.watch(dioProvider));
});
