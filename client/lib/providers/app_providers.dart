import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../core/network/dio_provider.dart';
import '../data/mock_repository.dart';
import '../domain/auth.dart';
import '../domain/models.dart';
import '../services/auth_api.dart';
import '../services/recommendation_api.dart';
import '../services/schedule_api.dart';

final ddubiRepositoryProvider = Provider<DdubiRepository>((ref) {
  return MockDdubiRepository();
});

final authRepositoryProvider = Provider<AuthRepository>((ref) {
  return MockAuthRepository();
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
