import 'package:ddubi/core/constants/api_constants.dart';
import 'package:ddubi/core/constants/api_paths.dart';
import 'package:ddubi/domain/auth.dart';
import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

final authSessionStoreProvider = Provider<AuthSessionStore>((ref) {
  return AuthSessionStore();
});

final dioProvider = Provider<Dio>((ref) {
  final sessionStore = ref.watch(authSessionStoreProvider);
  final dio = Dio(
    BaseOptions(
      baseUrl: ApiConstants.baseUrl,
      connectTimeout: ApiConstants.connectTimeout,
      receiveTimeout: ApiConstants.receiveTimeout,
      headers: const {'Content-Type': 'application/json'},
    ),
  );

  dio.interceptors.add(
    InterceptorsWrapper(
      onRequest: (options, handler) {
        final accessToken = sessionStore.accessToken;
        if (accessToken != null) {
          options.headers['Authorization'] = 'Bearer $accessToken';
        }
        handler.next(options);
      },
      onError: (error, handler) async {
        final session = sessionStore.session;
        final isAuthRequest = error.requestOptions.path.startsWith('/auth/');
        final alreadyRetried = error.requestOptions.extra['retried'] == true;
        if (error.response?.statusCode != 401 ||
            session == null ||
            isAuthRequest ||
            alreadyRetried) {
          handler.next(error);
          return;
        }

        try {
          final refreshClient = Dio(
            BaseOptions(
              baseUrl: ApiConstants.baseUrl,
              connectTimeout: ApiConstants.connectTimeout,
              receiveTimeout: ApiConstants.receiveTimeout,
              headers: const {'Content-Type': 'application/json'},
            ),
          );
          final response = await refreshClient.post<Map<String, dynamic>>(
            ApiPaths.refresh,
            data: {
              'refreshToken': session.refreshToken,
              'deviceId': session.deviceId,
            },
          );
          final data = response.data?['data'];
          if (response.data?['success'] != true ||
              data is! Map<String, dynamic>) {
            throw const FormatException('토큰 갱신 응답 형식이 올바르지 않습니다.');
          }
          final refreshed = AuthSession.fromJson(data);
          sessionStore.save(refreshed);

          final request = error.requestOptions;
          request.extra['retried'] = true;
          request.headers['Authorization'] = 'Bearer ${refreshed.accessToken}';
          handler.resolve(await dio.fetch<dynamic>(request));
        } on Object {
          sessionStore.clear();
          handler.next(error);
        }
      },
    ),
  );

  return dio;
});
