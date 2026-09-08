import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'app/router.dart';
import 'core/theme.dart';

class DdubiApp extends ConsumerWidget {
  const DdubiApp({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) => MaterialApp.router(
    title: '뚜비',
    debugShowCheckedModeBanner: false,
    theme: DdubiTheme.light,
    scrollBehavior: const DdubiScrollBehavior(),
    routerConfig: ref.watch(appRouterProvider),
  );
}
