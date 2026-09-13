import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'app.dart';
import 'core/auth/google_auth_config.dart';
import 'core/auth/kakao_auth_config.dart';
import 'core/maps/kakao_map_config.dart';
import 'presentation/launch_flow.dart';
import 'services/device_token_service.dart';
import 'services/time_based_app_icon_service.dart';

final _appIconLifecycle = TimeBasedAppIconLifecycle();

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();

  runApp(
    ProviderScope(
      child: AppLaunchFlow(initialize: _initializeApp, child: const DdubiApp()),
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
