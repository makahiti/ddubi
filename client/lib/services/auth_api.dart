import 'package:dio/dio.dart';

import '../core/constants/api_paths.dart';

class AuthApi {
  const AuthApi(this._dio);

  final Dio _dio;

  Future<Response<Map<String, dynamic>>> login({
    required String email,
    required String password,
    String? deviceId,
  }) {
    return _dio.post<Map<String, dynamic>>(
      ApiPaths.login,
      data: {'email': email, 'password': password, 'deviceId': ?deviceId},
    );
  }

  Future<Response<Map<String, dynamic>>> signUp({
    required String email,
    required String password,
    required String nickname,
    String? deviceId,
  }) {
    return _dio.post<Map<String, dynamic>>(
      ApiPaths.signUp,
      data: {
        'email': email,
        'password': password,
        'nickname': nickname,
        'deviceId': ?deviceId,
      },
    );
  }
}
