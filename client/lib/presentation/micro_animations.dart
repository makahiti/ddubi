import 'dart:math' as math;

import 'package:flutter/material.dart';

class TtubeeEntrance extends StatefulWidget {
  const TtubeeEntrance({
    super.key,
    required this.asset,
    this.height = 120,
    this.fromRight = false,
    this.semanticLabel = '뚜비 캐릭터',
  });

  final String asset;
  final double height;
  final bool fromRight;
  final String semanticLabel;

  @override
  State<TtubeeEntrance> createState() => _TtubeeEntranceState();
}

class _TtubeeEntranceState extends State<TtubeeEntrance>
    with SingleTickerProviderStateMixin {
  late final AnimationController _controller = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 760),
  )..forward();
  late final Animation<double> _opacity = CurvedAnimation(
    parent: _controller,
    curve: const Interval(0, .58, curve: Curves.easeOut),
  );
  late final Animation<double> _scale = TweenSequence<double>([
    TweenSequenceItem(tween: Tween(begin: .95, end: 1.025), weight: 72),
    TweenSequenceItem(tween: Tween(begin: 1.025, end: 1), weight: 28),
  ]).animate(CurvedAnimation(parent: _controller, curve: Curves.easeOut));

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
    animation: _controller,
    child: Image.asset(
      widget.asset,
      height: widget.height,
      fit: BoxFit.contain,
      semanticLabel: widget.semanticLabel,
    ),
    builder: (context, child) {
      final remaining = 1 - Curves.easeOutCubic.transform(_controller.value);
      return Opacity(
        opacity: _opacity.value,
        child: Transform.translate(
          offset: widget.fromRight
              ? Offset(14 * remaining, 0)
              : Offset(0, 16 * remaining),
          child: Transform.scale(scale: _scale.value, child: child),
        ),
      );
    },
  );
}

class TtubeeCelebrate extends StatefulWidget {
  const TtubeeCelebrate({
    super.key,
    required this.asset,
    this.height = 118,
    this.semanticLabel = '기뻐하는 뚜비',
  });

  final String asset;
  final double height;
  final String semanticLabel;

  @override
  State<TtubeeCelebrate> createState() => _TtubeeCelebrateState();
}

class _TtubeeCelebrateState extends State<TtubeeCelebrate>
    with SingleTickerProviderStateMixin {
  late final AnimationController _controller = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 920),
  )..forward();
  late final Animation<double> _scale = TweenSequence<double>([
    TweenSequenceItem(tween: Tween(begin: .8, end: 1.08), weight: 55),
    TweenSequenceItem(tween: Tween(begin: 1.08, end: 1), weight: 45),
  ]).animate(CurvedAnimation(parent: _controller, curve: Curves.easeOut));

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
    animation: _controller,
    child: Image.asset(
      widget.asset,
      height: widget.height,
      fit: BoxFit.contain,
      semanticLabel: widget.semanticLabel,
    ),
    builder: (context, child) {
      final bounce = math.sin(_controller.value * math.pi * 4);
      final envelope = 1 - _controller.value;
      return Transform.translate(
        offset: Offset(0, -7 * bounce.abs() * envelope),
        child: Transform.scale(scale: _scale.value, child: child),
      );
    },
  );
}

class TtubeeIdle extends StatefulWidget {
  const TtubeeIdle({
    super.key,
    required this.asset,
    this.height = 112,
    this.semanticLabel = '일정을 기다리는 뚜비',
  });

  final String asset;
  final double height;
  final String semanticLabel;

  @override
  State<TtubeeIdle> createState() => _TtubeeIdleState();
}

class _TtubeeIdleState extends State<TtubeeIdle>
    with SingleTickerProviderStateMixin {
  late final AnimationController _controller = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 2600),
  )..repeat(reverse: true);

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
    animation: _controller,
    child: Image.asset(
      widget.asset,
      height: widget.height,
      fit: BoxFit.contain,
      semanticLabel: widget.semanticLabel,
    ),
    builder: (context, child) => Transform.translate(
      offset: Offset(0, -4 * Curves.easeInOut.transform(_controller.value)),
      child: Transform.scale(scale: 1 + .012 * _controller.value, child: child),
    ),
  );
}

class TtubeeRunning extends StatefulWidget {
  const TtubeeRunning({super.key, required this.asset, this.height = 72});

  final String asset;
  final double height;

  @override
  State<TtubeeRunning> createState() => _TtubeeRunningState();
}

class _TtubeeRunningState extends State<TtubeeRunning>
    with SingleTickerProviderStateMixin {
  late final AnimationController _controller = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 780),
  )..repeat(reverse: true);

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
    animation: _controller,
    child: Image.asset(
      widget.asset,
      height: widget.height,
      fit: BoxFit.contain,
      semanticLabel: '달리는 뚜비',
    ),
    builder: (context, child) {
      final wave = math.sin(_controller.value * math.pi);
      return Transform.translate(
        offset: Offset((_controller.value - .5) * 10, -3 * wave),
        child: Transform.rotate(
          angle: (_controller.value - .5) * math.pi / 45,
          child: child,
        ),
      );
    },
  );
}
