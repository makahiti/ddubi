import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'app/router.dart';
import 'core/theme.dart';
import 'providers/app_providers.dart';

class DdubiApp extends ConsumerWidget {
  const DdubiApp({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final router = ref.watch(appRouterProvider);
    ref.read(deviceTokenServiceProvider).setNotificationOpenHandler(router.go);

    return MaterialApp.router(
      title: '뚜비',
      debugShowCheckedModeBanner: false,
      theme: DdubiTheme.light,
      scrollBehavior: const DdubiScrollBehavior(),
      routerConfig: router,
    );
  }
}
