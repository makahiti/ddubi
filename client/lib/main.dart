import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'app.dart';
import 'core/network/dio_provider.dart';
import 'domain/auth.dart';
import 'services/secure_session_storage.dart';
import 'core/network/session_refresh.dart';
import 'core/constants/api_constants.dart';

import 'package:dio/dio.dart';

import 'core/auth/google_auth_config.dart';
import 'core/auth/kakao_auth_config.dart';
import 'core/maps/kakao_map_config.dart';
import 'presentation/launch_flow.dart';
import 'services/device_token_service.dart';
import 'services/time_based_app_icon_service.dart';

final _appIconLifecycle = TimeBasedAppIconLifecycle();

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  final sessions = AuthSessionStore(storage: const SecureSessionStorage());

  runApp(
    ProviderScope(
      overrides: [authSessionStoreProvider.overrideWithValue(sessions)],
      child: AppLaunchFlow(
        initialize: (progress) async {
          const storage = SecureSessionStorage();
          sessions.hasLaunched = await storage.hasLaunched();
          await sessions.restore();
          if (sessions.session != null) {
            final refreshClient = Dio(
              BaseOptions(
                baseUrl: ApiConstants.baseUrl,
                connectTimeout: ApiConstants.connectTimeout,
                receiveTimeout: ApiConstants.receiveTimeout,
              ),
            );
            try {
              await SessionRefresher(sessions, refreshClient).refresh();
            } on DioException {
              // A rejected token is cleared; connectivity failures retain it.
            } finally {
              refreshClient.close();
            }
          }
          await storage.markLaunched();
          await _initializeApp(progress);
        },
        child: const DdubiApp(),
      ),
    ),
  );

  _appIconLifecycle.start();
}

Future<void> _initializeApp(ValueChanged<double> reportProgress) async {
  reportProgress(.06);
  await initializeFirebaseForAndroid();
  reportProgress(.28);
  await GoogleAuthConfig.initialize();
  reportProgress(.48);
  await KakaoAuthConfig.initialize();
  reportProgress(.68);
  await KakaoMapConfig.initialize();
  reportProgress(.9);
}
