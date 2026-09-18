import 'package:ddubi/core/constants/api_constants.dart';

import 'session_refresh.dart';

import 'package:ddubi/domain/auth.dart';
import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

final authSessionStoreProvider = Provider<AuthSessionStore>((ref) {
  return AuthSessionStore();
});

final dioProvider = Provider<Dio>((ref) {
  final sessionStore = ref.watch(authSessionStoreProvider);
  final refreshClient = Dio(
    BaseOptions(
      baseUrl: ApiConstants.baseUrl,
      connectTimeout: ApiConstants.connectTimeout,
      receiveTimeout: ApiConstants.receiveTimeout,
    ),
  );
  final refresher = SessionRefresher(sessionStore, refreshClient);
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
        options.extra['authDeviceId'] = sessionStore.session?.deviceId;
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
            alreadyRetried ||
            error.requestOptions.extra['authDeviceId'] != session.deviceId) {
          handler.next(error);
          return;
        }

        try {
          if (error.requestOptions.headers['Authorization'] ==
              'Bearer ${session.accessToken}') {
            await refresher.refresh();
          }
          final refreshed = sessionStore.session;
          if (refreshed == null || refreshed.deviceId != session.deviceId) {
            handler.next(error);
            return;
          }

          final request = error.requestOptions;
          request.extra['retried'] = true;
          request.headers['Authorization'] = 'Bearer ${refreshed.accessToken}';
          handler.resolve(await dio.fetch<dynamic>(request));
        } on DioException catch (retryError) {
          handler.next(retryError);
        } on Object {
          handler.next(error);
        }
      },
    ),
  );

  ref.onDispose(() {
    dio.close();
    refreshClient.close();
  });
  return dio;
});
