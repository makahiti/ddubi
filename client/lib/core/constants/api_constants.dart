import '../config/app_environment.dart';

class ApiConstants {
  ApiConstants._();

  /// 운영/개발 주소 선택은 [AppEnvironment.apiBaseUrl] 이 한다. 여기서는 그대로 넘긴다.
  static String get baseUrl => AppEnvironment.apiBaseUrl;

  static const bool useScheduleApi = bool.fromEnvironment(
    'USE_SCHEDULE_API',
    defaultValue: true,
  );

  static const Duration connectTimeout = Duration(seconds: 10);
  static const Duration receiveTimeout = Duration(seconds: 10);
}
