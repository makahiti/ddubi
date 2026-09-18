import 'dart:async';

import 'package:flutter/material.dart';

import '../core/diagnostics/app_log.dart';

typedef AppStartup = Future<void> Function(ValueChanged<double> reportProgress);

enum _LaunchStage { splash, app }

class AppLaunchFlow extends StatefulWidget {
  const AppLaunchFlow({
    super.key,
    required this.initialize,
    required this.child,
    this.splashDuration = const Duration(milliseconds: 280),
  });

  final AppStartup initialize;
  final Widget child;
  final Duration splashDuration;

  @override
  State<AppLaunchFlow> createState() => _AppLaunchFlowState();
}

class _AppLaunchFlowState extends State<AppLaunchFlow> {
  _LaunchStage _stage = _LaunchStage.splash;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) unawaited(_runLaunchFlow());
    });
  }

  Future<void> _runLaunchFlow() async {
    final startup = _initialize();
    await Future<void>.delayed(widget.splashDuration);
    if (!mounted) return;
    await startup;
    if (!mounted) return;
    setState(() => _stage = _LaunchStage.app);
  }

  Future<void> _initialize() async {
    try {
      await widget.initialize((_) {});
    } on Object catch (error, stackTrace) {
      AppLog.debug('App startup initialization failed: $error');
      AppLog.stack(stackTrace);
    }
  }

  @override
  Widget build(BuildContext context) => AnimatedSwitcher(
    duration: const Duration(milliseconds: 220),
    switchInCurve: Curves.easeOutCubic,
    switchOutCurve: Curves.easeInOut,
    transitionBuilder: (child, animation) => FadeTransition(
      opacity: animation,
      child: ScaleTransition(
        scale: Tween<double>(begin: .985, end: 1).animate(animation),
        child: child,
      ),
    ),
    child: _stage == _LaunchStage.app
        ? KeyedSubtree(key: const ValueKey('app'), child: widget.child)
        : MaterialApp(
            key: ValueKey(_stage),
            debugShowCheckedModeBanner: false,
            home: switch (_stage) {
              _LaunchStage.splash => const _InitialSplashScreen(),
              _LaunchStage.app => const SizedBox.shrink(),
            },
          ),
  );
}

class _InitialSplashScreen extends StatelessWidget {
  const _InitialSplashScreen();

  @override
  Widget build(BuildContext context) => Scaffold(
    key: const Key('initialSplashScreen'),
    backgroundColor: Colors.white,
    body: SafeArea(
      child: Center(
        child: LayoutBuilder(
          builder: (context, constraints) => Image.asset(
            'assets/images/ttubee/launch-logo.png',
            key: const Key('launchLogo'),
            width: (constraints.maxWidth * .54).clamp(190.0, 280.0).toDouble(),
            fit: BoxFit.contain,
            filterQuality: FilterQuality.high,
            semanticLabel: '뚜비 로고',
          ),
        ),
      ),
    ),
  );
}
