import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'app.dart';
import 'core/auth/google_auth_config.dart';
import 'core/auth/kakao_auth_config.dart';
import 'core/maps/kakao_map_config.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await GoogleAuthConfig.initialize();
  await KakaoAuthConfig.initialize();
  await KakaoMapConfig.initialize();
  runApp(const ProviderScope(child: DdubiApp()));
}
