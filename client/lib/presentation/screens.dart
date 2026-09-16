import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../providers/app_providers.dart';

import 'package:flutter/services.dart';
import 'package:firebase_messaging/firebase_messaging.dart';
import 'package:go_router/go_router.dart';
import 'package:image_picker/image_picker.dart';
import 'package:kakao_maps_flutter/kakao_maps_flutter.dart';

import '../core/constants/layout_constants.dart';
import '../core/maps/kakao_map_config.dart';
import '../core/theme.dart';
import '../domain/auth.dart';
import '../domain/models.dart';
import '../services/kakao_login_service.dart';
import '../services/google_login_service.dart';
import '../services/naver_login_service.dart';
import '../services/app_api.dart';
import '../services/place_api.dart';
import '../services/recommendation_api.dart';
import 'auth_screens.dart';
import 'help_inquiry_screen.dart';
import 'micro_animations.dart';
import 'more_page_widgets.dart';
import 'widgets.dart';
import 'schedule_evaluation_card.dart';
import '../domain/schedule_evaluation.dart';

export 'notification_center.dart';

void _open(BuildContext c, Widget page) =>
    Navigator.of(c).push(MaterialPageRoute(builder: (_) => page));
const _pad = EdgeInsets.fromLTRB(16, 8, 16, 20);
const _weekdays = ['월', '화', '수', '목', '금', '토', '일'];

RouteOption? _primaryRoute(List<RouteOption> routes) {
  if (routes.isEmpty) return null;
  return routes.firstWhere(
    (route) => route.kind == RouteKind.recommended,
    orElse: () => routes.first,
  );
}

class ScheduleDetailArguments {
  const ScheduleDetailArguments({
    required this.schedule,
    this.routes = const [],
  });

  final Schedule schedule;
  final List<RouteOption> routes;
}

class _RouteSearchHistory {
  static final List<RouteSearchCondition> items = [];

  static void add(RouteSearchCondition condition) {
    items.removeWhere(
      (item) =>
          item.origin.name == condition.origin.name &&
          item.destination.name == condition.destination.name,
    );
    items.insert(0, condition);
    if (items.length > 5) items.removeRange(5, items.length);
  }
}

class _ProfileImageCache {
  static int? characterIndex = 0;
  static Uint8List? bytes;
}

class _AppPermissionController {
  static const _channel = MethodChannel('com.ssafy.ddubi/app_permissions');

  static Future<bool> hasLocationPermission() async {
    if (kIsWeb) return false;
    return await _channel.invokeMethod<bool>('hasLocationPermission') ?? false;
  }

  static Future<bool> requestLocationPermission() async {
    if (kIsWeb) return false;
    return await _channel.invokeMethod<bool>('requestLocationPermission') ??
        false;
  }

  static Future<bool> hasNotificationPermission() async {
    final settings = await FirebaseMessaging.instance.getNotificationSettings();
    return settings.authorizationStatus == AuthorizationStatus.authorized ||
        settings.authorizationStatus == AuthorizationStatus.provisional;
  }

  static Future<bool> requestNotificationPermission() async {
    final settings = await FirebaseMessaging.instance.requestPermission();
    return settings.authorizationStatus == AuthorizationStatus.authorized ||
        settings.authorizationStatus == AuthorizationStatus.provisional;
  }

  static Future<void> openSettings() async {
    if (!kIsWeb) await _channel.invokeMethod<void>('openAppSettings');
  }
}

String _fullDate(DateTime date) =>
    '${date.year}.${date.month.toString().padLeft(2, '0')}.${date.day.toString().padLeft(2, '0')} (${_weekdays[date.weekday - 1]})';

String _arrivalResultText(String? result) => switch (result) {
  'ON_TIME' => '정시',
  'TIGHT' => '빠듯',
  'LATE_RISK' => '지연',
  _ => '판정 없음',
};

class IntroScreen extends StatefulWidget {
  const IntroScreen({super.key});

  @override
  State<IntroScreen> createState() => _IntroScreenState();
}

class _IntroScreenState extends State<IntroScreen> {
  final _pages = const [
    {
      'bg': 'intro-bg-1.png',
      'title': '뚜비',
      'body': '변하는 길 위에서,\n가장 좋은 출발의 순간을 알려드려요.',
      'character': 'welcome.png',
    },
    {
      'bg': 'intro-bg-2.png',
      'title': '중요한 일정,\n놓치지 않게 도와드려요',
      'body': '일정과 실시간 이동 변수를 바탕으로\n추천 출발 시간을 안내해요.',
      'character': 'intro-calendar-clock.png',
    },
    {
      'bg': 'intro-bg-3.png',
      'title': '실시간 이동 변수를\n한눈에 확인해요',
      'body': '날씨, 혼잡도, 지하철 지연,\n도로 통제까지 함께 반영해요.',
      'character': 'empty-schedule.png',
    },
    {
      'bg': 'intro-bg-4.png',
      'title': '여러 변수를 반영해\n출발 시간을 추천해요',
      'body': '일정 중요도와 예상 지연을 반영해\n가장 알맞은 출발 시간을 알려드려요.',
      'character': 'celebrate.png',
    },
  ];
  final _controller = PageController();
  var _page = 0;

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final last = _page == _pages.length - 1;
    return Scaffold(
      backgroundColor: Colors.white,
      body: SafeArea(
        child: Stack(
          children: [
            Positioned.fill(
              child: Image.asset(
                'assets/images/ttubee/${_page == 1
                    ? 'intro-bg-bus-stop.png'
                    : _page == 2
                    ? 'intro-bg-plaza.png'
                    : _page == 3
                    ? 'intro-bg-skyline.png'
                    : 'intro-bg-4.png'}',
                fit: BoxFit.cover,
              ),
            ),
            const Positioned.fill(
              child: DecoratedBox(
                decoration: BoxDecoration(color: Color(0x66FFFDF7)),
              ),
            ),
            Column(
              children: [
                Align(
                  alignment: Alignment.centerRight,
                  child: TextButton(
                    onPressed: () => context.go('/login'),
                    child: const Text(
                      '건너뛰기',
                      style: TextStyle(
                        color: Color(0xFF078C83),
                        fontWeight: FontWeight.w800,
                      ),
                    ),
                  ),
                ),
                Expanded(
                  child: PageView.builder(
                    controller: _controller,
                    itemCount: _pages.length,
                    onPageChanged: (value) => setState(() => _page = value),
                    itemBuilder: (context, index) {
                      final page = _pages[index];
                      if (index == 0) return const _IntroStartPage();
                      return Padding(
                        padding: const EdgeInsets.fromLTRB(16, 22, 16, 8),
                        child: Column(
                          children: [
                            _IntroProgressBadge(_progressLabel(index)),
                            const SizedBox(height: 12),
                            Text(
                              page['title']!,
                              textAlign: TextAlign.center,
                              style: const TextStyle(
                                fontSize: 30,
                                height: 1.2,
                                fontWeight: FontWeight.w900,
                                color: Color(0xFF101A32),
                              ),
                            ),
                            const SizedBox(height: 10),
                            Text(
                              page['body']!,
                              textAlign: TextAlign.center,
                              style: const TextStyle(
                                fontSize: 16,
                                height: 1.5,
                                color: Color(0xFF52647A),
                              ),
                            ),
                            const SizedBox(height: 12),
                            Expanded(
                              child: Stack(
                                fit: StackFit.expand,
                                children: [
                                  if (index == 2)
                                    const Align(
                                      alignment: Alignment.bottomCenter,
                                      child: _IntroBench(),
                                    ),
                                  Align(
                                    alignment: Alignment.bottomCenter,
                                    child: Padding(
                                      padding: EdgeInsets.only(
                                        bottom: index == 2
                                            ? 8
                                            : index == 3
                                            ? 128
                                            : 18,
                                      ),
                                      child: Image.asset(
                                        'assets/images/ttubee/${page['character']}',
                                        height: index == 1
                                            ? 355
                                            : index == 2
                                            ? 220
                                            : 305,
                                        fit: BoxFit.contain,
                                      ),
                                    ),
                                  ),
                                  if (index == 1)
                                    const Positioned(
                                      right: 12,
                                      top: 30,
                                      child: _SpeechBubble('제시간에\n도착해요!'),
                                    ),
                                  if (index == 2)
                                    const Positioned(
                                      top: 18,
                                      left: 0,
                                      right: 0,
                                      child: _MobilityCards(),
                                    ),
                                  if (index == 3)
                                    const Positioned(
                                      bottom: 22,
                                      left: 0,
                                      right: 0,
                                      child: _DepartureCards(),
                                    ),
                                ],
                              ),
                            ),
                          ],
                        ),
                      );
                    },
                  ),
                ),
                Padding(
                  padding: const EdgeInsets.fromLTRB(24, 4, 24, 18),
                  child: SizedBox(
                    height: 48,
                    child: Stack(
                      children: [
                        Align(
                          alignment: Alignment.center,
                          child: Row(
                            mainAxisSize: MainAxisSize.min,
                            children: List.generate(
                              4,
                              (i) => AnimatedContainer(
                                duration: const Duration(milliseconds: 200),
                                margin: const EdgeInsets.symmetric(
                                  horizontal: 3,
                                ),
                                width: i == _page ? 10 : 8,
                                height: 8,
                                decoration: BoxDecoration(
                                  color: i == _page
                                      ? const Color(0xFF08A79D)
                                      : const Color(0xFFD4DAD8),
                                  borderRadius: BorderRadius.circular(8),
                                ),
                              ),
                            ),
                          ),
                        ),
                        if (_page > 0)
                          Align(
                            alignment: Alignment.centerLeft,
                            child: _roundNav(
                              Icons.arrow_back,
                              () => _controller.previousPage(
                                duration: const Duration(milliseconds: 260),
                                curve: Curves.easeOut,
                              ),
                            ),
                          ),
                        Align(
                          alignment: Alignment.centerRight,
                          child: _roundNav(Icons.arrow_forward, () {
                            if (last) {
                              context.go('/login');
                            } else {
                              _controller.nextPage(
                                duration: const Duration(milliseconds: 260),
                                curve: Curves.easeOut,
                              );
                            }
                          }),
                        ),
                      ],
                    ),
                  ),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  Widget _roundNav(IconData icon, VoidCallback onPressed) => SizedBox(
    width: 48,
    height: 48,
    child: FilledButton(
      onPressed: onPressed,
      style: FilledButton.styleFrom(
        padding: EdgeInsets.zero,
        shape: const CircleBorder(),
      ),
      child: Icon(icon),
    ),
  );

  String _progressLabel(int index) => switch (index) {
    0 => '1 / 4',
    1 => '2 / 4',
    2 => '3 / 4',
    _ => '4 / 4',
  };
}

class _IntroStartPage extends StatelessWidget {
  const _IntroStartPage();

  @override
  Widget build(BuildContext context) => Stack(
    fit: StackFit.expand,
    children: [
      const Positioned(
        top: 18,
        left: 0,
        right: 0,
        child: Center(child: _IntroProgressBadge('1 / 4')),
      ),
      const Positioned(
        top: 78,
        left: 0,
        right: 0,
        child: Column(
          children: [
            Text(
              '뚜비',
              style: TextStyle(
                fontSize: 50,
                height: .9,
                fontWeight: FontWeight.w900,
                color: Color(0xFF087A76),
                letterSpacing: 2,
              ),
            ),
            SizedBox(height: 7),
            Text(
              '뚜벅이의 비서',
              style: TextStyle(
                fontSize: 16,
                fontWeight: FontWeight.w900,
                color: Color(0xFF08A79D),
              ),
            ),
            SizedBox(height: 24),
            Text(
              '변하는 길 위에서,\n가장 좋은 출발의 순간을 알려주는 비서',
              textAlign: TextAlign.center,
              style: TextStyle(
                fontSize: 18,
                height: 1.35,
                fontWeight: FontWeight.w800,
                color: Color(0xFF181818),
              ),
            ),
          ],
        ),
      ),
      Positioned(
        bottom: 92,
        left: 18,
        right: 18,
        child: Image.asset(
          'assets/images/ttubee/welcome.png',
          height: 315,
          fit: BoxFit.contain,
        ),
      ),
      const Positioned(
        bottom: 42,
        left: 0,
        right: 0,
        child: Text(
          '좋은 출발이 좋은 하루를 만들어요.',
          textAlign: TextAlign.center,
          style: TextStyle(
            fontSize: 15,
            fontWeight: FontWeight.w900,
            color: Color(0xFF078C83),
          ),
        ),
      ),
    ],
  );
}

class _IntroProgressBadge extends StatelessWidget {
  const _IntroProgressBadge(this.label);

  final String label;

  @override
  Widget build(BuildContext context) => Container(
    width: 70,
    height: 30,
    decoration: BoxDecoration(
      color: const Color(0xFFDDF2EA),
      borderRadius: BorderRadius.circular(18),
    ),
    alignment: Alignment.center,
    child: Text(
      label,
      style: const TextStyle(
        color: Color(0xFF078C83),
        fontWeight: FontWeight.w800,
      ),
    ),
  );
}

class _SpeechBubble extends StatelessWidget {
  const _SpeechBubble(this.text);
  final String text;
  @override
  Widget build(BuildContext context) => Stack(
    clipBehavior: Clip.none,
    children: [
      Container(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 13),
        decoration: BoxDecoration(
          color: Colors.white,
          border: Border.all(color: const Color(0xFFD0D7D7)),
          borderRadius: BorderRadius.circular(30),
        ),
        child: Text(
          text,
          textAlign: TextAlign.center,
          style: const TextStyle(
            fontSize: 16,
            height: 1.2,
            fontWeight: FontWeight.w900,
            color: DdubiColors.navy,
          ),
        ),
      ),
      Positioned(
        bottom: -9,
        left: 22,
        child: Transform.rotate(
          angle: .65,
          child: Container(
            width: 18,
            height: 18,
            decoration: BoxDecoration(
              color: Colors.white,
              border: Border(
                right: BorderSide(color: Color(0xFFD0D7D7)),
                bottom: BorderSide(color: Color(0xFFD0D7D7)),
              ),
            ),
          ),
        ),
      ),
    ],
  );
}

class _MobilityCards extends StatelessWidget {
  const _MobilityCards();
  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.symmetric(horizontal: 18),
    child: LayoutBuilder(
      builder: (context, box) {
        final width = (box.maxWidth - 8) / 2;
        return Wrap(
          spacing: 8,
          runSpacing: 8,
          children: [
            _MiniStatus(
              Icons.wb_sunny_rounded,
              '날씨',
              '맑음',
              Color(0xFF1976E9),
              width,
            ),
            _MiniStatus(
              Icons.groups_rounded,
              '혼잡도',
              '보통',
              Color(0xFF078C83),
              width,
            ),
            _MiniStatus(
              Icons.train_rounded,
              '지하철',
              '일부 지연',
              Color(0xFFEF3D3D),
              width,
            ),
            _MiniStatus(
              Icons.add_road_rounded,
              '도로',
              '통제 없음',
              Color(0xFF078C83),
              width,
            ),
          ],
        );
      },
    ),
  );
}

class _MiniStatus extends StatelessWidget {
  const _MiniStatus(this.icon, this.title, this.value, this.color, this.width);
  final IconData icon;
  final String title, value;
  final Color color;
  final double width;
  @override
  Widget build(BuildContext context) => Container(
    width: width,
    height: 64,
    padding: const EdgeInsets.symmetric(horizontal: 11),
    decoration: BoxDecoration(
      color: Colors.white.withValues(alpha: .96),
      borderRadius: BorderRadius.circular(12),
      boxShadow: const [
        BoxShadow(
          color: Color(0x12003F3B),
          blurRadius: 8,
          offset: Offset(0, 3),
        ),
      ],
    ),
    child: Row(
      children: [
        Icon(icon, size: 29, color: color),
        const SizedBox(width: 9),
        Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Text(
              title,
              style: const TextStyle(
                fontSize: 13,
                fontWeight: FontWeight.w800,
                color: DdubiColors.navy,
              ),
            ),
            const SizedBox(height: 1),
            Text(
              value,
              style: TextStyle(
                fontSize: 13,
                fontWeight: FontWeight.w900,
                color: color,
              ),
            ),
          ],
        ),
      ],
    ),
  );
}

class _IntroBench extends StatelessWidget {
  const _IntroBench();

  @override
  Widget build(BuildContext context) => SizedBox(
    width: 286,
    height: 142,
    child: Stack(
      children: [
        const Positioned(left: 34, bottom: 4, child: _BenchLeg()),
        const Positioned(right: 34, bottom: 4, child: _BenchLeg()),
        Positioned(left: 12, right: 12, top: 28, child: _slat(24)),
        Positioned(left: 8, right: 8, top: 58, child: _slat(22)),
        Positioned(left: 0, right: 0, top: 88, child: _slat(18)),
      ],
    ),
  );

  Widget _slat(double height) => Container(
    height: height,
    decoration: BoxDecoration(
      gradient: const LinearGradient(
        begin: Alignment.topCenter,
        end: Alignment.bottomCenter,
        colors: [Color(0xFFF5C98F), Color(0xFFD99A57)],
      ),
      borderRadius: BorderRadius.circular(8),
      border: Border.all(color: const Color(0x33A8642B)),
      boxShadow: const [
        BoxShadow(
          color: Color(0x22003F3B),
          blurRadius: 7,
          offset: Offset(0, 4),
        ),
      ],
    ),
  );
}

class _BenchLeg extends StatelessWidget {
  const _BenchLeg();

  @override
  Widget build(BuildContext context) => Container(
    width: 17,
    height: 47,
    decoration: BoxDecoration(
      color: const Color(0xFF718083),
      borderRadius: BorderRadius.circular(5),
      boxShadow: const [
        BoxShadow(
          color: Color(0x25003F3B),
          blurRadius: 5,
          offset: Offset(0, 3),
        ),
      ],
    ),
  );
}

class _DepartureCards extends StatelessWidget {
  const _DepartureCards();
  @override
  Widget build(BuildContext context) => Row(
    mainAxisAlignment: MainAxisAlignment.center,
    children: const [
      _DepartureCard(
        Icons.schedule_rounded,
        '추천 출발',
        '17:35',
        Color(0xFF08A79D),
      ),
      SizedBox(width: 8),
      _DepartureCard(Icons.eco_rounded, '여유 출발', '17:25', Color(0xFF3C657E)),
      SizedBox(width: 8),
      _DepartureCard(Icons.flag_rounded, '도착 예상', '18:20', Color(0xFF9B53A4)),
    ],
  );
}

class _DepartureCard extends StatelessWidget {
  const _DepartureCard(this.icon, this.label, this.time, this.color);
  final IconData icon;
  final String label, time;
  final Color color;
  @override
  Widget build(BuildContext context) => Container(
    width: 104,
    height: 102,
    decoration: BoxDecoration(
      color: Colors.white.withValues(alpha: .96),
      borderRadius: BorderRadius.circular(15),
      border: Border.all(color: color.withValues(alpha: .55)),
      boxShadow: const [
        BoxShadow(
          color: Color(0x17003F3B),
          blurRadius: 10,
          offset: Offset(0, 4),
        ),
      ],
    ),
    child: Column(
      mainAxisAlignment: MainAxisAlignment.center,
      children: [
        Icon(icon, size: 20, color: color),
        const SizedBox(height: 3),
        Text(
          label,
          style: TextStyle(
            fontSize: 14,
            fontWeight: FontWeight.w800,
            color: color,
          ),
        ),
        const SizedBox(height: 4),
        Text(
          time,
          style: TextStyle(
            fontSize: 24,
            fontWeight: FontWeight.w900,
            color: color,
          ),
        ),
      ],
    ),
  );
}

class LoginScreen extends StatefulWidget {
  const LoginScreen({
    super.key,
    required this.authRepository,
    required this.kakaoLoginService,
    required this.googleLoginService,
    required this.naverLoginService,
    this.onAuthenticated,
  });
  final AuthRepository authRepository;
  final KakaoLoginService kakaoLoginService;
  final GoogleLoginService googleLoginService;
  final NaverLoginService naverLoginService;
  final VoidCallback? onAuthenticated;

  @override
  State<LoginScreen> createState() => _LoginScreenState();
}

class _LoginScreenState extends State<LoginScreen> {
  final email = TextEditingController();
  final password = TextEditingController();
  final formKey = GlobalKey<FormState>();
  bool obscure = true;
  bool loading = false;
  bool keepSignedIn = true;
  String? serverError;

  @override
  void dispose() {
    email.dispose();
    password.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext c) => Scaffold(
    backgroundColor: Colors.white,
    floatingActionButton: null,
    body: SafeArea(
      child: LayoutBuilder(
        builder: (context, box) => Stack(
          children: [
            Positioned(
              top: 0,
              left: 0,
              right: 0,
              height: box.maxHeight * .50,
              child: Stack(
                fit: StackFit.expand,
                children: [
                  Image.asset(
                    'assets/images/ttubee/intro-bg-2.png',
                    fit: BoxFit.cover,
                  ),
                  const DecoratedBox(
                    decoration: BoxDecoration(
                      gradient: LinearGradient(
                        begin: Alignment.topCenter,
                        end: Alignment.bottomCenter,
                        colors: [Color(0xBFFFFFFF), Color(0x00FFFFFF)],
                      ),
                    ),
                  ),
                  const Positioned(
                    top: 22,
                    left: 0,
                    right: 0,
                    child: Column(
                      children: [
                        Text(
                          '뚜비',
                          style: TextStyle(
                            fontSize: 38,
                            fontWeight: FontWeight.w900,
                            color: Color(0xFF087A76),
                          ),
                        ),
                        Text(
                          '뚜벅이의 비서',
                          style: TextStyle(
                            fontWeight: FontWeight.w900,
                            color: Color(0xFF08A79D),
                          ),
                        ),
                        SizedBox(height: 10),
                        Text(
                          '오늘도, 더 안전한 출발.',
                          style: TextStyle(
                            fontSize: 15,
                            fontWeight: FontWeight.w800,
                            color: Color(0xFF222222),
                          ),
                        ),
                      ],
                    ),
                  ),
                  Positioned(
                    bottom: -12,
                    left: 0,
                    right: 0,
                    child: Image.asset(
                      'assets/images/ttubee/empty-schedule.png',
                      height: 205,
                    ),
                  ),
                ],
              ),
            ),
            Positioned.fill(
              top: box.maxHeight * .50,
              child: SingleChildScrollView(
                padding: const EdgeInsets.fromLTRB(28, 12, 28, 24),
                child: Form(
                  key: formKey,
                  child: Column(
                    children: [
                      TextFormField(
                        key: const Key('loginEmailField'),
                        controller: email,
                        validator: validateEmail,
                        keyboardType: TextInputType.emailAddress,
                        autofillHints: const [AutofillHints.email],
                        textInputAction: TextInputAction.next,
                        decoration: const InputDecoration(
                          hintText: '이메일을 입력해주세요',
                          prefixIcon: Icon(Icons.mail_outline),
                        ),
                      ),
                      const SizedBox(height: 9),
                      TextFormField(
                        key: const Key('loginPasswordField'),
                        controller: password,
                        validator: validatePassword,
                        obscureText: obscure,
                        autofillHints: const [AutofillHints.password],
                        textInputAction: TextInputAction.done,
                        onFieldSubmitted: (_) => loading ? null : _submit(c),
                        decoration: InputDecoration(
                          hintText: '비밀번호를 입력해주세요',
                          prefixIcon: const Icon(Icons.lock_outline),
                          suffixIcon: IconButton(
                            onPressed: () => setState(() => obscure = !obscure),
                            icon: Icon(
                              obscure
                                  ? Icons.visibility_off_outlined
                                  : Icons.visibility_outlined,
                            ),
                          ),
                        ),
                      ),
                      const SizedBox(height: 8),
                      Align(
                        alignment: Alignment.centerLeft,
                        child: CheckboxListTile(
                          contentPadding: EdgeInsets.zero,
                          dense: true,
                          controlAffinity: ListTileControlAffinity.leading,
                          value: keepSignedIn,
                          onChanged: (value) =>
                              setState(() => keepSignedIn = value ?? true),
                          title: const Text(
                            '로그인 상태 유지',
                            style: TextStyle(
                              fontSize: 13,
                              color: DdubiColors.navy,
                            ),
                          ),
                        ),
                      ),
                      if (serverError != null)
                        Padding(
                          padding: const EdgeInsets.only(bottom: 8),
                          child: Align(
                            alignment: Alignment.centerLeft,
                            child: Text(
                              serverError!,
                              style: const TextStyle(color: DdubiColors.red),
                            ),
                          ),
                        ),
                      const SizedBox(height: 12),
                      SizedBox(
                        width: double.infinity,
                        height: 48,
                        child: FilledButton(
                          onPressed: loading ? null : () => _submit(c),
                          child: loading
                              ? const SizedBox.square(
                                  dimension: 22,
                                  child: CircularProgressIndicator(
                                    strokeWidth: 2,
                                  ),
                                )
                              : const Text(
                                  '로그인',
                                  style: TextStyle(
                                    fontSize: 16,
                                    fontWeight: FontWeight.w700,
                                  ),
                                ),
                        ),
                      ),
                      Row(
                        mainAxisAlignment: MainAxisAlignment.center,
                        children: [
                          TextButton(
                            onPressed: () => _open(
                              c,
                              const AccountRecoveryScreen(initialIndex: 0),
                            ),
                            child: const Text(
                              '아이디 찾기',
                              style: TextStyle(
                                fontSize: 12,
                                color: Color(0xFF607080),
                              ),
                            ),
                          ),
                          Container(
                            width: 1,
                            height: 12,
                            color: const Color(0xFFD7DDE0),
                          ),
                          TextButton(
                            onPressed: () => _open(
                              c,
                              const AccountRecoveryScreen(initialIndex: 1),
                            ),
                            child: const Text(
                              '비밀번호 찾기',
                              style: TextStyle(
                                fontSize: 12,
                                color: Color(0xFF607080),
                              ),
                            ),
                          ),
                        ],
                      ),
                      const SizedBox(height: 14),
                      const Row(
                        children: [
                          Expanded(child: Divider()),
                          Padding(
                            padding: EdgeInsets.symmetric(horizontal: 12),
                            child: Text(
                              '또는',
                              style: TextStyle(color: Color(0xFF77818B)),
                            ),
                          ),
                          Expanded(child: Divider()),
                        ],
                      ),
                      const SizedBox(height: 12),
                      Row(
                        mainAxisAlignment: MainAxisAlignment.center,
                        children: [
                          _socialIcon(
                            'K',
                            const Color(0xFFFFD600),
                            Colors.black,
                            () => _social(c, '카카오'),
                          ),
                          _socialIcon(
                            'G',
                            Colors.white,
                            DdubiColors.navy,
                            () => _social(c, 'Google'),
                          ),
                          _socialIcon(
                            'N',
                            const Color(0xFF03C75A),
                            Colors.white,
                            () => _social(c, '네이버'),
                          ),
                        ],
                      ),
                      TextButton(
                        onPressed: () => _openSignUp(c),
                        child: const Text(
                          '계정이 없으신가요?  회원가입',
                          style: TextStyle(color: Color(0xFF078C83)),
                        ),
                      ),
                      const SizedBox(height: 16),
                      const Row(
                        mainAxisAlignment: MainAxisAlignment.center,
                        children: [
                          Icon(
                            Icons.lock_outline,
                            size: 14,
                            color: Color(0xFF84909A),
                          ),
                          SizedBox(width: 6),
                          Text(
                            '안전한 서비스 이용을 위해 최소한의 권한만 사용합니다.',
                            style: TextStyle(
                              fontSize: 10,
                              color: Color(0xFF84909A),
                            ),
                          ),
                        ],
                      ),
                    ],
                  ),
                ),
              ),
            ),
          ],
        ),
      ),
    ),
  );
  void _completeLogin(BuildContext c) {
    final callback = widget.onAuthenticated;
    if (callback != null) {
      callback();
      return;
    }
    c.go('/home');
  }

  Future<void> _submit(BuildContext context) async {
    setState(() => serverError = null);
    if (kDebugMode &&
        email.text == 'localadmin' &&
        password.text == 'local1234') {
      _completeLogin(context);
      return;
    }
    if (!(formKey.currentState?.validate() ?? false)) return;
    setState(() => loading = true);
    try {
      await widget.authRepository.login(
        email: email.text.trim(),
        password: password.text,
      );
      if (context.mounted) _completeLogin(context);
    } on AuthException catch (error) {
      if (mounted) {
        setState(() => serverError = error.message ?? '이메일 또는 비밀번호를 확인해주세요.');
      }
    } finally {
      if (mounted) setState(() => loading = false);
    }
  }

  Future<void> _openSignUp(BuildContext context) async {
    final registeredEmail = await Navigator.push<String>(
      context,
      MaterialPageRoute(
        builder: (_) => SignUpScreen(repository: widget.authRepository),
      ),
    );
    if (registeredEmail != null && context.mounted) {
      email.text = registeredEmail;
    }
  }

  Widget _socialIcon(
    String label,
    Color color,
    Color text,
    VoidCallback onTap,
  ) => Padding(
    padding: const EdgeInsets.symmetric(horizontal: 8),
    child: Semantics(
      label: '$label 로그인',
      button: true,
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(28),
        child: Container(
          width: 48,
          height: 48,
          alignment: Alignment.center,
          decoration: BoxDecoration(
            color: color,
            shape: BoxShape.circle,
            border: Border.all(color: const Color(0xFFE0E3E5)),
          ),
          child: label == 'G'
              ? const _GoogleMark()
              : Text(
                  label,
                  style: TextStyle(
                    fontSize: 18,
                    fontWeight: FontWeight.w900,
                    color: text,
                  ),
                ),
        ),
      ),
    ),
  );
  void _social(BuildContext c, String provider) => _open(
    c,
    SocialAuthScreen(
      provider: provider,
      repository: widget.authRepository,
      kakaoLoginService: provider == '카카오' ? widget.kakaoLoginService : null,
      googleLoginService: provider == 'Google'
          ? widget.googleLoginService
          : null,
      naverLoginService: provider == '네이버' ? widget.naverLoginService : null,
      onAuthenticated: () => _completeLogin(c),
    ),
  );
}

class _GoogleMark extends StatelessWidget {
  const _GoogleMark();

  @override
  Widget build(BuildContext context) => Image.asset(
    'assets/images/auth/google_g_logo.png',
    width: 24,
    height: 24,
    fit: BoxFit.contain,
    semanticLabel: 'Google',
  );
}

class HomeScreen extends StatelessWidget {
  const HomeScreen({
    super.key,
    required this.homeApi,
    this.recommendationApi,
    this.go,
  });
  final HomeApi homeApi;
  final RecommendationApi? recommendationApi;
  final ValueChanged<int>? go;

  Future<_HomeData> _load() async {
    final summary = await homeApi.getSummary();
    RecommendationOverview? recommendation;
    final schedule = summary.nextSchedule;
    if (schedule != null && recommendationApi != null) {
      try {
        recommendation = await recommendationApi!.getLatestOverview(
          schedule.id,
        );
      } on Object {
        recommendation = null;
      }
    }
    return _HomeData(summary: summary, recommendation: recommendation);
  }

  @override
  Widget build(BuildContext c) => AppPage(
    title: '안녕하세요! 🌱',
    webTitle: '이동 대시보드',
    webSubtitle: '오늘 일정과 추천 출발 시간, 교통 상태를 한눈에 확인하세요.',
    index: go == null ? null : 2,
    onNav: go,
    actions: [
      IconButton(
        key: const Key('manageRepeatSchedulesButton'),
        tooltip: '반복 일정 관리',
        onPressed: () => c.push('/schedules/repeats'),
        icon: const Icon(Icons.event_repeat_outlined),
      ),
      IconButton(
        tooltip: '알림 보기',
        onPressed: () => c.go('/notifications'),
        icon: const Icon(Icons.notifications_none_rounded),
      ),
    ],
    child: FutureBuilder<_HomeData>(
      future: _load(),
      builder: (context, snapshot) {
        if (snapshot.hasError) {
          return const DataLoadError(message: '홈 정보를 불러오지 못했어요.');
        }
        if (!snapshot.hasData) {
          return const Center(child: CircularProgressIndicator());
        }
        final data = snapshot.data!;
        final schedule = data.summary.nextSchedule;
        final routes =
            schedule != null && data.recommendation?.hasEstimate == true
            ? [data.recommendation!.toRouteOption(schedule)]
            : const <RouteOption>[];
        return LayoutBuilder(
          builder: (context, constraints) {
            final isWide =
                constraints.maxWidth >= LayoutConstants.wideScreenBreakpoint;
            return isWide
                ? _WebHomeContent(routes: routes, summary: data.summary)
                : _MobileHomeContent(
                    schedules: data.summary.todaySchedules,
                    routes: routes,
                  );
          },
        );
      },
    ),
  );
}

class _HomeData {
  const _HomeData({required this.summary, this.recommendation});

  final HomeSummary summary;
  final RecommendationOverview? recommendation;
}

class _MobileHomeContent extends StatelessWidget {
  const _MobileHomeContent({required this.schedules, required this.routes});

  final List<Schedule> schedules;
  final List<RouteOption> routes;

  @override
  Widget build(BuildContext context) {
    final now = DateTime.now();
    final today =
        schedules
            .where(
              (schedule) =>
                  schedule.date.year == now.year &&
                  schedule.date.month == now.month &&
                  schedule.date.day == now.day,
            )
            .toList()
          ..sort((a, b) => a.date.compareTo(b.date));
    return ColoredBox(
      color: const Color(0xFFF7FBFA),
      child: ListView(
        key: const Key('todayScheduleList'),
        padding: const EdgeInsets.fromLTRB(18, 6, 18, 28),
        children: [
          const Text(
            '오늘도 안전한 이동 되세요!',
            style: TextStyle(fontSize: 14, color: Color(0xFF718497)),
          ),
          const SizedBox(height: 14),
          Container(
            height: 130,
            padding: const EdgeInsets.fromLTRB(22, 16, 8, 0),
            decoration: BoxDecoration(
              gradient: const LinearGradient(
                colors: [Color(0xFFCFF6FA), Color(0xFFE8FAE9)],
              ),
              borderRadius: BorderRadius.circular(22),
            ),
            child: Row(
              children: [
                const Expanded(
                  child: Text(
                    '오늘도\n뚜비와 함께\n좋은 하루 되세요!',
                    style: TextStyle(
                      fontSize: 22,
                      height: 1.35,
                      fontWeight: FontWeight.w900,
                      color: DdubiColors.navy,
                    ),
                  ),
                ),
                Image.asset(
                  'assets/images/ttubee/welcome.png',
                  width: 145,
                  fit: BoxFit.contain,
                ),
              ],
            ),
          ),
          const SizedBox(height: 16),
          Row(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              const Icon(Icons.chevron_left_rounded, color: Color(0xFF52709B)),
              const SizedBox(width: 14),
              Text(
                '${now.month}월 ${now.day}일 (${_weekdays[now.weekday - 1]})',
                style: const TextStyle(
                  fontSize: 18,
                  fontWeight: FontWeight.w900,
                  color: DdubiColors.navy,
                ),
              ),
              const SizedBox(width: 14),
              const Icon(Icons.chevron_right_rounded, color: Color(0xFF52709B)),
            ],
          ),
          const SizedBox(height: 14),
          Row(
            children: [
              const Expanded(
                child: Text(
                  '오늘의 일정',
                  style: TextStyle(
                    fontSize: 20,
                    fontWeight: FontWeight.w900,
                    color: DdubiColors.navy,
                  ),
                ),
              ),
              Text(
                '${today.length}',
                style: const TextStyle(
                  fontWeight: FontWeight.w900,
                  color: DdubiColors.green,
                ),
              ),
              const SizedBox(width: 10),
              OutlinedButton.icon(
                onPressed: () => context.push('/schedules/new', extra: now),
                icon: const Icon(Icons.add, size: 18),
                label: const Text('일정 추가'),
                style: OutlinedButton.styleFrom(
                  padding: const EdgeInsets.symmetric(horizontal: 12),
                  visualDensity: VisualDensity.compact,
                ),
              ),
            ],
          ),
          const SizedBox(height: 10),
          if (today.isEmpty)
            const InfoCard(
              child: Center(
                child: Padding(
                  padding: EdgeInsets.all(18),
                  child: Text('오늘 등록된 일정이 없어요.'),
                ),
              ),
            )
          else
            for (var index = 0; index < today.length; index++) ...[
              _TodayScheduleCard(
                schedule: today[index],
                routes: routes,
                index: index,
              ),
              const SizedBox(height: 10),
            ],
        ],
      ),
    );
  }
}

class _TodayScheduleCard extends StatelessWidget {
  const _TodayScheduleCard({
    required this.schedule,
    required this.routes,
    required this.index,
  });
  final Schedule schedule;
  final List<RouteOption> routes;
  final int index;

  @override
  Widget build(BuildContext context) {
    const tones = [
      Color(0xFF4D80E8),
      Color(0xFFFFA626),
      Color(0xFF8A72E8),
      Color(0xFFFF639A),
    ];
    const icons = [
      Icons.work_rounded,
      Icons.restaurant_rounded,
      Icons.menu_book_rounded,
      Icons.fitness_center_rounded,
    ];
    final tone = tones[index % tones.length];
    return Material(
      color: Colors.white,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(18),
        side: BorderSide(color: DdubiColors.green.withValues(alpha: .22)),
      ),
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        key: Key('todaySchedule-${schedule.id}'),
        onTap: () => context.push(
          '/schedules/${schedule.id}',
          extra: ScheduleDetailArguments(schedule: schedule),
        ),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 13),
          child: Row(
            children: [
              Container(
                width: 50,
                height: 50,
                decoration: BoxDecoration(
                  color: tone.withValues(alpha: .12),
                  borderRadius: BorderRadius.circular(14),
                ),
                child: Icon(icons[index % icons.length], color: tone),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      schedule.time,
                      style: const TextStyle(
                        fontSize: 13,
                        fontWeight: FontWeight.w900,
                        color: Color(0xFF1B43A6),
                      ),
                    ),
                    const SizedBox(height: 2),
                    Text(
                      schedule.title,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(
                        fontSize: 17,
                        fontWeight: FontWeight.w900,
                        color: DdubiColors.navy,
                      ),
                    ),
                    const SizedBox(height: 3),
                    Row(
                      children: [
                        const Icon(
                          Icons.location_on,
                          size: 14,
                          color: Color(0xFF8391A8),
                        ),
                        const SizedBox(width: 3),
                        Expanded(
                          child: Text(
                            schedule.place,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: const TextStyle(
                              fontSize: 12,
                              color: Color(0xFF718098),
                            ),
                          ),
                        ),
                      ],
                    ),
                  ],
                ),
              ),
              Container(
                padding: const EdgeInsets.symmetric(
                  horizontal: 10,
                  vertical: 6,
                ),
                decoration: BoxDecoration(
                  color: const Color(0xFFE8F8F2),
                  borderRadius: BorderRadius.circular(14),
                ),
                child: const Text(
                  '예정',
                  style: TextStyle(
                    fontSize: 11,
                    fontWeight: FontWeight.w800,
                    color: DdubiColors.green,
                  ),
                ),
              ),
              const SizedBox(width: 4),
              const Icon(Icons.chevron_right_rounded, color: Color(0xFF1D3F8E)),
            ],
          ),
        ),
      ),
    );
  }
}

// Retained for the desktop dashboard route cards.
// ignore: unused_element
class _HomePlayfulIcon extends StatelessWidget {
  const _HomePlayfulIcon({
    required this.icon,
    required this.color,
    this.size = 38,
  });
  final IconData icon;
  final Color color;
  final double size;
  @override
  Widget build(BuildContext context) => SizedBox(
    width: size + 8,
    height: size + 6,
    child: Stack(
      clipBehavior: Clip.none,
      children: [
        Positioned(
          bottom: 0,
          left: 6,
          right: 2,
          child: Container(
            height: 7,
            decoration: BoxDecoration(
              color: color.withValues(alpha: .12),
              borderRadius: BorderRadius.circular(20),
            ),
          ),
        ),
        Transform.rotate(
          angle: -.09,
          child: ShaderMask(
            blendMode: BlendMode.srcIn,
            shaderCallback: (bounds) => LinearGradient(
              begin: Alignment.topLeft,
              end: Alignment.bottomRight,
              colors: [
                Color.lerp(color, Colors.white, .35)!,
                color,
                Color.lerp(color, Colors.black, .15)!,
              ],
            ).createShader(bounds),
            child: Icon(icon, size: size, color: color),
          ),
        ),
        Positioned(
          right: 0,
          top: -2,
          child: Icon(
            Icons.auto_awesome_rounded,
            size: 10,
            color: color.withValues(alpha: .6),
          ),
        ),
      ],
    ),
  );
}

// ignore: unused_element
class _HomeVariable extends StatelessWidget {
  const _HomeVariable({
    required this.icon,
    required this.label,
    required this.tone,
  });
  final IconData icon;
  final String label;
  final Color tone;
  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.symmetric(horizontal: 5, vertical: 14),
    decoration: BoxDecoration(
      color: tone,
      borderRadius: BorderRadius.circular(16),
    ),
    child: Column(
      children: [
        _HomePlayfulIcon(
          icon: icon,
          color: switch (label) {
            '날씨' => const Color(0xFFF4B829),
            '혼잡도' => const Color(0xFF00AE79),
            '도로 통제' => const Color(0xFFFA716C),
            _ => const Color(0xFF00AB8A),
          },
        ),
        const SizedBox(height: 9),
        Text(
          label,
          textAlign: TextAlign.center,
          style: const TextStyle(fontSize: 11, fontWeight: FontWeight.w700),
        ),
        const SizedBox(height: 5),
        const Text(
          '확인 필요',
          style: TextStyle(fontSize: 11, color: Color(0xFF77868B)),
        ),
      ],
    ),
  );
}

// ignore: unused_element
class _HomeRouteTile extends StatelessWidget {
  const _HomeRouteTile({required this.route, required this.index});
  final RouteOption route;
  final int index;
  @override
  Widget build(BuildContext context) {
    final tone = [
      const Color(0xFF009B75),
      const Color(0xFF347FD6),
      const Color(0xFF8261D9),
    ][index % 3];
    return Material(
      color: Color.lerp(Colors.white, tone, .035),
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(20),
        side: BorderSide(color: tone.withValues(alpha: .3)),
      ),
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: () => _showHomeRouteDetails(context, [route]),
        child: Padding(
          padding: const EdgeInsets.all(12),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              _HomePlayfulIcon(
                icon: [
                  Icons.workspace_premium_rounded,
                  Icons.bolt_rounded,
                  Icons.verified_user_rounded,
                ][index % 3],
                color: tone,
                size: 32,
              ),
              const SizedBox(height: 8),
              Text(
                route.label,
                style: TextStyle(
                  fontSize: 12,
                  fontWeight: FontWeight.w800,
                  color: tone,
                ),
              ),
              const SizedBox(height: 8),
              Text(
                route.transport,
                style: const TextStyle(
                  fontSize: 14,
                  fontWeight: FontWeight.w800,
                  color: DdubiColors.navy,
                ),
              ),
              const SizedBox(height: 7),
              Text(
                '${route.departure} → ${route.arrival}',
                style: const TextStyle(fontSize: 11, color: Color(0xFF5E7180)),
              ),
              const SizedBox(height: 6),
              Text(
                route.margin,
                style: TextStyle(
                  fontSize: 17,
                  fontWeight: FontWeight.w800,
                  color: index == 1 ? const Color(0xFFE46C60) : tone,
                ),
              ),
              const SizedBox(height: 7),
              Text(
                route.detail,
                style: const TextStyle(fontSize: 10, color: Color(0xFF72818A)),
              ),
              const SizedBox(height: 4),
              Text(
                route.fare,
                style: const TextStyle(
                  fontSize: 15,
                  fontWeight: FontWeight.w800,
                  color: DdubiColors.navy,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

void _showHomeRouteDetails(BuildContext context, List<RouteOption> routes) {
  showModalBottomSheet<void>(
    context: context,
    showDragHandle: true,
    isScrollControlled: true,
    backgroundColor: Colors.white,
    builder: (context) => SafeArea(
      child: SingleChildScrollView(
        padding: const EdgeInsets.fromLTRB(24, 4, 24, 24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text(
              '추천 출발 상세',
              style: TextStyle(
                fontSize: 23,
                fontWeight: FontWeight.w800,
                color: DdubiColors.navy,
              ),
            ),
            const SizedBox(height: 8),
            const Text(
              '현재 홈에 표시된 경로를 비교해보세요.',
              style: TextStyle(color: Color(0xFF6D7C88)),
            ),
            const SizedBox(height: 18),
            if (routes.isEmpty) const Text('예정된 일정이 없어요. 목적지를 설정해 경로를 찾아보세요.'),
            for (final route in routes)
              Padding(
                padding: const EdgeInsets.only(bottom: 16),
                child: ListTile(
                  contentPadding: EdgeInsets.zero,
                  leading: const Icon(
                    Icons.route_rounded,
                    color: Color(0xFF009B83),
                  ),
                  title: Text('${route.label} · ${route.transport}'),
                  subtitle: Text(
                    '${route.departure} 출발 → ${route.arrival} 도착\n${route.detail} · ${route.fare}',
                  ),
                  isThreeLine: true,
                ),
              ),
            SizedBox(
              width: double.infinity,
              child: FilledButton(
                onPressed: () {
                  Navigator.pop(context);
                  context.go('/recommendations');
                },
                child: const Text('내 목적지로 경로 찾기'),
              ),
            ),
          ],
        ),
      ),
    ),
  );
}

class _WebHomeContent extends StatelessWidget {
  const _WebHomeContent({required this.routes, required this.summary});

  final List<RouteOption> routes;
  final HomeSummary summary;

  @override
  Widget build(BuildContext context) => ListView(
    key: const Key('webHomeDashboard'),
    padding: const EdgeInsets.fromLTRB(24, 20, 24, 32),
    children: [
      Row(
        key: const Key('webHomeMetrics'),
        children: [
          Expanded(
            child: DashboardMetricCard(
              label: '다음 일정',
              value: summary.nextSchedule?.time ?? '--:--',
              caption: summary.nextSchedule?.title ?? '예정된 일정 없음',
              icon: Icons.event_available_outlined,
            ),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: DashboardMetricCard(
              label: '추천 출발',
              value: _primaryRoute(routes)?.departure ?? '--:--',
              caption: _primaryRoute(routes)?.margin ?? '경로 탐색 필요',
              icon: Icons.directions_run_outlined,
              tone: DdubiColors.green,
            ),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: DashboardMetricCard(
              label: '도착 예정',
              value: _primaryRoute(routes)?.arrival ?? '--:--',
              caption: _primaryRoute(routes)?.detail ?? '경로 탐색 필요',
              icon: Icons.flag_outlined,
              tone: Color(0xFF7257E8),
            ),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: DashboardMetricCard(
              label: '데이터 상태',
              value: summary.dataState,
              caption: '안 읽은 알림 ${summary.unreadNotificationCount}건',
              icon: Icons.check_circle_outline,
              tone: DdubiColors.green,
            ),
          ),
        ],
      ),
      const SizedBox(height: 20),
      Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Expanded(
            flex: 3,
            child: _HomeScheduleCard(
              schedule: summary.nextSchedule,
              previewRoutes: routes,
            ),
          ),
          const SizedBox(width: 16),
          Expanded(
            flex: 2,
            child: Column(
              children: [
                TtubeeGuideCard(
                  _primaryRoute(routes) == null
                      ? '다음 일정을 등록하면 출발 시간을 챙겨드릴게요.'
                      : '${_primaryRoute(routes)!.departure}에 출발하면 안전해요!\n'
                            '${_primaryRoute(routes)!.margin}',
                  compact: true,
                ),
                const SizedBox(height: 12),
                InfoCard(
                  child: Row(
                    children: [
                      Expanded(
                        child: _HomeMetric(
                          '도착 예정',
                          _primaryRoute(routes)?.arrival ?? '--:--',
                        ),
                      ),
                      Expanded(
                        child: _HomeMetric(
                          '이동 정보',
                          _primaryRoute(routes)?.detail ?? '-',
                        ),
                      ),
                      Expanded(
                        child: _HomeMetric(
                          '여유 시간',
                          _primaryRoute(routes)?.margin ?? '-',
                        ),
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
      const SizedBox(height: 28),
      SectionHeader(
        '추천 경로',
        trailing: Text(
          '가장 알맞은 경로 하나를 안내해요',
          style: Theme.of(context).textTheme.bodySmall,
        ),
      ),
      const SizedBox(height: 12),
      GridView.builder(
        key: const Key('webHomeRouteGrid'),
        shrinkWrap: true,
        physics: const NeverScrollableScrollPhysics(),
        itemCount: routes.isEmpty ? 0 : 1,
        gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
          crossAxisCount: 3,
          crossAxisSpacing: 12,
          mainAxisExtent: 220,
        ),
        itemBuilder: (_, i) => RouteCard(
          _primaryRoute(routes)!,
          compact: true,
          fillWidth: true,
          onTap: summary.nextSchedule == null
              ? null
              : () => context.push('/schedules/${summary.nextSchedule!.id}'),
        ),
      ),
    ],
  );
}

class _HomeScheduleCard extends StatelessWidget {
  const _HomeScheduleCard({
    required this.schedule,
    this.previewRoutes = const [],
  });

  final Schedule? schedule;
  final List<RouteOption> previewRoutes;

  @override
  Widget build(BuildContext context) => InfoCard(
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const Text(
          '다음 중요 일정',
          style: TextStyle(
            fontSize: 14,
            fontWeight: FontWeight.w700,
            color: DdubiColors.navy,
          ),
        ),
        const SizedBox(height: 8),
        Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    schedule?.time ?? '--:--',
                    style: Theme.of(context).textTheme.headlineLarge,
                  ),
                  Text(
                    schedule?.title ?? '예정된 일정이 없어요',
                    style: Theme.of(context).textTheme.titleLarge,
                  ),
                  const SizedBox(height: 6),
                  Text(
                    schedule == null
                        ? '새 일정을 등록하고 이동을 준비해요'
                        : '일정에 맞춰 여유롭게 준비하세요',
                    style: const TextStyle(
                      fontSize: 12,
                      color: Color(0xFF6D7C88),
                    ),
                  ),
                ],
              ),
            ),
            Image.asset(
              'assets/images/ttubee/welcome.png',
              width: MediaQuery.sizeOf(context).width < 400 ? 124 : 160,
              height: 154,
            ),
          ],
        ),
        const Divider(height: 16),
        const Text('추천 출발 시간', style: TextStyle(fontSize: 12)),
        Row(
          children: [
            Text(
              previewRoutes.isEmpty ? '17:57' : previewRoutes.first.departure,
              style: Theme.of(context).textTheme.headlineMedium
                  ?.copyWith(color: DdubiColors.blue),
            ),
            const Spacer(),
            OutlinedButton(
              onPressed: schedule == null
                  ? () => _showHomeRouteDetails(context, previewRoutes)
                  : () => context.push('/schedules/${schedule!.id}'),
              child: const Text('상세 보기'),
            ),
          ],
        ),
      ],
    ),
  );
}

class _HomeMetric extends StatelessWidget {
  const _HomeMetric(this.label, this.value);

  final String label;
  final String value;

  @override
  Widget build(BuildContext context) => Column(
    children: [
      Text(label, style: Theme.of(context).textTheme.bodySmall),
      const SizedBox(height: 5),
      Text(value, style: Theme.of(context).textTheme.titleMedium),
    ],
  );
}

class ScheduleListScreen extends StatefulWidget {
  const ScheduleListScreen({super.key, required this.repository, this.go});
  final ScheduleRepository repository;
  final ValueChanged<int>? go;
  @override
  State<ScheduleListScreen> createState() => _ScheduleListScreenState();
}

class _ScheduleListScreenState extends State<ScheduleListScreen> {
  DateTime selectedDate = DateTime.now();
  Future<void> _add(BuildContext context) async {
    final saved = await context.push<bool>(
      '/schedules/new',
      extra: selectedDate,
    );
    if (saved == true && mounted) setState(() {});
  }

  Future<void> _addRepeat(BuildContext context) async {
    final saved = await context.push<bool>(
      '/schedules/repeat/new',
      extra: selectedDate,
    );
    if (saved == true && mounted) setState(() {});
  }

  Future<void> _showConflicts(BuildContext context) async {
    final from = DateTime(selectedDate.year, selectedDate.month, 1);
    final to = DateTime(selectedDate.year, selectedDate.month + 1, 1);
    try {
      final conflicts = await widget.repository.conflicts(from, to);
      if (!context.mounted) return;
      await showDialog<void>(
        context: context,
        builder: (dialogContext) => AlertDialog(
          title: const Text('연속 일정 충돌'),
          content: conflicts.isEmpty
              ? const Text('이동 시간이 부족한 일정 조합이 없어요.')
              : Column(
                  mainAxisSize: MainAxisSize.min,
                  children: conflicts
                      .map(
                        (conflict) => ListTile(
                          leading: Icon(
                            conflict.severity == 'CRITICAL'
                                ? Icons.error_outline
                                : Icons.warning_amber_outlined,
                            color: conflict.severity == 'CRITICAL'
                                ? DdubiColors.red
                                : DdubiColors.orange,
                          ),
                          title: Text('${conflict.shortageMin}분 부족'),
                          subtitle: Text(
                            '일정 ${conflict.previousScheduleId} → ${conflict.nextScheduleId}',
                          ),
                        ),
                      )
                      .toList(growable: false),
                ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(dialogContext),
              child: const Text('확인'),
            ),
          ],
        ),
      );
    } on Object {
      if (!context.mounted) return;
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('일정 충돌을 확인하지 못했어요.')));
    }
  }

  @override
  Widget build(BuildContext c) => AppPage(
    title: '전체 일정',
    webTitle: '일정 대시보드',
    webSubtitle: '월간 일정과 선택한 날짜의 이동 계획을 함께 관리하세요.',
    index: widget.go == null ? null : 0,
    onNav: widget.go,
    actions: [
      IconButton(
        tooltip: '연속 일정 충돌 확인',
        onPressed: () => _showConflicts(c),
        icon: const Icon(Icons.warning_amber_outlined),
      ),
      IconButton(
        key: const Key('addRepeatScheduleButton'),
        tooltip: '반복 일정 추가',
        onPressed: () => _addRepeat(c),
        icon: const Icon(Icons.repeat_rounded),
      ),
    ],
    fab: FloatingActionButton(
      onPressed: () => _add(c),
      child: const Icon(Icons.add),
    ),
    child: FutureBuilder<List<Schedule>>(
      future: widget.repository.schedules(),
      builder: (c, s) {
        if (s.hasError) {
          return const DataLoadError(message: '일정을 불러오지 못했어요.');
        }
        final schedules = s.data ?? [];
        final selectedSchedules = schedules
            .where(
              (item) =>
                  item.date.year == selectedDate.year &&
                  item.date.month == selectedDate.month &&
                  item.date.day == selectedDate.day,
            )
            .toList();
        final calendar = MonthlyCalendar(
          selectedDate: selectedDate,
          schedules: schedules,
          onSelected: (date) => setState(() => selectedDate = date),
        );
        final dayPanel = _ScheduleDayPanel(
          selectedDate: selectedDate,
          schedules: selectedSchedules,
          isLoading: !s.hasData,
          onAdd: () => _add(c),
        );
        return LayoutBuilder(
          builder: (context, constraints) {
            final isWide =
                constraints.maxWidth >= LayoutConstants.wideScreenBreakpoint;
            if (isWide) {
              return SingleChildScrollView(
                key: const Key('webScheduleLayout'),
                padding: const EdgeInsets.fromLTRB(24, 20, 24, 32),
                child: Column(
                  children: [
                    Row(
                      key: const Key('webScheduleMetrics'),
                      children: [
                        Expanded(
                          child: DashboardMetricCard(
                            label: '전체 일정',
                            value: '${schedules.length}개',
                            caption: '이번 달 등록 기준',
                            icon: Icons.calendar_month_outlined,
                          ),
                        ),
                        const SizedBox(width: 12),
                        Expanded(
                          child: DashboardMetricCard(
                            label: '선택 날짜',
                            value:
                                '${selectedDate.month}월 ${selectedDate.day}일',
                            caption: '${selectedSchedules.length}개의 일정',
                            icon: Icons.today_outlined,
                            tone: DdubiColors.green,
                          ),
                        ),
                        const SizedBox(width: 12),
                        const Expanded(
                          child: DashboardMetricCard(
                            label: '반복 일정',
                            value: '평일',
                            caption: '출근 일정 활성화',
                            icon: Icons.repeat_rounded,
                            tone: Color(0xFF7257E8),
                          ),
                        ),
                      ],
                    ),
                    const SizedBox(height: 20),
                    Row(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Expanded(
                          child: MonthlyCalendar(
                            selectedDate: selectedDate,
                            schedules: schedules,
                            expanded: true,
                            onSelected: (date) =>
                                setState(() => selectedDate = date),
                          ),
                        ),
                        const SizedBox(width: 20),
                        SizedBox(width: 360, child: dayPanel),
                      ],
                    ),
                  ],
                ),
              );
            }
            return ListView(
              key: const Key('mobileScheduleLayout'),
              padding: _pad,
              children: [calendar, const SizedBox(height: 18), dayPanel],
            );
          },
        );
      },
    ),
  );
}

class _ScheduleDayPanel extends StatelessWidget {
  const _ScheduleDayPanel({
    required this.selectedDate,
    required this.schedules,
    required this.isLoading,
    required this.onAdd,
  });

  final DateTime selectedDate;
  final List<Schedule> schedules;
  final bool isLoading;
  final VoidCallback onAdd;

  @override
  Widget build(BuildContext context) => Column(
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      SectionHeader(
        '${selectedDate.month}월 ${selectedDate.day}일 일정',
        trailing: TextButton.icon(
          onPressed: onAdd,
          icon: const Icon(Icons.add),
          label: const Text('일정 추가'),
        ),
      ),
      const SizedBox(height: 8),
      if (isLoading)
        const Center(child: CircularProgressIndicator())
      else if (schedules.isEmpty)
        InfoCard(
          color: const Color(0xFFF4FCFA),
          child: Column(
            children: [
              const TtubeeIdle(
                asset: 'assets/images/ttubee/empty-schedule.png',
              ),
              const SizedBox(height: 8),
              Text(
                '아직 등록된 일정이 없어요.',
                style: Theme.of(context).textTheme.titleMedium,
              ),
              const SizedBox(height: 4),
              const Text('뚜비에게 중요한 일정을 알려주세요!'),
              const SizedBox(height: 14),
              FilledButton.icon(
                onPressed: onAdd,
                icon: const Icon(Icons.add),
                label: const Text('일정 등록하기'),
              ),
            ],
          ),
        )
      else
        ...schedules.map(
          (schedule) => Padding(
            padding: const EdgeInsets.only(bottom: 10),
            child: _ScheduleSummaryCard(schedule: schedule),
          ),
        ),
    ],
  );
}

class _ScheduleSummaryCard extends StatelessWidget {
  const _ScheduleSummaryCard({required this.schedule});

  final Schedule schedule;

  @override
  Widget build(BuildContext context) => Card(
    child: InkWell(
      borderRadius: BorderRadius.circular(18),
      onTap: () => context.push('/schedules/${schedule.id}'),
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Row(
          children: [
            Container(
              width: 58,
              padding: const EdgeInsets.symmetric(vertical: 10),
              alignment: Alignment.center,
              decoration: BoxDecoration(
                color: DdubiColors.blue.withValues(alpha: .1),
                borderRadius: BorderRadius.circular(12),
              ),
              child: Text(
                schedule.time,
                style: const TextStyle(
                  color: DdubiColors.blue,
                  fontWeight: FontWeight.w800,
                ),
              ),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    schedule.title,
                    style: Theme.of(context).textTheme.titleMedium,
                  ),
                  const SizedBox(height: 4),
                  Row(
                    children: [
                      const Icon(
                        Icons.location_on_outlined,
                        size: 15,
                        color: Color(0xFF667085),
                      ),
                      const SizedBox(width: 3),
                      Expanded(
                        child: Text(
                          schedule.repeatLabel == null
                              ? schedule.place
                              : '${schedule.place} · ${schedule.repeatLabel}',
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: Theme.of(context).textTheme.bodySmall,
                        ),
                      ),
                    ],
                  ),
                ],
              ),
            ),
            const Icon(Icons.chevron_right, color: Color(0xFF98A2B3)),
          ],
        ),
      ),
    ),
  );
}

class ScheduleFormScreen extends StatefulWidget {
  const ScheduleFormScreen({
    super.key,
    required this.repository,
    required this.placeSearchService,
    this.initialDate,
    this.schedule,
  });
  final ScheduleRepository repository;
  final PlaceSearchService placeSearchService;
  final DateTime? initialDate;
  final Schedule? schedule;
  @override
  State<ScheduleFormScreen> createState() => _ScheduleFormScreenState();
}

class RepeatScheduleFormScreen extends StatefulWidget {
  const RepeatScheduleFormScreen({
    super.key,
    required this.repository,
    required this.placeSearchService,
    this.initialDate,
    this.rule,
  });

  final RepeatScheduleRepository repository;
  final PlaceSearchService placeSearchService;
  final DateTime? initialDate;
  final RepeatScheduleRule? rule;

  @override
  State<RepeatScheduleFormScreen> createState() =>
      _RepeatScheduleFormScreenState();
}

class _RepeatScheduleFormScreenState extends State<RepeatScheduleFormScreen> {
  final formKey = GlobalKey<FormState>();
  final title = TextEditingController();
  final time = TextEditingController(text: '09:00');
  final weekdays = <int>{1, 2, 3, 4, 5};
  PlaceLocation? origin;
  PlaceLocation? destination;

  late DateTime startDate;
  late DateTime endDate;
  bool hasEndDate = false;
  bool considerLastTrip = false;
  ScheduleImportance importance = ScheduleImportance.high;
  TransportPreferenceChoice transport = TransportPreferenceChoice.balanced;

  @override
  void initState() {
    super.initState();
    final rule = widget.rule;
    title.text = rule?.title ?? '';
    time.text = rule?.time ?? '09:00';
    origin = rule?.origin;
    destination = rule?.destination;
    weekdays
      ..clear()
      ..addAll(rule?.weekdays ?? const {1, 2, 3, 4, 5});
    startDate = rule?.startDate ?? widget.initialDate ?? DateTime.now();
    endDate = rule?.untilDate ?? startDate.add(const Duration(days: 84));
    hasEndDate = rule?.untilDate != null;
    considerLastTrip = rule?.considerLastTrip ?? false;
    importance = rule?.importance ?? ScheduleImportance.high;
    transport = rule?.preferredTransport ?? TransportPreferenceChoice.balanced;
  }

  @override
  void dispose() {
    title.dispose();
    time.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AppPage(
    title: widget.rule == null ? '반복 일정 등록' : '반복 일정 수정',
    child: Form(
      key: formKey,
      child: LayoutBuilder(
        builder: (context, constraints) {
          final isWide = constraints.maxWidth >= 760;
          final basic = _basicCard(context);
          final repeat = _repeatCard(context);
          final preference = _preferenceCard(context);
          return ListView(
            padding: _pad,
            children: [
              const TtubeeGuideCard(
                '출근이나 수업처럼 정해진 요일에 반복되는 일정을 한 번에 등록해요.',
                compact: true,
              ),
              const SizedBox(height: 16),
              if (isWide)
                Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Expanded(child: basic),
                    const SizedBox(width: 16),
                    Expanded(
                      child: Column(
                        children: [
                          repeat,
                          const SizedBox(height: 16),
                          preference,
                        ],
                      ),
                    ),
                  ],
                )
              else ...[
                basic,
                const SizedBox(height: 12),
                repeat,
                const SizedBox(height: 12),
                preference,
              ],
              const SizedBox(height: 16),
              PrimaryButton(
                widget.rule == null ? '반복 일정 저장하기' : '반복 일정 수정하기',
                onPressed: _save,
              ),
              const SizedBox(height: 24),
            ],
          );
        },
      ),
    ),
  );

  Widget _basicCard(BuildContext context) => InfoCard(
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text('일정 정보', style: Theme.of(context).textTheme.titleMedium),
        const SizedBox(height: 16),
        _field('일정 제목', title, key: const Key('repeatTitleField')),
        PlaceSearchField(
          key: const Key('repeatPlaceField'),
          label: '도착지',
          value: destination,
          searchService: widget.placeSearchService,
          onSelected: (value) => setState(() => destination = value),
        ),
        _field(
          '반드시 도착해야 하는 시간',
          time,
          key: const Key('repeatTimeField'),
          icon: Icons.schedule,
          timeField: true,
        ),
        PlaceSearchField(
          key: const Key('repeatDepartureField'),
          label: '출발지',
          value: origin,
          searchService: widget.placeSearchService,
          onSelected: (value) => setState(() => origin = value),
        ),
      ],
    ),
  );

  Widget _repeatCard(BuildContext context) => InfoCard(
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text('반복 설정', style: Theme.of(context).textTheme.titleMedium),
        const SizedBox(height: 16),
        const Text('반복 요일', style: TextStyle(fontWeight: FontWeight.w700)),
        const SizedBox(height: 8),
        Wrap(
          spacing: 6,
          runSpacing: 6,
          children: List.generate(7, (index) {
            final weekday = index + 1;
            return FilterChip(
              key: Key('repeatWeekday$weekday'),
              label: Text(_weekdays[index]),
              selected: weekdays.contains(weekday),
              onSelected: (selected) => setState(() {
                selected ? weekdays.add(weekday) : weekdays.remove(weekday);
              }),
            );
          }),
        ),
        if (weekdays.isEmpty) ...[
          const SizedBox(height: 8),
          const Text(
            '반복할 요일을 하나 이상 선택해주세요.',
            style: TextStyle(color: DdubiColors.red, fontSize: 12),
          ),
        ],
        const SizedBox(height: 18),
        _dateRow(
          context,
          label: '시작일',
          date: startDate,
          key: const Key('repeatStartDateButton'),
          onPressed: () => _pickStartDate(context),
        ),
        const SizedBox(height: 14),
        SwitchListTile(
          contentPadding: EdgeInsets.zero,
          title: const Text('종료일 설정'),
          subtitle: Text(hasEndDate ? _fullDate(endDate) : '종료일 없음'),
          value: hasEndDate,
          onChanged: (value) => setState(() => hasEndDate = value),
        ),
        if (hasEndDate)
          _dateRow(
            context,
            label: '종료일',
            date: endDate,
            key: const Key('repeatEndDateButton'),
            onPressed: () => _pickEndDate(context),
          ),
      ],
    ),
  );

  Widget _preferenceCard(BuildContext context) => InfoCard(
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text('이동 설정', style: Theme.of(context).textTheme.titleMedium),
        const SizedBox(height: 16),
        const Text('중요도', style: TextStyle(fontWeight: FontWeight.w700)),
        const SizedBox(height: 8),
        SegmentedButton<ScheduleImportance>(
          segments: const [
            ButtonSegment(value: ScheduleImportance.normal, label: Text('일반')),
            ButtonSegment(value: ScheduleImportance.high, label: Text('중요')),
            ButtonSegment(
              value: ScheduleImportance.critical,
              label: Text('절대 지각 불가'),
            ),
          ],
          selected: {importance},
          onSelectionChanged: (value) =>
              setState(() => importance = value.first),
        ),
        const SizedBox(height: 12),
        SwitchListTile(
          key: const Key('repeatConsiderLastTripSwitch'),
          contentPadding: EdgeInsets.zero,
          title: const Text('막차 고려'),
          subtitle: const Text('늦은 반복 일정의 막차 위험을 함께 확인해요.'),
          value: considerLastTrip,
          onChanged: (value) => setState(() => considerLastTrip = value),
        ),
        const SizedBox(height: 18),
        const Text('교통수단 선호', style: TextStyle(fontWeight: FontWeight.w700)),
        TransportPreferenceSelector(
          value: transport,
          onChanged: (value) => setState(() => transport = value),
        ),
      ],
    ),
  );

  Widget _field(
    String label,
    TextEditingController controller, {
    required Key key,
    IconData? icon,
    bool timeField = false,
  }) => Padding(
    padding: const EdgeInsets.only(bottom: 15),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(label, style: const TextStyle(fontWeight: FontWeight.w700)),
        const SizedBox(height: 6),
        TextFormField(
          key: key,
          controller: controller,
          keyboardType: timeField ? TextInputType.datetime : TextInputType.text,
          validator: (value) {
            final text = (value ?? '').trim();
            if (text.isEmpty) return '필수 입력 항목입니다.';
            if (timeField &&
                !RegExp(r'^([01]\d|2[0-3]):[0-5]\d$').hasMatch(text)) {
              return '시간을 HH:mm 형식으로 입력해주세요.';
            }
            return null;
          },
          decoration: InputDecoration(
            suffixIcon: icon == null ? null : Icon(icon),
          ),
        ),
      ],
    ),
  );

  Widget _dateRow(
    BuildContext context, {
    required String label,
    required DateTime date,
    required Key key,
    required VoidCallback onPressed,
  }) => Row(
    children: [
      Expanded(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(label, style: const TextStyle(fontWeight: FontWeight.w700)),
            Text(_fullDate(date)),
          ],
        ),
      ),
      OutlinedButton.icon(
        key: key,
        onPressed: onPressed,
        icon: const Icon(Icons.calendar_month_outlined),
        label: const Text('변경'),
      ),
    ],
  );

  Future<void> _pickStartDate(BuildContext context) async {
    final selected = await showDatePicker(
      context: context,
      initialDate: startDate,
      firstDate: DateTime.now().subtract(const Duration(days: 1)),
      lastDate: DateTime.now().add(const Duration(days: 365 * 2)),
    );
    if (selected == null || !mounted) return;
    setState(() {
      startDate = selected;
      if (endDate.isBefore(startDate)) {
        endDate = startDate.add(const Duration(days: 84));
      }
    });
  }

  Future<void> _pickEndDate(BuildContext context) async {
    final selected = await showDatePicker(
      context: context,
      initialDate: endDate.isBefore(startDate) ? startDate : endDate,
      firstDate: startDate,
      lastDate: startDate.add(const Duration(days: 365 * 2)),
    );
    if (selected != null && mounted) setState(() => endDate = selected);
  }

  Future<void> _save() async {
    setState(() {});
    if (weekdays.isEmpty ||
        origin == null ||
        destination == null ||
        !(formKey.currentState?.validate() ?? false)) {
      return;
    }
    try {
      final rule = RepeatScheduleRule(
        id:
            widget.rule?.id ??
            'repeat-${DateTime.now().microsecondsSinceEpoch}',
        title: title.text.trim(),
        origin: origin!,
        destination: destination!,
        time: time.text.trim(),
        startDate: startDate,
        weekdays: {...weekdays},
        untilDate: hasEndDate ? endDate : null,
        importance: importance,
        considerLastTrip: considerLastTrip,
        preferredTransport: transport,
      );
      if (widget.rule == null) {
        await widget.repository.addRepeatSchedule(rule);
      } else {
        await widget.repository.updateRepeatSchedule(rule);
      }
      if (mounted) context.pop(true);
    } on Object {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('반복 일정을 저장하지 못했어요. 연결 상태를 확인해주세요.')),
      );
    }
  }
}

class _ScheduleFormScreenState extends State<ScheduleFormScreen> {
  late ScheduleImportance importance;
  TransportPreferenceChoice transport = TransportPreferenceChoice.balanced;
  late bool considerLastTrip;
  late DateTime departureDate;
  late DateTime arrivalDate;
  late TimeOfDay departureTime;
  late TimeOfDay arrivalTime;
  final formKey = GlobalKey<FormState>();
  late final TextEditingController title;
  PlaceLocation? origin;
  PlaceLocation? destination;

  @override
  void initState() {
    super.initState();
    final schedule = widget.schedule;
    title = TextEditingController(text: schedule?.title ?? '');
    final arriveBy =
        schedule?.arriveBy ??
        DateTime(
          (widget.initialDate ?? DateTime.now()).year,
          (widget.initialDate ?? DateTime.now()).month,
          (widget.initialDate ?? DateTime.now()).day,
          19,
          30,
        );
    final departAt =
        schedule?.plannedDepartAt ??
        arriveBy.subtract(const Duration(hours: 1));
    arrivalDate = arriveBy;
    arrivalTime = TimeOfDay.fromDateTime(arriveBy);
    departureDate = departAt;
    departureTime = TimeOfDay.fromDateTime(departAt);
    origin = schedule?.origin;
    destination = schedule?.destination;
    importance = schedule?.importance ?? ScheduleImportance.high;
    considerLastTrip = schedule?.considerLastTrip ?? false;
    transport =
        schedule?.preferredTransport ?? TransportPreferenceChoice.balanced;
  }

  @override
  void dispose() {
    title.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    backgroundColor: const Color(0xFFF2FBF9),
    appBar: AppBar(
      backgroundColor: const Color(0xFFF2FBF9),
      surfaceTintColor: Colors.transparent,
      centerTitle: true,
      leading: IconButton(
        key: const Key('scheduleFormBackButton'),
        tooltip: '뒤로가기',
        onPressed: () => _leaveForm(context),
        icon: const Icon(
          Icons.arrow_back_ios_new_rounded,
          color: DdubiColors.navy,
          size: 22,
        ),
      ),
      title: Text(widget.schedule == null ? '일정 등록' : '일정 수정'),
    ),
    body: SafeArea(
      top: false,
      child: Form(
        key: formKey,
        child: ListView(
          key: const Key('scheduleFormScrollView'),
          padding: const EdgeInsets.fromLTRB(16, 4, 16, 28),
          children: [
            _hero(),
            const SizedBox(height: 14),
            Container(
              padding: const EdgeInsets.fromLTRB(16, 20, 16, 18),
              decoration: BoxDecoration(
                color: Colors.white,
                borderRadius: BorderRadius.circular(28),
                boxShadow: const [
                  BoxShadow(
                    color: Color(0x12004F4A),
                    blurRadius: 24,
                    offset: Offset(0, 8),
                  ),
                ],
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  _field('일정 제목', title, icon: Icons.edit_calendar_rounded),
                  PlaceSearchField(
                    key: const Key('originPlaceField'),
                    label: '출발지',
                    value: origin,
                    searchService: widget.placeSearchService,
                    onSelected: (value) => setState(() => origin = value),
                  ),
                  _dateTimeSection(
                    label: '출발 예정 시간',
                    icon: Icons.schedule_rounded,
                    date: departureDate,
                    time: departureTime,
                    dateKey: const Key('scheduleDepartureDateButton'),
                    timeKey: const Key('scheduleDepartureTimeButton'),
                    onDatePressed: () => _pickDate(context, arrival: false),
                    onTimePressed: () => _pickTime(context, arrival: false),
                  ),
                  const SizedBox(height: 15),
                  PlaceSearchField(
                    key: const Key('destinationPlaceField'),
                    label: '도착지',
                    value: destination,
                    searchService: widget.placeSearchService,
                    onSelected: (value) => setState(() => destination = value),
                  ),
                  _dateTimeSection(
                    label: '도착 희망 시간',
                    icon: Icons.flag_rounded,
                    date: arrivalDate,
                    time: arrivalTime,
                    dateKey: const Key('scheduleDateButton'),
                    timeKey: const Key('scheduleArrivalTimeButton'),
                    onDatePressed: () => _pickDate(context, arrival: true),
                    onTimePressed: () => _pickTime(context, arrival: true),
                  ),
                ],
              ),
            ),
            const SizedBox(height: 14),
            _lastTripCard(),
            const SizedBox(height: 12),
            _infoCard(),
            const SizedBox(height: 16),
            SizedBox(
              height: 56,
              child: PrimaryButton(
                widget.schedule == null ? '저장하기' : '수정 완료',
                onPressed: _save,
              ),
            ),
          ],
        ),
      ),
    ),
  );

  Widget _hero() => Container(
    height: 210,
    clipBehavior: Clip.antiAlias,
    decoration: BoxDecoration(
      gradient: const LinearGradient(
        colors: [Color(0xFFE6FBF6), Color(0xFFD6F5F1)],
        begin: Alignment.topLeft,
        end: Alignment.bottomRight,
      ),
      borderRadius: BorderRadius.circular(28),
    ),
    child: LayoutBuilder(
      builder: (context, constraints) => Stack(
        children: [
          const Positioned(
            left: -22,
            bottom: -32,
            child: _ScheduleHeroBubble(size: 110),
          ),
          const Positioned(
            right: 78,
            top: 18,
            child: _ScheduleHeroBubble(size: 48),
          ),
          Positioned(
            left: 20,
            top: 24,
            width: constraints.maxWidth * .59,
            child: const Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  '어디서 출발해서\n어디로 가시나요?',
                  style: TextStyle(
                    color: DdubiColors.navy,
                    fontSize: 24,
                    height: 1.25,
                    fontWeight: FontWeight.w900,
                    letterSpacing: -.7,
                  ),
                ),
                SizedBox(height: 12),
                Text(
                  '뚜비가 가장 좋은 출발 시간을\n찾도록 도와드릴게요!',
                  style: TextStyle(
                    color: Color(0xFF587086),
                    fontSize: 13,
                    height: 1.45,
                    fontWeight: FontWeight.w600,
                  ),
                ),
              ],
            ),
          ),
          Positioned(
            right: -8,
            bottom: -2,
            width: constraints.maxWidth * .43,
            height: 170,
            child: Image.asset(
              'assets/images/ttubee/intro-calendar-clock.png',
              fit: BoxFit.contain,
              filterQuality: FilterQuality.high,
            ),
          ),
        ],
      ),
    ),
  );

  Future<void> _leaveForm(BuildContext context) async {
    FocusScope.of(context).unfocus();
    if (await Navigator.of(context).maybePop()) return;
    if (!context.mounted) return;
    GoRouter.maybeOf(context)?.go('/schedules');
  }

  Widget _lastTripCard() => Container(
    decoration: BoxDecoration(
      color: const Color(0xFFE4F8F4),
      borderRadius: BorderRadius.circular(20),
      border: Border.all(color: const Color(0xFFC2EEE5)),
    ),
    child: Material(
      color: Colors.transparent,
      borderRadius: BorderRadius.circular(20),
      clipBehavior: Clip.antiAlias,
      child: SwitchListTile(
        key: const Key('considerLastTripSwitch'),
        contentPadding: const EdgeInsets.fromLTRB(16, 6, 12, 6),
        secondary: const CircleAvatar(
          radius: 22,
          backgroundColor: DdubiColors.green,
          child: Icon(Icons.directions_bus_rounded, color: Colors.white),
        ),
        title: const Text(
          '막차 고려',
          style: TextStyle(
            color: DdubiColors.navy,
            fontWeight: FontWeight.w900,
          ),
        ),
        subtitle: const Text(
          '늦은 일정의 막차 위험을 함께 확인해요.',
          style: TextStyle(color: Color(0xFF60778A), fontSize: 12),
        ),
        value: considerLastTrip,
        onChanged: (value) => setState(() => considerLastTrip = value),
      ),
    ),
  );

  Widget _infoCard() => Container(
    height: 104,
    clipBehavior: Clip.antiAlias,
    padding: const EdgeInsets.fromLTRB(16, 14, 2, 12),
    decoration: BoxDecoration(
      color: const Color(0xFFECFAF7),
      borderRadius: BorderRadius.circular(20),
      border: Border.all(color: const Color(0xFFCBEFE8)),
    ),
    child: Row(
      children: [
        Container(
          width: 38,
          height: 38,
          decoration: const BoxDecoration(
            color: Color(0xFFFFEAA0),
            shape: BoxShape.circle,
          ),
          child: const Icon(
            Icons.lightbulb_rounded,
            color: Color(0xFFFFB800),
            size: 24,
          ),
        ),
        const SizedBox(width: 11),
        const Expanded(
          child: Text(
            '도착 희망 시간까지 갈 수 있도록\n교통 상황을 고려해 최적의\n출발 시간을 추천해드려요!',
            style: TextStyle(
              color: DdubiColors.navy,
              fontSize: 12,
              height: 1.45,
              fontWeight: FontWeight.w700,
            ),
          ),
        ),
        SizedBox(
          width: 78,
          height: 88,
          child: Image.asset(
            'assets/images/ttubee/welcome.png',
            fit: BoxFit.contain,
            alignment: Alignment.bottomCenter,
            filterQuality: FilterQuality.high,
          ),
        ),
      ],
    ),
  );

  Future<void> _save() async {
    if (!(formKey.currentState?.validate() ?? false)) return;
    if (origin == null || destination == null) return;
    final plannedDeparture = DateTime(
      departureDate.year,
      departureDate.month,
      departureDate.day,
      departureTime.hour,
      departureTime.minute,
    );
    final arriveBy = DateTime(
      arrivalDate.year,
      arrivalDate.month,
      arrivalDate.day,
      arrivalTime.hour,
      arrivalTime.minute,
    );
    if (!plannedDeparture.isBefore(arriveBy)) {
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('출발시각은 도착시각보다 앞서야 합니다.')));
      return;
    }
    final schedule = Schedule(
      id:
          widget.schedule?.id ??
          'schedule-${DateTime.now().microsecondsSinceEpoch}',
      title: title.text.trim(),
      origin: origin!,
      destination: destination!,
      arriveBy: arriveBy,
      plannedDepartAt: plannedDeparture,
      importance: importance,
      considerLastTrip: considerLastTrip,
      preferredTransport: transport,
      repeatLabel: widget.schedule?.repeatLabel,
      updatedAt: widget.schedule?.updatedAt,
    );
    try {
      if (widget.schedule == null) {
        await widget.repository.addSchedule(schedule);
      } else {
        await widget.repository.updateSchedule(schedule);
      }
      if (mounted) context.pop(true);
    } on ScheduleConflictException {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('다른 기기에서 일정이 변경됐어요. 새로 고침 후 다시 시도해주세요.')),
      );
    } on Object {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(
            widget.schedule == null
                ? '일정을 저장하지 못했어요. 입력값과 연결 상태를 확인해주세요.'
                : '일정을 수정하지 못했어요. 잠시 후 다시 시도해주세요.',
          ),
        ),
      );
    }
  }

  Future<void> _pickDate(BuildContext context, {required bool arrival}) async {
    final initialDate = arrival ? arrivalDate : departureDate;
    final selected = await showDatePicker(
      context: context,
      initialDate: initialDate,
      firstDate: DateTime.now().subtract(const Duration(days: 1)),
      lastDate: DateTime.now().add(const Duration(days: 365 * 2)),
    );
    if (selected == null || !mounted) return;
    setState(() {
      if (arrival) {
        arrivalDate = selected;
      } else {
        departureDate = selected;
      }
    });
  }

  Future<void> _pickTime(BuildContext context, {required bool arrival}) async {
    final selected = await showTimePicker(
      context: context,
      initialTime: arrival ? arrivalTime : departureTime,
    );
    if (selected == null || !mounted) return;
    setState(() {
      if (arrival) {
        arrivalTime = selected;
      } else {
        departureTime = selected;
      }
    });
  }

  Widget _field(
    String label,
    TextEditingController controller, {
    IconData? icon,
  }) => Padding(
    padding: const EdgeInsets.only(bottom: 15),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          label,
          style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 12),
        ),
        const SizedBox(height: 6),
        TextFormField(
          controller: controller,
          validator: (value) {
            final text = (value ?? '').trim();
            if (text.isEmpty) return '필수 입력 항목입니다.';
            return null;
          },
          decoration: InputDecoration(
            filled: true,
            fillColor: const Color(0xFFFAFCFD),
            prefixIcon: icon == null
                ? null
                : Icon(icon, color: DdubiColors.green),
            suffixIcon: controller.text.isEmpty
                ? null
                : IconButton(
                    tooltip: '입력값 지우기',
                    onPressed: () => setState(controller.clear),
                    icon: const Icon(Icons.cancel_rounded, size: 20),
                  ),
            border: OutlineInputBorder(
              borderRadius: BorderRadius.circular(14),
              borderSide: const BorderSide(color: Color(0xFFD7E4E8)),
            ),
          ),
        ),
      ],
    ),
  );

  Widget _dateTimeSection({
    required String label,
    required IconData icon,
    required DateTime date,
    required TimeOfDay time,
    required Key dateKey,
    required Key timeKey,
    required VoidCallback onDatePressed,
    required VoidCallback onTimePressed,
  }) => Column(
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      Row(
        children: [
          Icon(icon, color: DdubiColors.green, size: 20),
          const SizedBox(width: 7),
          Text(
            label,
            style: const TextStyle(
              color: DdubiColors.navy,
              fontSize: 13,
              fontWeight: FontWeight.w900,
            ),
          ),
        ],
      ),
      const SizedBox(height: 8),
      Row(
        children: [
          Expanded(
            flex: 6,
            child: _dateTimeButton(
              key: dateKey,
              icon: Icons.calendar_month_rounded,
              text: _scheduleDateLabel(date),
              onPressed: onDatePressed,
            ),
          ),
          const SizedBox(width: 8),
          Expanded(
            flex: 4,
            child: _dateTimeButton(
              key: timeKey,
              icon: Icons.access_time_filled_rounded,
              text: _scheduleTimeLabel(time),
              onPressed: onTimePressed,
            ),
          ),
        ],
      ),
    ],
  );

  Widget _dateTimeButton({
    required Key key,
    required IconData icon,
    required String text,
    required VoidCallback onPressed,
  }) => InkWell(
    key: key,
    borderRadius: BorderRadius.circular(14),
    onTap: onPressed,
    child: Container(
      height: 52,
      padding: const EdgeInsets.symmetric(horizontal: 11),
      decoration: BoxDecoration(
        color: const Color(0xFFFAFCFD),
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: const Color(0xFFD7E4E8)),
      ),
      child: Row(
        children: [
          Icon(icon, color: const Color(0xFF365976), size: 20),
          const SizedBox(width: 7),
          Expanded(
            child: Text(
              text,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: const TextStyle(
                color: DdubiColors.navy,
                fontSize: 12,
                fontWeight: FontWeight.w700,
              ),
            ),
          ),
          const Icon(Icons.keyboard_arrow_down_rounded, size: 18),
        ],
      ),
    ),
  );

  String _scheduleDateLabel(DateTime date) {
    const weekdays = ['월', '화', '수', '목', '금', '토', '일'];
    return '${date.year}.${date.month}.${date.day} (${weekdays[date.weekday - 1]})';
  }

  String _scheduleTimeLabel(TimeOfDay time) {
    final period = time.hour < 12 ? '오전' : '오후';
    final hour = time.hourOfPeriod == 0 ? 12 : time.hourOfPeriod;
    return '$period $hour:${time.minute.toString().padLeft(2, '0')}';
  }
}

class _ScheduleHeroBubble extends StatelessWidget {
  const _ScheduleHeroBubble({required this.size});

  final double size;

  @override
  Widget build(BuildContext context) => Container(
    width: size,
    height: size,
    decoration: BoxDecoration(
      color: Colors.white.withValues(alpha: .38),
      shape: BoxShape.circle,
    ),
  );
}

class ScheduleDetailScreen extends StatelessWidget {
  const ScheduleDetailScreen({
    super.key,
    required this.repository,
    required this.routeRepository,
    this.recommendationApi,
    required this.scheduleId,
    required this.arrivalApi,
    this.initialSchedule,
    this.initialRoutes,
  });
  final ScheduleRepository repository;
  final DdubiRepository routeRepository;
  final RecommendationApi? recommendationApi;
  final String scheduleId;
  final ArrivalApi arrivalApi;
  final Schedule? initialSchedule;
  final List<RouteOption>? initialRoutes;

  @override
  Widget build(BuildContext c) => FutureBuilder<Schedule?>(
    future: _loadSchedule(),
    builder: (context, snapshot) {
      if (snapshot.hasError) {
        return const AppPage(
          title: '일정 상세',
          child: DataLoadError(message: '일정을 불러오지 못했어요.'),
        );
      }
      if (snapshot.connectionState != ConnectionState.done) {
        return const AppPage(
          title: '일정 상세',
          child: Center(child: CircularProgressIndicator()),
        );
      }
      final schedule = snapshot.data;
      if (schedule == null) {
        return const AppPage(
          title: '일정 상세',
          child: DataLoadError(message: '해당 일정을 찾을 수 없어요.'),
        );
      }
      return FutureBuilder<_ScheduleRecommendationData>(
        future: _loadRecommendation(schedule),
        builder: (context, recommendationSnapshot) {
          if (!recommendationSnapshot.hasData &&
              !recommendationSnapshot.hasError) {
            return const AppPage(
              title: '일정 상세',
              child: Center(child: CircularProgressIndicator()),
            );
          }
          final data = recommendationSnapshot.data;
          return _content(
            context,
            schedule,
            data?.route,
            data?.overview,
            data?.savedRoutes ?? const [],
            recommendationUnavailable:
                recommendationSnapshot.hasError ||
                data?.recommendationUnavailable == true,
            routesUnavailable: data?.routesUnavailable == true,
          );
        },
      );
    },
  );

  Future<Schedule?> _loadSchedule() async {
    if (initialSchedule != null) return initialSchedule;
    try {
      return await repository.scheduleById(scheduleId);
    } on Object {
      final schedules = await repository.schedules();
      for (final schedule in schedules) {
        if (schedule.id == scheduleId) return schedule;
      }
      return null;
    }
  }

  Future<_ScheduleRecommendationData> _loadRecommendation(
    Schedule schedule,
  ) async {
    if (initialRoutes != null && initialRoutes!.isNotEmpty) {
      return _ScheduleRecommendationData(route: initialRoutes!.first);
    }
    if (recommendationApi == null) {
      return const _ScheduleRecommendationData();
    }
    RecommendationOverview? overview;
    var recommendationUnavailable = false;
    try {
      overview = await recommendationApi!.getLatestOverview(schedule.id);
    } on Object {
      recommendationUnavailable = true;
    }
    List<SavedRouteSummary> savedRoutes = const [];
    var routesUnavailable = false;
    try {
      savedRoutes = await recommendationApi!.getSavedRoutes(schedule.id);
    } on Object {
      routesUnavailable = true;
    }
    final selectedSavedRoute = savedRoutes.where((route) => route.selected);
    return _ScheduleRecommendationData(
      overview: overview,
      route: overview?.hasEstimate == true
          ? overview!.toRouteOption(schedule)
          : selectedSavedRoute.isNotEmpty
          ? selectedSavedRoute.first.toRouteOption(schedule)
          : null,
      savedRoutes: savedRoutes,
      recommendationUnavailable: recommendationUnavailable,
      routesUnavailable: routesUnavailable,
    );
  }

  Widget _content(
    BuildContext c,
    Schedule schedule,
    RouteOption? route,
    RecommendationOverview? overview,
    List<SavedRouteSummary> savedRoutes, {
    bool recommendationUnavailable = false,
    bool routesUnavailable = false,
  }) {
    return AppPage(
      title: '일정 상세',
      child: ListView(
        key: const Key('scheduleRouteOverview'),
        padding: const EdgeInsets.fromLTRB(16, 8, 16, 28),
        children: [
          Container(
            padding: const EdgeInsets.all(16),
            decoration: BoxDecoration(
              color: Colors.white,
              borderRadius: BorderRadius.circular(18),
              border: Border.all(color: const Color(0xFFD5E6FA)),
            ),
            child: Row(
              children: [
                Container(
                  width: 58,
                  height: 58,
                  decoration: BoxDecoration(
                    color: const Color(0xFFE9F1FF),
                    borderRadius: BorderRadius.circular(16),
                  ),
                  child: const Icon(
                    Icons.work_rounded,
                    color: Color(0xFF3C73DD),
                    size: 30,
                  ),
                ),
                const SizedBox(width: 13),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        schedule.title,
                        style: const TextStyle(
                          fontSize: 19,
                          fontWeight: FontWeight.w900,
                          color: DdubiColors.navy,
                        ),
                      ),
                      const SizedBox(height: 5),
                      Text(
                        '${_fullDate(schedule.date)}  ${schedule.time}',
                        style: const TextStyle(
                          fontSize: 13,
                          fontWeight: FontWeight.w700,
                          color: Color(0xFF415A89),
                        ),
                      ),
                      const SizedBox(height: 4),
                      Row(
                        children: [
                          const Icon(
                            Icons.location_on_outlined,
                            size: 16,
                            color: Color(0xFF516A98),
                          ),
                          const SizedBox(width: 3),
                          Expanded(
                            child: Text(
                              schedule.place,
                              maxLines: 2,
                              overflow: TextOverflow.ellipsis,
                              style: const TextStyle(
                                fontSize: 13,
                                color: Color(0xFF516A98),
                              ),
                            ),
                          ),
                        ],
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(height: 10),
          Container(
            padding: const EdgeInsets.fromLTRB(8, 10, 16, 10),
            decoration: BoxDecoration(
              gradient: const LinearGradient(
                colors: [Color(0xFFE1FAF5), Color(0xFFF1FBE8)],
              ),
              borderRadius: BorderRadius.circular(19),
              border: Border.all(color: const Color(0xFFC7EEDC)),
            ),
            child: Row(
              children: [
                Image.asset(
                  'assets/images/ttubee/running.png',
                  width: 104,
                  height: 92,
                  fit: BoxFit.contain,
                ),
                const SizedBox(width: 4),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Container(
                        padding: const EdgeInsets.symmetric(
                          horizontal: 10,
                          vertical: 5,
                        ),
                        decoration: BoxDecoration(
                          color: Colors.white,
                          borderRadius: BorderRadius.circular(14),
                        ),
                        child: const Text(
                          '이 일정에 맞춰',
                          style: TextStyle(
                            fontSize: 11,
                            fontWeight: FontWeight.w800,
                            color: Color(0xFF35528A),
                          ),
                        ),
                      ),
                      const SizedBox(height: 6),
                      Text(
                        route == null
                            ? '추천 출발 시간을 확인 중이에요.'
                            : '${route.departure}에 출발하는 것을\n추천해요!',
                        style: const TextStyle(
                          fontSize: 19,
                          height: 1.28,
                          fontWeight: FontWeight.w900,
                          color: DdubiColors.navy,
                        ),
                      ),
                      const SizedBox(height: 4),
                      const Text(
                        '실시간 이동 상황을 반영했어요.',
                        style: TextStyle(
                          fontSize: 11,
                          color: Color(0xFF68808B),
                        ),
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(height: 14),
          Row(
            children: [
              const Expanded(
                child: Text(
                  '뚜비 최종 추천 경로',
                  style: TextStyle(
                    fontSize: 19,
                    fontWeight: FontWeight.w900,
                    color: DdubiColors.navy,
                  ),
                ),
              ),
              if (overview?.preview == true)
                const StatusBadge('검증용', tone: DdubiColors.blue),
            ],
          ),
          if (recommendationUnavailable)
            const InfoCard(child: Text('최신 추천을 불러오지 못했어요. 잠시 후 다시 확인해주세요.'))
          else if (route == null)
            InfoCard(
              child: Text(
                overview?.state == 'PENDING'
                    ? '추천 경로를 계산하고 있어요.'
                    : '아직 생성된 추천 결과가 없어요. 아래에서 다시 계산해주세요.',
              ),
            )
          else ...[
            _FinalRouteSummary(route: route),
            const SizedBox(height: 10),
            if (recommendationApi != null)
              _RecommendationEvidenceCard(
                api: recommendationApi!,
                overview: overview,
              ),
          ],
          const SizedBox(height: 16),
          _SavedRouteList(
            routes: savedRoutes,
            unavailable: routesUnavailable,
            onSelected: recommendationApi == null
                ? null
                : (savedRoute) => _open(
                    c,
                    RouteMapScreen(
                      repository: routeRepository,
                      api: recommendationApi,
                      savedRoute: savedRoute,
                      selectedRoute: savedRoute.toRouteOption(schedule),
                      departureLabel:
                          savedRoute.fromName ?? schedule.departurePlace,
                      destinationLabel: savedRoute.toName ?? schedule.place,
                    ),
                  ),
          ),
          const SizedBox(height: 16),
          const Text(
            '오늘의 이동 변수',
            style: TextStyle(
              fontSize: 19,
              fontWeight: FontWeight.w900,
              color: DdubiColors.navy,
            ),
          ),
          const SizedBox(height: 10),
          const Row(
            children: [
              Expanded(
                child: _RouteVariable(
                  icon: Icons.wb_sunny_rounded,
                  label: '날씨',
                  value: '확인 필요',
                  color: Color(0xFFFFB323),
                ),
              ),
              SizedBox(width: 7),
              Expanded(
                child: _RouteVariable(
                  icon: Icons.groups_rounded,
                  label: '혼잡도',
                  value: '확인 필요',
                  color: Color(0xFF4D80E8),
                ),
              ),
              SizedBox(width: 7),
              Expanded(
                child: _RouteVariable(
                  icon: Icons.directions_car_rounded,
                  label: '도로 통제',
                  value: '확인 필요',
                  color: Color(0xFF00A873),
                ),
              ),
              SizedBox(width: 7),
              Expanded(
                child: _RouteVariable(
                  icon: Icons.directions_subway_rounded,
                  label: '지하철 지연',
                  value: '확인 필요',
                  color: Color(0xFF00A873),
                ),
              ),
            ],
          ),
          const SizedBox(height: 14),
          if (repository is ScheduleEvaluationRepository)
            ScheduleEvaluationCard(
              repository: repository as ScheduleEvaluationRepository,
              scheduleId: schedule.id,
            )
          else
            const InfoCard(child: Text('이 일정은 서버 예측에 연결되지 않았습니다.')),
          const SizedBox(height: 14),
          OutlinedButton.icon(
            key: const Key('editScheduleButton'),
            onPressed: () async {
              final changed = await c.push<bool>(
                '/schedules/${schedule.id}/edit',
              );
              if (changed == true && c.mounted) {
                c.go('/schedules/${schedule.id}');
              }
            },
            icon: const Icon(Icons.edit_outlined),
            label: const Text('일정 수정'),
          ),
          const SizedBox(height: 8),
          FilledButton.icon(
            key: const Key('completeScheduleButton'),
            onPressed: () => _recordArrival(c, schedule),
            icon: const Icon(Icons.check_circle_outline),
            label: const Text('도착 완료'),
          ),
          const SizedBox(height: 8),
          TextButton.icon(
            key: const Key('cancelScheduleButton'),
            onPressed: () => _confirmCancel(c, schedule),
            icon: const Icon(Icons.delete_outline, color: DdubiColors.red),
            label: const Text(
              '일정 취소',
              style: TextStyle(color: DdubiColors.red),
            ),
          ),
        ],
      ),
    );
  }

  Future<void> _confirmCancel(BuildContext context, Schedule schedule) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('일정을 취소할까요?'),
        content: Text('${schedule.title} 일정은 목록에서 제외됩니다.'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(dialogContext, false),
            child: const Text('돌아가기'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(dialogContext, true),
            child: const Text('일정 취소'),
          ),
        ],
      ),
    );
    if (confirmed != true) return;
    try {
      await repository.cancelSchedule(schedule.id);
      if (context.mounted) context.go('/schedules');
    } on Object {
      if (!context.mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('일정을 취소하지 못했어요. 잠시 후 다시 시도해주세요.')),
      );
    }
  }

  Future<void> _recordArrival(BuildContext context, Schedule schedule) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('도착을 완료할까요?'),
        content: const Text('위치 정보 없이 일정만 완료하고 이동 기록을 남깁니다.'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(dialogContext, false),
            child: const Text('취소'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(dialogContext, true),
            child: const Text('완료'),
          ),
        ],
      ),
    );
    if (confirmed != true) return;
    try {
      await arrivalApi.record(schedule.id);
      if (!context.mounted) return;
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('도착 기록을 저장했어요.')));
      context.go('/schedules');
    } on Object {
      if (!context.mounted) return;
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('도착 기록을 저장하지 못했어요.')));
    }
  }
}

class _ScheduleRecommendationData {
  const _ScheduleRecommendationData({
    this.route,
    this.overview,
    this.savedRoutes = const [],
    this.recommendationUnavailable = false,
    this.routesUnavailable = false,
  });

  final RouteOption? route;
  final RecommendationOverview? overview;
  final List<SavedRouteSummary> savedRoutes;
  final bool recommendationUnavailable;
  final bool routesUnavailable;
}

class _SavedRouteList extends StatelessWidget {
  const _SavedRouteList({
    required this.routes,
    required this.unavailable,
    required this.onSelected,
  });

  final List<SavedRouteSummary> routes;
  final bool unavailable;
  final ValueChanged<SavedRouteSummary>? onSelected;

  @override
  Widget build(BuildContext context) => Column(
    key: const Key('savedRouteList'),
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      const Text(
        '저장된 추천 경로',
        style: TextStyle(
          fontSize: 19,
          fontWeight: FontWeight.w900,
          color: DdubiColors.navy,
        ),
      ),
      const SizedBox(height: 9),
      if (unavailable)
        const InfoCard(child: Text('저장된 경로를 불러오지 못했어요.'))
      else if (routes.isEmpty)
        const InfoCard(
          key: Key('savedRouteEmptyState'),
          child: Text('아직 평가된 추천 경로가 없어요.'),
        )
      else
        for (final route in routes) ...[
          _SavedRouteTile(route: route, onTap: onSelected),
          if (route != routes.last) const SizedBox(height: 8),
        ],
    ],
  );
}

class _SavedRouteTile extends StatelessWidget {
  const _SavedRouteTile({required this.route, required this.onTap});

  final SavedRouteSummary route;
  final ValueChanged<SavedRouteSummary>? onTap;

  @override
  Widget build(BuildContext context) => Material(
    color: route.selected ? const Color(0xFFE7FAF5) : Colors.white,
    borderRadius: BorderRadius.circular(16),
    child: InkWell(
      key: Key('savedRoute-${route.routeId}'),
      borderRadius: BorderRadius.circular(16),
      onTap: onTap == null ? null : () => onTap!(route),
      child: Container(
        padding: const EdgeInsets.all(14),
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(16),
          border: Border.all(
            color: route.selected
                ? const Color(0xFF9DE2D2)
                : const Color(0xFFDCE6EC),
          ),
        ),
        child: Row(
          children: [
            Container(
              width: 42,
              height: 42,
              decoration: BoxDecoration(
                color: route.selected
                    ? DdubiColors.green
                    : const Color(0xFFEAF1F8),
                shape: BoxShape.circle,
              ),
              child: Icon(
                _transportIcon(route.modes),
                color: route.selected ? Colors.white : DdubiColors.navy,
              ),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      Expanded(
                        child: Text(
                          route.selected ? '선택된 추천 경로' : '${route.rankNo}순위 경로',
                          style: const TextStyle(
                            fontWeight: FontWeight.w900,
                            color: DdubiColors.navy,
                          ),
                        ),
                      ),
                      if (route.selected)
                        const StatusBadge('선택됨', tone: DdubiColors.green),
                    ],
                  ),
                  const SizedBox(height: 4),
                  Text(
                    '${route.fromName ?? '출발지'} → ${route.toName ?? '도착지'}',
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(color: Color(0xFF52677B)),
                  ),
                  const SizedBox(height: 3),
                  Text(
                    '약 ${route.baseDurationMin}분 · ${route.segmentCount}개 구간 · '
                    '${route.modes.map(_routeModeLabel).join(' · ')}',
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(
                      fontSize: 12,
                      color: Color(0xFF6E8193),
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(width: 4),
            const Icon(Icons.chevron_right_rounded, color: Color(0xFF7F91A2)),
          ],
        ),
      ),
    ),
  );

  static IconData _transportIcon(List<String> modes) {
    if (modes.contains('SUBWAY')) return Icons.directions_subway_rounded;
    if (modes.contains('BUS')) return Icons.directions_bus_rounded;
    return Icons.directions_walk_rounded;
  }
}

class _RecommendationEvidenceCard extends StatelessWidget {
  const _RecommendationEvidenceCard({
    required this.api,
    required this.overview,
  });

  final RecommendationApi api;
  final RecommendationOverview? overview;

  @override
  Widget build(BuildContext context) {
    final recommendationId = overview?.recommendationId;
    if (recommendationId == null) {
      return _evidence(
        context,
        source: overview?.source,
        reasonCodes: overview?.reasonCodes ?? const [],
        preview: overview?.preview,
      );
    }
    return FutureBuilder<RecommendationExplanation>(
      future: api.getExplanation(recommendationId),
      builder: (context, snapshot) {
        if (snapshot.hasError) {
          return _evidence(
            context,
            source: overview?.source,
            reasonCodes: overview?.reasonCodes ?? const [],
            preview: overview?.preview,
            note: '상세 추천 근거를 불러오지 못했어요.',
          );
        }
        final explanation = snapshot.data;
        return _evidence(
          context,
          source: explanation?.source ?? overview?.source,
          reasonCodes:
              explanation?.reasonCodes ?? overview?.reasonCodes ?? const [],
          preview: explanation?.preview ?? overview?.preview,
          checkedAt: explanation?.dataCheckedAt,
          loading: !snapshot.hasData,
        );
      },
    );
  }

  Widget _evidence(
    BuildContext context, {
    String? source,
    required List<String> reasonCodes,
    bool? preview,
    DateTime? checkedAt,
    bool loading = false,
    String? note,
  }) => InfoCard(
    color: const Color(0xFFF4F8FF),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const StatusBadge(
          '추천 근거',
          tone: DdubiColors.blue,
          icon: Icons.lightbulb_outline,
        ),
        const SizedBox(height: 10),
        if (loading)
          const LinearProgressIndicator()
        else ...[
          Text(_sourceDescription(source)),
          if (preview == true) ...[
            const SizedBox(height: 6),
            const Text(
              '현재 결과는 검증용이며 일정에 자동 적용되지 않아요.',
              style: TextStyle(color: Color(0xFF64708B)),
            ),
          ],
          if (reasonCodes.isNotEmpty) ...[
            const SizedBox(height: 8),
            for (final reason in reasonCodes.take(3))
              Text('• ${_reasonDescription(reason)}'),
          ],
          if (checkedAt != null) ...[
            const SizedBox(height: 8),
            Text(
              '${checkedAt.hour.toString().padLeft(2, '0')}:${checkedAt.minute.toString().padLeft(2, '0')} 기준',
              style: Theme.of(context).textTheme.bodySmall,
            ),
          ],
          if (note != null) ...[
            const SizedBox(height: 8),
            Text(note, style: Theme.of(context).textTheme.bodySmall),
          ],
        ],
      ],
    ),
  );

  static String _sourceDescription(String? source) => switch (source) {
    'API_ONLY' => '현재 경로 API의 예상 소요시간을 기준으로 계산했어요.',
    'ML_PREVIEW' => '버스 구간 예측을 포함해 계산했어요.',
    'SUBWAY_PREVIEW' => '지하철 시간표를 포함해 계산했어요.',
    'MIXED_PREVIEW' => '경로 API와 교통 예측 정보를 함께 사용했어요.',
    _ => '서버에서 저장한 최신 평가 결과를 표시하고 있어요.',
  };

  static String _reasonDescription(String code) {
    if (code == 'VALIDATION_ONLY') return '검증 단계의 결과예요.';
    if (code.contains('ML_NOT_CONFIGURED')) return '일부 구간은 경로 API 시간을 사용했어요.';
    if (code.contains('BUS_WAIT_MISSING')) return '버스 대기시간 정보가 부족해요.';
    if (code.contains('SUBWAY_TIMETABLE_NOT_CONFIGURED')) {
      return '지하철 시간표 정보가 부족해요.';
    }
    if (code.startsWith('ROAD_POTENTIAL_IMPACT:')) {
      return '예상 이동 구간에 도로 영향 가능성이 있어요.';
    }
    return code;
  }
}

class _FinalRouteSummary extends StatelessWidget {
  const _FinalRouteSummary({required this.route});
  final RouteOption route;

  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.symmetric(vertical: 13),
    decoration: BoxDecoration(
      color: Colors.white,
      borderRadius: BorderRadius.circular(18),
      border: Border.all(color: const Color(0xFFBDE9DF)),
    ),
    child: Row(
      children: [
        _metric(Icons.schedule_rounded, '예상 이동 시간', route.detail),
        _divider(),
        _metric(Icons.payments_outlined, '예상 요금', route.fare),
        _divider(),
        _metric(Icons.eco_rounded, '주요 변동 요소', route.margin),
      ],
    ),
  );

  Widget _metric(IconData icon, String label, String value) => Expanded(
    child: Column(
      children: [
        Icon(icon, size: 23, color: DdubiColors.green),
        const SizedBox(height: 5),
        Text(
          label,
          textAlign: TextAlign.center,
          style: const TextStyle(fontSize: 10, color: Color(0xFF62758A)),
        ),
        const SizedBox(height: 3),
        Text(
          value,
          maxLines: 2,
          overflow: TextOverflow.ellipsis,
          textAlign: TextAlign.center,
          style: const TextStyle(
            fontSize: 12,
            fontWeight: FontWeight.w900,
            color: DdubiColors.navy,
          ),
        ),
      ],
    ),
  );

  Widget _divider() =>
      Container(width: 1, height: 50, color: const Color(0xFFE4ECEF));
}

class _RouteVariable extends StatelessWidget {
  const _RouteVariable({
    required this.icon,
    required this.label,
    required this.value,
    required this.color,
  });
  final IconData icon;
  final String label;
  final String value;
  final Color color;

  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.symmetric(vertical: 10, horizontal: 3),
    decoration: BoxDecoration(
      color: color.withValues(alpha: .09),
      borderRadius: BorderRadius.circular(14),
    ),
    child: Column(
      children: [
        Icon(icon, size: 23, color: color),
        const SizedBox(height: 5),
        Text(
          label,
          maxLines: 1,
          style: const TextStyle(
            fontSize: 10,
            fontWeight: FontWeight.w700,
            color: Color(0xFF617083),
          ),
        ),
        const SizedBox(height: 2),
        Text(
          value,
          style: const TextStyle(
            fontSize: 11,
            fontWeight: FontWeight.w900,
            color: DdubiColors.navy,
          ),
        ),
      ],
    ),
  );
}

class ScheduleEditScreen extends StatelessWidget {
  const ScheduleEditScreen({
    super.key,
    required this.repository,
    required this.placeSearchService,
    required this.scheduleId,
  });

  final ScheduleRepository repository;
  final PlaceSearchService placeSearchService;
  final String scheduleId;

  @override
  Widget build(BuildContext context) => FutureBuilder<Schedule?>(
    future: repository.scheduleById(scheduleId),
    builder: (context, snapshot) {
      if (snapshot.hasError) {
        return const AppPage(
          title: '일정 수정',
          child: DataLoadError(message: '일정을 불러오지 못했어요.'),
        );
      }
      if (snapshot.connectionState != ConnectionState.done) {
        return const AppPage(
          title: '일정 수정',
          child: Center(child: CircularProgressIndicator()),
        );
      }
      final schedule = snapshot.data;
      if (schedule == null) {
        return const AppPage(
          title: '일정 수정',
          child: DataLoadError(message: '수정할 일정을 찾을 수 없어요.'),
        );
      }
      return ScheduleFormScreen(
        repository: repository,
        placeSearchService: placeSearchService,
        schedule: schedule,
      );
    },
  );
}

class RouteResultsScreen extends StatelessWidget {
  const RouteResultsScreen({
    super.key,
    required this.repository,
    required this.api,
    this.condition,
    this.go,
  });
  final DdubiRepository repository;
  final RecommendationApi api;
  final RouteSearchCondition? condition;
  final ValueChanged<int>? go;
  @override
  Widget build(BuildContext c) => AppPage(
    title: '추천 결과',
    webTitle: '추천 경로',
    webSubtitle: '도착 시간과 이동 조건을 기준으로 가장 알맞은 경로를 안내해요.',
    index: go == null ? null : 1,
    onNav: go,
    actions: const [IconButton(onPressed: null, icon: Icon(Icons.refresh))],
    child: FutureBuilder<List<RouteOption>>(
      future: condition == null
          ? repository.routes()
          : api.searchRoutes(condition!),
      builder: (c, s) {
        if (s.hasError) {
          return const DataLoadError(message: '추천 경로를 불러오지 못했어요.');
        }
        if (!s.hasData) {
          return const Center(child: CircularProgressIndicator());
        }
        final route = _primaryRoute(s.data!);
        if (route == null) {
          return const Center(child: Text('표시할 추천 경로가 없어요.'));
        }
        return LayoutBuilder(
          builder: (context, constraints) {
            final isWide =
                constraints.maxWidth >= LayoutConstants.wideScreenBreakpoint;
            return isWide
                ? _webRouteResults(c, route)
                : _mobileRouteResults(c, route);
          },
        );
      },
    ),
  );

  Widget _mobileRouteResults(BuildContext context, RouteOption route) =>
      ListView(
        key: const Key('mobileRouteResultsLayout'),
        padding: _pad,
        children: [
          if (condition != null) ...[
            _RouteSearchSummary(condition: condition!),
            const SizedBox(height: 12),
          ],
          _routeGuide,
          const SizedBox(height: 12),
          Padding(
            padding: const EdgeInsets.only(bottom: 12),
            child: RouteCard(route, onTap: () => _openRouteMap(context, route)),
          ),
          _recommendationReason(route),
        ],
      );

  Widget _webRouteResults(BuildContext context, RouteOption route) => ListView(
    key: const Key('webRouteResultsLayout'),
    padding: _pad,
    children: [
      if (condition != null) ...[
        _RouteSearchSummary(condition: condition!),
        const SizedBox(height: 16),
      ],
      Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Expanded(flex: 3, child: _routeGuide),
          const SizedBox(width: 16),
          Expanded(flex: 2, child: _recommendationReason(route)),
        ],
      ),
      const SizedBox(height: 20),
      Text('추천 경로', style: Theme.of(context).textTheme.titleLarge),
      const SizedBox(height: 12),
      KeyedSubtree(
        key: const Key('webRouteResultsGrid'),
        child: RouteCard(
          route,
          compact: true,
          fillWidth: true,
          onTap: () => _openRouteMap(context, route),
        ),
      ),
    ],
  );

  void _openRouteMap(BuildContext context, RouteOption route) => _open(
    context,
    RouteMapScreen(
      repository: repository,
      selectedRoute: route,
      condition: condition,
    ),
  );

  static const _routeGuide = TtubeeGuideCard(
    '이 경로가 지금 가장 안정적이에요!',
    compact: true,
    character: TtubeeEntrance(
      asset: 'assets/images/ttubee/route-guide.png',
      height: 62,
      fromRight: true,
      semanticLabel: '지도를 든 뚜비',
    ),
  );

  Widget _recommendationReason(RouteOption route) => InfoCard(
    color: const Color(0xFFF4F8FF),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const StatusBadge(
          '추천 이유',
          tone: DdubiColors.blue,
          icon: Icons.lightbulb_outline,
        ),
        const SizedBox(height: 10),
        Text(
          '${route.transport} 경로 중 예상 이동시간이 적합한 후보예요.\n'
          '${route.detail} · ${route.margin} · ${route.fare}',
        ),
      ],
    ),
  );
}

class RouteSearchScreen extends StatefulWidget {
  const RouteSearchScreen({
    super.key,
    required this.repository,
    required this.placeSearchService,
  });

  final DdubiRepository repository;
  final PlaceSearchService placeSearchService;

  @override
  State<RouteSearchScreen> createState() => _RouteSearchScreenState();
}

class _RouteSearchScreenState extends State<RouteSearchScreen> {
  final _formKey = GlobalKey<FormState>();
  PlaceLocation? _origin;
  PlaceLocation? _destination;
  late DateTime _desiredArrivalAt;
  late final TextEditingController _arrival;

  @override
  void initState() {
    super.initState();
    final now = DateTime.now();
    _desiredArrivalAt = DateTime(now.year, now.month, now.day + 1, 10);
    _arrival = TextEditingController(text: _formatArrival(_desiredArrivalAt));
  }

  @override
  void dispose() {
    _arrival.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AppPage(
    title: '경로 찾기',
    webTitle: '경로 탐색',
    webSubtitle: '출발지와 도착 희망시간을 입력해 이동 계획을 만들어보세요.',
    child: Form(
      key: _formKey,
      child: LayoutBuilder(
        builder: (context, constraints) {
          final isWide =
              constraints.maxWidth >= LayoutConstants.wideScreenBreakpoint;
          return ListView(
            padding: _pad,
            children: [
              if (isWide)
                Row(
                  key: const Key('webRouteSearchLayout'),
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    const Expanded(flex: 2, child: _RouteSearchGuide()),
                    const SizedBox(width: 20),
                    Expanded(flex: 3, child: _searchFormCard(context, true)),
                  ],
                )
              else
                Column(
                  key: const Key('mobileRouteSearchLayout'),
                  children: [
                    const TtubeeGuideCard(
                      '언제 어디까지 가야 하는지 알려주세요.\n뚜비가 알맞은 경로를 찾아드릴게요!',
                      compact: true,
                    ),
                    const SizedBox(height: 16),
                    _searchFormCard(context, false),
                    if (_RouteSearchHistory.items.isNotEmpty) ...[
                      const SizedBox(height: 16),
                      _recentSearches(),
                    ],
                  ],
                ),
            ],
          );
        },
      ),
    ),
  );

  Widget _searchFormCard(BuildContext context, bool isWide) => InfoCard(
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Text('이동 조건', style: Theme.of(context).textTheme.titleMedium),
        const SizedBox(height: 16),
        if (isWide)
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Expanded(child: _placeField(true)),
              const SizedBox(width: 12),
              Expanded(child: _placeField(false)),
            ],
          )
        else ...[
          _placeField(true),
          const SizedBox(height: 12),
          _placeField(false),
        ],
        const SizedBox(height: 12),
        TextFormField(
          key: const Key('routeArrivalField'),
          controller: _arrival,
          readOnly: true,
          onTap: _pickArrival,
          decoration: const InputDecoration(
            labelText: '도착 희망시간',
            prefixIcon: Icon(Icons.schedule_outlined),
            suffixIcon: Icon(Icons.edit_calendar_outlined),
          ),
        ),
        const SizedBox(height: 20),
        PrimaryButton('추천 경로 찾기', onPressed: _search),
      ],
    ),
  );

  Widget _recentSearches() => InfoCard(
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        const Text('최근 검색', style: TextStyle(fontWeight: FontWeight.w800)),
        const SizedBox(height: 8),
        for (final item in _RouteSearchHistory.items.take(3))
          ListTile(
            dense: true,
            contentPadding: EdgeInsets.zero,
            leading: const Icon(Icons.history_rounded),
            title: Text('${item.departurePlace} → ${item.destinationPlace}'),
            subtitle: Text(_formatArrival(item.desiredArrivalAt)),
            onTap: () => setState(() {
              _origin = item.origin;
              _destination = item.destination;
              _desiredArrivalAt = item.desiredArrivalAt;
              _arrival.text = _formatArrival(_desiredArrivalAt);
            }),
          ),
      ],
    ),
  );

  Widget _placeField(bool departure) {
    return PlaceSearchField(
      key: Key(departure ? 'routeDepartureField' : 'routeDestinationField'),
      label: departure ? '출발지' : '도착지',
      value: departure ? _origin : _destination,
      searchService: widget.placeSearchService,
      onSelected: (value) => setState(() {
        if (departure) {
          _origin = value;
        } else {
          _destination = value;
        }
      }),
    );
  }

  Future<void> _pickArrival() async {
    final date = await showDatePicker(
      context: context,
      initialDate: _desiredArrivalAt,
      firstDate: DateTime.now(),
      lastDate: DateTime.now().add(const Duration(days: 365)),
    );
    if (date == null || !mounted) return;
    final time = await showTimePicker(
      context: context,
      initialTime: TimeOfDay.fromDateTime(_desiredArrivalAt),
    );
    if (time == null || !mounted) return;
    setState(() {
      _desiredArrivalAt = DateTime(
        date.year,
        date.month,
        date.day,
        time.hour,
        time.minute,
      );
      _arrival.text = _formatArrival(_desiredArrivalAt);
    });
  }

  void _search() {
    if (!(_formKey.currentState?.validate() ?? false)) return;
    if (_origin == null || _destination == null) {
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('출발지와 도착지를 검색해 선택해주세요.')));
      return;
    }
    final condition = RouteSearchCondition(
      origin: _origin!,
      destination: _destination!,
      desiredArrivalAt: _desiredArrivalAt,
    );
    setState(() => _RouteSearchHistory.add(condition));
    context.push('/recommendations/search-results', extra: condition);
  }
}

class _RouteSearchGuide extends StatelessWidget {
  const _RouteSearchGuide();

  @override
  Widget build(BuildContext context) => Column(
    crossAxisAlignment: CrossAxisAlignment.stretch,
    children: [
      const TtubeeGuideCard('언제 어디까지 가야 하는지 알려주세요.\n뚜비가 알맞은 경로를 찾아드릴게요!'),
      const SizedBox(height: 16),
      const InfoCard(
        color: Color(0xFFF4F8FF),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            StatusBadge(
              '어떻게 추천하나요?',
              tone: DdubiColors.blue,
              icon: Icons.info_outline,
            ),
            SizedBox(height: 10),
            Text('도착 희망시간과 실시간 교통 상황을 함께 고려해 경로를 비교해요.'),
          ],
        ),
      ),
    ],
  );
}

class _RouteSearchSummary extends StatelessWidget {
  const _RouteSearchSummary({required this.condition});

  final RouteSearchCondition condition;

  @override
  Widget build(BuildContext context) => InfoCard(
    color: const Color(0xFFF4F8FF),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          '${condition.departurePlace}  →  ${condition.destinationPlace}',
          style: const TextStyle(fontWeight: FontWeight.w800),
        ),
        const SizedBox(height: 6),
        Text('${_formatArrival(condition.desiredArrivalAt)}까지 도착'),
        Align(
          alignment: Alignment.centerRight,
          child: TextButton(
            onPressed: () => context.pop(),
            child: const Text('조건 수정'),
          ),
        ),
      ],
    ),
  );
}

String _formatArrival(DateTime value) =>
    '${value.year}.${value.month.toString().padLeft(2, '0')}.${value.day.toString().padLeft(2, '0')} '
    '${value.hour.toString().padLeft(2, '0')}:${value.minute.toString().padLeft(2, '0')}';

class RouteMapScreen extends StatefulWidget {
  const RouteMapScreen({
    super.key,
    required this.repository,
    this.api,
    this.savedRoute,
    this.selectedRoute,
    this.condition,
    this.departureLabel,
    this.destinationLabel,
    this.initialRouteDeviation = false,
  });

  final DdubiRepository repository;
  final RecommendationApi? api;
  final SavedRouteSummary? savedRoute;
  final RouteOption? selectedRoute;
  final RouteSearchCondition? condition;
  final String? departureLabel;
  final String? destinationLabel;
  final bool initialRouteDeviation;

  @override
  State<RouteMapScreen> createState() => _RouteMapScreenState();
}

class _RouteMapScreenState extends State<RouteMapScreen> {
  late final Future<List<RouteOption>> _routes;
  late RouteKind _selectedKind;
  late bool _showDeviationBanner;

  @override
  void initState() {
    super.initState();
    _selectedKind = widget.selectedRoute?.kind ?? RouteKind.recommended;
    _showDeviationBanner = widget.initialRouteDeviation;
    _routes = widget.savedRoute != null
        ? Future.value(const [])
        : widget.selectedRoute != null
        ? Future.value([widget.selectedRoute!])
        : widget.repository.routes(condition: widget.condition).then((routes) {
            final primary = _primaryRoute(routes);
            return primary == null ? <RouteOption>[] : [primary];
          });
  }

  @override
  Widget build(BuildContext context) =>
      widget.api != null && widget.savedRoute != null
      ? _SavedRouteApiScreen(
          api: widget.api!,
          summary: widget.savedRoute!,
          route:
              widget.selectedRoute ??
              widget.savedRoute!.toRouteOption(
                Schedule(
                  id: '',
                  title: '',
                  origin: const PlaceLocation(name: '', lat: 0, lng: 0),
                  destination: const PlaceLocation(name: '', lat: 0, lng: 0),
                  arriveBy: DateTime.now().add(
                    Duration(minutes: widget.savedRoute!.baseDurationMin),
                  ),
                ),
              ),
          repository: widget.repository,
          departureLabel:
              widget.departureLabel ?? widget.savedRoute!.fromName ?? '출발지',
          destinationLabel:
              widget.destinationLabel ?? widget.savedRoute!.toName ?? '도착지',
        )
      : AppPage(
          title: '경로 상세',
          webTitle: '경로 지도',
          webSubtitle: '추천 경로와 이동 단계, 현재 위치를 한 화면에서 확인하세요.',
          child: FutureBuilder<List<RouteOption>>(
            future: _routes,
            builder: (context, snapshot) {
              if (snapshot.hasError) {
                return const DataLoadError(message: '지도 경로를 불러오지 못했어요.');
              }
              if (!snapshot.hasData) {
                return const Center(child: CircularProgressIndicator());
              }
              final routes = snapshot.data!;
              if (routes.isEmpty) {
                return const Center(child: Text('표시할 추천 경로가 없어요.'));
              }
              final selected = routes.firstWhere(
                (route) => route.kind == _selectedKind,
                orElse: () => routes.first,
              );
              return LayoutBuilder(
                builder: (context, constraints) {
                  final isWide = constraints.maxWidth >= 760;
                  final map = _RouteMapView(
                    routes: routes,
                    selectedKind: selected.kind,
                    departureLabel:
                        widget.departureLabel ??
                        widget.condition?.departurePlace ??
                        '출발지',
                    destinationLabel:
                        widget.destinationLabel ??
                        widget.condition?.destinationPlace ??
                        '도착지',
                    onSelected: (kind) => setState(() => _selectedKind = kind),
                    showRouteSelector: false,
                  );
                  final detail = _RouteMapDetail(
                    route: selected,
                    repository: widget.repository,
                    departureLabel:
                        widget.departureLabel ??
                        widget.condition?.departurePlace ??
                        '출발지',
                    destinationLabel:
                        widget.destinationLabel ??
                        widget.condition?.destinationPlace ??
                        '도착지',
                  );
                  if (isWide) {
                    final layout = Padding(
                      key: const Key('webRouteMapLayout'),
                      padding: const EdgeInsets.all(16),
                      child: Row(
                        children: [
                          Expanded(flex: 3, child: map),
                          const SizedBox(width: 16),
                          SizedBox(
                            width: 360,
                            child: SingleChildScrollView(child: detail),
                          ),
                        ],
                      ),
                    );
                    return Column(
                      children: [
                        if (_showDeviationBanner)
                          _RouteDeviationBanner(
                            onDismiss: () =>
                                setState(() => _showDeviationBanner = false),
                          ),
                        Expanded(child: layout),
                      ],
                    );
                  }
                  return Column(
                    key: const Key('mobileRouteMapLayout'),
                    children: [
                      if (_showDeviationBanner)
                        _RouteDeviationBanner(
                          onDismiss: () =>
                              setState(() => _showDeviationBanner = false),
                        ),
                      Expanded(flex: 6, child: map),
                      Flexible(
                        flex: 4,
                        child: SingleChildScrollView(
                          padding: const EdgeInsets.fromLTRB(12, 8, 12, 16),
                          child: detail,
                        ),
                      ),
                    ],
                  );
                },
              );
            },
          ),
        );
}

class _SavedRouteApiScreen extends StatefulWidget {
  const _SavedRouteApiScreen({
    required this.api,
    required this.summary,
    required this.route,
    required this.repository,
    required this.departureLabel,
    required this.destinationLabel,
  });

  final RecommendationApi api;
  final SavedRouteSummary summary;
  final RouteOption route;
  final DdubiRepository repository;
  final String departureLabel;
  final String destinationLabel;

  @override
  State<_SavedRouteApiScreen> createState() => _SavedRouteApiScreenState();
}

class _SavedRouteApiScreenState extends State<_SavedRouteApiScreen> {
  late Future<_SavedRouteViewData> _data = _load();

  Future<_SavedRouteViewData> _load() async {
    final results = await Future.wait<Object>([
      widget.api.getSavedRouteDetail(widget.summary.routeId),
      widget.api.getSavedRouteMap(widget.summary.routeId),
    ]);
    return _SavedRouteViewData(
      detail: results[0] as SavedRouteDetail,
      map: results[1] as SavedRouteMap,
    );
  }

  @override
  Widget build(BuildContext context) => AppPage(
    title: '경로 상세',
    child: FutureBuilder<_SavedRouteViewData>(
      future: _data,
      builder: (context, snapshot) {
        if (snapshot.hasError) {
          return Center(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                const DataLoadError(message: '경로 상세를 불러오지 못했어요.'),
                TextButton(
                  onPressed: () => setState(() => _data = _load()),
                  child: const Text('다시 시도'),
                ),
              ],
            ),
          );
        }
        if (!snapshot.hasData) {
          return const Center(child: CircularProgressIndicator());
        }
        final data = snapshot.data!;
        return LayoutBuilder(
          builder: (context, constraints) {
            final map = _SavedRouteMapView(
              routeMap: data.map,
              departureLabel: widget.departureLabel,
              destinationLabel: widget.destinationLabel,
            );
            final detail = _SavedRouteDetailPanel(
              summary: widget.summary,
              detail: data.detail,
              route: widget.route,
              repository: widget.repository,
            );
            if (constraints.maxWidth >= 760) {
              return Padding(
                key: const Key('webSavedRouteLayout'),
                padding: const EdgeInsets.all(16),
                child: Row(
                  children: [
                    Expanded(flex: 3, child: map),
                    const SizedBox(width: 16),
                    SizedBox(
                      width: 370,
                      child: SingleChildScrollView(child: detail),
                    ),
                  ],
                ),
              );
            }
            return Column(
              key: const Key('mobileSavedRouteLayout'),
              children: [
                Expanded(flex: 5, child: map),
                Flexible(
                  flex: 5,
                  child: SingleChildScrollView(
                    padding: const EdgeInsets.fromLTRB(12, 8, 12, 18),
                    child: detail,
                  ),
                ),
              ],
            );
          },
        );
      },
    ),
  );
}

class _SavedRouteViewData {
  const _SavedRouteViewData({required this.detail, required this.map});

  final SavedRouteDetail detail;
  final SavedRouteMap map;
}

class _SavedRouteDetailPanel extends StatelessWidget {
  const _SavedRouteDetailPanel({
    required this.summary,
    required this.detail,
    required this.route,
    required this.repository,
  });

  final SavedRouteSummary summary;
  final SavedRouteDetail detail;
  final RouteOption route;
  final DdubiRepository repository;

  @override
  Widget build(BuildContext context) => InfoCard(
    key: const Key('savedRouteDetail'),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Row(
          children: [
            Expanded(child: _metric('예상 시간', '약 ${detail.baseDurationMin}분')),
            Expanded(child: _metric('경로 순위', '${detail.rankNo}순위')),
            Expanded(child: _metric('이동 구간', '${detail.segments.length}개')),
          ],
        ),
        const Divider(height: 28),
        if (detail.selected)
          const Align(
            alignment: Alignment.centerLeft,
            child: StatusBadge('선택된 경로', tone: DdubiColors.green),
          ),
        if (detail.selected) const SizedBox(height: 12),
        if (detail.segments.isEmpty)
          const Text('표시할 이동 구간이 없어요.')
        else
          for (final segment in detail.segments)
            _SavedRouteSegmentTile(segment: segment),
        const SizedBox(height: 12),
        PrimaryButton(
          '이동 안내 시작',
          onPressed: () => _open(
            context,
            MovingScreen(repository: repository, route: route),
          ),
        ),
      ],
    ),
  );

  Widget _metric(String label, String value) => Column(
    children: [
      Text(
        label,
        style: const TextStyle(fontSize: 11, color: Color(0xFF687B8D)),
      ),
      const SizedBox(height: 3),
      Text(
        value,
        style: const TextStyle(
          fontSize: 15,
          fontWeight: FontWeight.w900,
          color: DdubiColors.navy,
        ),
      ),
    ],
  );
}

class _SavedRouteSegmentTile extends StatelessWidget {
  const _SavedRouteSegmentTile({required this.segment});

  final SavedRouteSegment segment;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.only(bottom: 10),
    child: Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Container(
          width: 36,
          height: 36,
          decoration: BoxDecoration(
            color: _modeColor(segment.mode).withValues(alpha: .12),
            shape: BoxShape.circle,
          ),
          child: Icon(
            _modeIcon(segment.mode),
            size: 20,
            color: _modeColor(segment.mode),
          ),
        ),
        const SizedBox(width: 10),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                '${segment.seq}. ${_routeModeLabel(segment.mode)} '
                        '${segment.providerRouteKey ?? ''}'
                    .trim(),
                style: const TextStyle(
                  fontWeight: FontWeight.w900,
                  color: DdubiColors.navy,
                ),
              ),
              const SizedBox(height: 3),
              Text(
                '${segment.fromName ?? '출발'} → ${segment.toName ?? '도착'}',
                style: const TextStyle(color: Color(0xFF52677B)),
              ),
              Text(
                '약 ${segment.baseDurationMin}분',
                style: const TextStyle(fontSize: 12, color: Color(0xFF7A8B9B)),
              ),
            ],
          ),
        ),
      ],
    ),
  );
}

IconData _modeIcon(String mode) => switch (mode) {
  'BUS' => Icons.directions_bus_rounded,
  'SUBWAY' => Icons.directions_subway_rounded,
  'TAXI' => Icons.local_taxi_rounded,
  _ => Icons.directions_walk_rounded,
};

String _routeModeLabel(String mode) => switch (mode) {
  'WALK' => '도보',
  'BUS' => '버스',
  'SUBWAY' => '지하철',
  'TAXI' => '택시',
  _ => mode,
};

Color _modeColor(String mode) => switch (mode) {
  'BUS' => const Color(0xFF2E70E8),
  'SUBWAY' => const Color(0xFF00A56F),
  'TAXI' => const Color(0xFFF3A91B),
  _ => const Color(0xFF55697D),
};

class _SavedRouteMapView extends StatefulWidget {
  const _SavedRouteMapView({
    required this.routeMap,
    required this.departureLabel,
    required this.destinationLabel,
  });

  final SavedRouteMap routeMap;
  final String departureLabel;
  final String destinationLabel;

  @override
  State<_SavedRouteMapView> createState() => _SavedRouteMapViewState();
}

class _SavedRouteMapViewState extends State<_SavedRouteMapView> {
  KakaoMapController? _controller;
  StreamSubscription<CameraMoveEndEvent>? _cameraMoveSubscription;
  List<_ProjectedRouteSegment> _projectedSegments = const [];

  LatLng get _center {
    final bounds = widget.routeMap.bounds;
    if (bounds != null) {
      return LatLng(
        latitude: (bounds.minLat + bounds.maxLat) / 2,
        longitude: (bounds.minLng + bounds.maxLng) / 2,
      );
    }
    final coordinates = widget.routeMap.coordinates;
    if (coordinates.isNotEmpty) {
      return LatLng(
        latitude: coordinates.first.latitude,
        longitude: coordinates.first.longitude,
      );
    }
    return const LatLng(latitude: 37.5665, longitude: 126.9780);
  }

  Future<void> _onMapCreated(KakaoMapController controller) async {
    _controller = controller;
    if (!kIsWeb) {
      _cameraMoveSubscription = controller.onCameraMoveEndStream.listen(
        (_) => _refreshProjectedSegments(),
      );
    }
    final points = widget.routeMap.coordinates;
    try {
      if (!kIsWeb) {
        await controller.addMarkerLayer(
          layerId: KakaoMapController.defaultLabelLayerId,
          zOrder: 1000,
          clickable: false,
        );
      }
      if (points.isNotEmpty) {
        await controller.addMarkers(
          markerOptions: [
            MarkerOption(
              id: 'saved-route-departure',
              latLng: LatLng(
                latitude: points.first.latitude,
                longitude: points.first.longitude,
              ),
              text: '출발',
            ),
            MarkerOption(
              id: 'saved-route-destination',
              latLng: LatLng(
                latitude: points.last.latitude,
                longitude: points.last.longitude,
              ),
              text: '도착',
            ),
          ],
        );
      }
      await _showEntireRoute();
    } catch (error) {
      debugPrint('Saved route map setup failed: $error');
    }
  }

  Future<void> _showEntireRoute() async {
    final controller = _controller;
    if (controller == null) return;
    final bounds = widget.routeMap.bounds;
    final update = bounds == null
        ? CameraUpdate.fromLatLng(_center)
        : CameraUpdate.fromBounds(
            LatLngBounds(
              southwest: LatLng(
                latitude: bounds.minLat,
                longitude: bounds.minLng,
              ),
              northeast: LatLng(
                latitude: bounds.maxLat,
                longitude: bounds.maxLng,
              ),
            ),
            padding: 48,
          );
    try {
      await controller.moveCamera(
        cameraUpdate: update,
        animation: const CameraAnimation(
          duration: 450,
          autoElevation: true,
          isConsecutive: false,
        ),
      );
      await _refreshProjectedSegments();
    } catch (error) {
      debugPrint('Saved route camera move failed: $error');
    }
  }

  Future<void> _refreshProjectedSegments() async {
    final controller = _controller;
    if (controller == null || kIsWeb) return;
    final projected = <_ProjectedRouteSegment>[];
    try {
      for (final segment in widget.routeMap.segments) {
        final points = <Offset>[];
        for (final coordinate in segment.coordinates) {
          final point = await controller.toScreenPoint(
            position: LatLng(
              latitude: coordinate.latitude,
              longitude: coordinate.longitude,
            ),
          );
          if (point != null) points.add(point);
        }
        projected.add(
          _ProjectedRouteSegment(mode: segment.mode, points: points),
        );
      }
      if (mounted) setState(() => _projectedSegments = projected);
    } catch (error) {
      debugPrint('Saved route coordinate projection failed: $error');
    }
  }

  @override
  void dispose() {
    _cameraMoveSubscription?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final hasCoordinates = widget.routeMap.coordinates.isNotEmpty;
    return ClipRRect(
      borderRadius: BorderRadius.circular(16),
      child: Stack(
        children: [
          Positioned.fill(
            child: KakaoMapConfig.isReady
                ? KakaoMap(
                    initialPosition: _center,
                    initialLevel: kIsWeb ? 5 : 13,
                    onMapCreated: _onMapCreated,
                  )
                : const ColoredBox(color: Color(0xFFEAF2EC)),
          ),
          if (hasCoordinates)
            Positioned.fill(
              child: IgnorePointer(
                child: CustomPaint(
                  key: const Key('savedRoutePolyline'),
                  painter: _SavedRoutePolylinePainter(
                    widget.routeMap,
                    projectedSegments: _projectedSegments,
                  ),
                ),
              ),
            )
          else
            const Center(
              child: Material(
                color: Colors.white,
                borderRadius: BorderRadius.all(Radius.circular(14)),
                child: Padding(
                  padding: EdgeInsets.symmetric(horizontal: 16, vertical: 12),
                  child: Text('이 경로는 표시할 지도 좌표가 없어요.'),
                ),
              ),
            ),
          if (!KakaoMapConfig.isReady && hasCoordinates) ...[
            Positioned(
              left: 12,
              bottom: 14,
              child: _MapMarker(
                label: '출발 · ${widget.departureLabel}',
                icon: Icons.trip_origin,
                tone: DdubiColors.green,
              ),
            ),
            Positioned(
              right: 12,
              top: 14,
              child: _MapMarker(
                label: '도착 · ${widget.destinationLabel}',
                icon: Icons.location_on,
                tone: DdubiColors.red,
              ),
            ),
          ],
          Positioned(
            right: 12,
            bottom: 12,
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.end,
              children: [
                if (KakaoMapConfig.isReady && hasCoordinates) ...[
                  FloatingActionButton.small(
                    heroTag: 'saved-route-entire',
                    tooltip: '전체 경로 보기',
                    onPressed: _showEntireRoute,
                    backgroundColor: Colors.white,
                    foregroundColor: DdubiColors.blue,
                    child: const Icon(Icons.route_rounded),
                  ),
                  const SizedBox(height: 8),
                ],
                Container(
                  key: const Key('savedRouteMapModeBadge'),
                  padding: const EdgeInsets.symmetric(
                    horizontal: 9,
                    vertical: 6,
                  ),
                  decoration: BoxDecoration(
                    color: Colors.white.withValues(alpha: .94),
                    borderRadius: BorderRadius.circular(12),
                  ),
                  child: Text(
                    hasCoordinates
                        ? '서버 경로 좌표 · ${widget.routeMap.coordinates.length}개 점'
                        : '지도 좌표 없음',
                    style: const TextStyle(
                      fontSize: 10,
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _SavedRoutePolylinePainter extends CustomPainter {
  const _SavedRoutePolylinePainter(
    this.routeMap, {
    this.projectedSegments = const [],
  });

  final SavedRouteMap routeMap;
  final List<_ProjectedRouteSegment> projectedSegments;

  @override
  void paint(Canvas canvas, Size size) {
    if (projectedSegments.isNotEmpty) {
      for (final segment in projectedSegments) {
        _drawSegment(canvas, segment.mode, segment.points);
      }
      return;
    }
    final bounds = routeMap.bounds;
    if (bounds == null) return;
    const padding = 28.0;
    final width = size.width - padding * 2;
    final height = size.height - padding * 2;
    final lngRange = bounds.maxLng - bounds.minLng;
    final latRange = bounds.maxLat - bounds.minLat;

    Offset point(RouteCoordinate coordinate) => Offset(
      padding +
          (lngRange == 0
              ? width / 2
              : (coordinate.longitude - bounds.minLng) / lngRange * width),
      padding +
          (latRange == 0
              ? height / 2
              : (bounds.maxLat - coordinate.latitude) / latRange * height),
    );

    for (final segment in routeMap.segments) {
      if (segment.coordinates.length < 2) continue;
      final points = segment.coordinates.map(point).toList(growable: false);
      _drawSegment(canvas, segment.mode, points);
    }
  }

  void _drawSegment(Canvas canvas, String mode, List<Offset> points) {
    if (points.length < 2) return;
    final paint = Paint()
      ..color = _modeColor(mode)
      ..strokeWidth = mode == 'WALK' ? 5 : 7
      ..strokeCap = StrokeCap.round
      ..strokeJoin = StrokeJoin.round
      ..style = PaintingStyle.stroke;
    if (mode == 'WALK') {
      for (var index = 0; index < points.length - 1; index++) {
        final start = points[index];
        final end = points[index + 1];
        final distance = (end - start).distance;
        if (distance == 0) continue;
        final direction = (end - start) / distance;
        for (var offset = 0.0; offset < distance; offset += 12) {
          canvas.drawLine(
            start + direction * offset,
            start + direction * (offset + 6).clamp(0, distance),
            paint,
          );
        }
      }
    } else {
      final path = Path()..moveTo(points.first.dx, points.first.dy);
      for (final coordinate in points.skip(1)) {
        path.lineTo(coordinate.dx, coordinate.dy);
      }
      canvas.drawPath(path, paint);
    }
  }

  @override
  bool shouldRepaint(covariant _SavedRoutePolylinePainter oldDelegate) =>
      oldDelegate.routeMap != routeMap ||
      oldDelegate.projectedSegments != projectedSegments;
}

class _ProjectedRouteSegment {
  const _ProjectedRouteSegment({required this.mode, required this.points});

  final String mode;
  final List<Offset> points;
}

class _RouteMapView extends StatefulWidget {
  const _RouteMapView({
    required this.routes,
    required this.selectedKind,
    required this.departureLabel,
    required this.destinationLabel,
    required this.onSelected,
    this.showRouteSelector = true,
  });

  final List<RouteOption> routes;
  final RouteKind selectedKind;
  final String departureLabel;
  final String destinationLabel;
  final ValueChanged<RouteKind> onSelected;
  final bool showRouteSelector;

  @override
  State<_RouteMapView> createState() => _RouteMapViewState();
}

class _RouteMapViewState extends State<_RouteMapView> {
  KakaoMapController? _mapController;

  static const _departure = LatLng(latitude: 37.5089, longitude: 127.0631);
  static const _current = LatLng(latitude: 37.5045, longitude: 127.0490);
  static const _destination = LatLng(latitude: 37.5006, longitude: 127.0364);
  static const _center = LatLng(latitude: 37.5048, longitude: 127.0498);
  static const _cameraAnimation = CameraAnimation(
    duration: 450,
    autoElevation: true,
    isConsecutive: false,
  );

  int get _entireRouteLevel => kIsWeb ? 5 : 13;

  @override
  void didUpdateWidget(covariant _RouteMapView oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.selectedKind != widget.selectedKind) {
      _showEntireRoute();
    }
  }

  Future<void> _onMapCreated(KakaoMapController controller) async {
    _mapController = controller;
    try {
      if (!kIsWeb) {
        await controller.addMarkerLayer(
          layerId: KakaoMapController.defaultLabelLayerId,
          zOrder: 1000,
          clickable: false,
        );
      }
      await controller.addMarkers(
        markerOptions: [
          MarkerOption(id: 'departure', latLng: _departure, text: '출발'),
          MarkerOption(id: 'current', latLng: _current, text: '현재 위치'),
          MarkerOption(id: 'destination', latLng: _destination, text: '도착'),
        ],
      );
      await _showEntireRoute();
    } catch (error) {
      debugPrint('Kakao Maps marker setup failed: $error');
    }
  }

  Future<void> _showEntireRoute() =>
      _moveCamera(position: _center, zoomLevel: _entireRouteLevel);

  Future<void> _showCurrentLocation() async {
    final controller = _mapController;
    if (controller == null) return;
    try {
      final currentZoomLevel = await controller.getZoomLevel();
      if (currentZoomLevel == null) return;
      await _moveCamera(position: _current, zoomLevel: currentZoomLevel);
    } catch (error) {
      debugPrint('Kakao Maps zoom level lookup failed: $error');
    }
  }

  Future<void> _moveCamera({
    required LatLng position,
    required int zoomLevel,
  }) async {
    final controller = _mapController;
    if (controller == null) return;
    try {
      await controller.moveCamera(
        cameraUpdate: CameraUpdate(position: position, zoomLevel: zoomLevel),
        animation: _cameraAnimation,
      );
    } catch (error) {
      debugPrint('Kakao Maps camera move failed: $error');
    }
  }

  void _handleRouteSelected(RouteKind kind) {
    widget.onSelected(kind);
    if (kind == widget.selectedKind) {
      _showEntireRoute();
    }
  }

  @override
  Widget build(BuildContext context) => ClipRRect(
    borderRadius: BorderRadius.circular(16),
    child: LayoutBuilder(
      builder: (context, constraints) => Stack(
        children: [
          Positioned.fill(
            child: KakaoMapConfig.isReady
                ? KakaoMap(
                    initialPosition: _center,
                    initialLevel: _entireRouteLevel,
                    onMapCreated: _onMapCreated,
                  )
                : ColoredBox(
                    color: const Color(0xFFEAF2EC),
                    child: CustomPaint(
                      painter: _MapPainter(selectedKind: widget.selectedKind),
                    ),
                  ),
          ),
          if (widget.showRouteSelector)
            Positioned(
              left: 12,
              right: 12,
              top: 12,
              child: Material(
                color: Colors.white.withValues(alpha: .94),
                borderRadius: BorderRadius.circular(12),
                child: Padding(
                  padding: const EdgeInsets.all(8),
                  child: Wrap(
                    spacing: 6,
                    runSpacing: 6,
                    children: widget.routes
                        .map(
                          (route) => ChoiceChip(
                            label: Text(route.label),
                            selected: route.kind == widget.selectedKind,
                            avatar: CircleAvatar(
                              backgroundColor: _routeTone(route.kind),
                              radius: 5,
                            ),
                            onSelected: (_) => _handleRouteSelected(route.kind),
                          ),
                        )
                        .toList(),
                  ),
                ),
              ),
            ),
          if (!KakaoMapConfig.isReady) ...[
            Positioned(
              key: const Key('mapCurrentMarker'),
              left: constraints.maxWidth * .42,
              top: constraints.maxHeight * .54,
              child: const _MapMarker(
                label: '현재 위치 (Mock)',
                icon: Icons.my_location,
                tone: DdubiColors.blue,
              ),
            ),
            Positioned(
              key: const Key('mapDepartureMarker'),
              left: 12,
              bottom: 14,
              child: _MapMarker(
                label: '출발 · ${widget.departureLabel}',
                icon: Icons.trip_origin,
                tone: DdubiColors.green,
              ),
            ),
            Positioned(
              key: const Key('mapDestinationMarker'),
              right: 12,
              top: constraints.maxHeight * .22,
              child: _MapMarker(
                label: '도착 · ${widget.destinationLabel}',
                icon: Icons.location_on,
                tone: DdubiColors.red,
              ),
            ),
          ],
          Positioned(
            right: 12,
            bottom: 12,
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.end,
              children: [
                FloatingActionButton.small(
                  key: const Key('mapEntireRouteButton'),
                  heroTag: 'map-entire-route',
                  tooltip: '전체 경로 보기',
                  onPressed: KakaoMapConfig.isReady ? _showEntireRoute : null,
                  backgroundColor: Colors.white,
                  foregroundColor: DdubiColors.blue,
                  child: const Icon(Icons.route),
                ),
                const SizedBox(height: 8),
                FloatingActionButton.small(
                  key: const Key('mapCurrentLocationButton'),
                  heroTag: 'map-current-location',
                  tooltip: '현재 위치로 이동',
                  onPressed: KakaoMapConfig.isReady
                      ? _showCurrentLocation
                      : null,
                  backgroundColor: Colors.white,
                  foregroundColor: DdubiColors.blue,
                  child: const Icon(Icons.my_location),
                ),
                const SizedBox(height: 8),
                _MapModeBadge(isKakaoMap: KakaoMapConfig.isReady),
              ],
            ),
          ),
        ],
      ),
    ),
  );
}

class _MapModeBadge extends StatelessWidget {
  const _MapModeBadge({required this.isKakaoMap});

  final bool isKakaoMap;

  @override
  Widget build(BuildContext context) => Container(
    key: const Key('mapModeBadge'),
    padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 6),
    decoration: BoxDecoration(
      color: Colors.white.withValues(alpha: .92),
      borderRadius: BorderRadius.circular(12),
    ),
    child: Text(
      isKakaoMap ? 'Kakao Map · Mock 좌표' : 'Mock 지도 · Kakao 키 미설정',
      style: const TextStyle(fontSize: 10, fontWeight: FontWeight.w700),
    ),
  );
}

class _MapMarker extends StatelessWidget {
  const _MapMarker({
    required this.label,
    required this.icon,
    required this.tone,
  });

  final String label;
  final IconData icon;
  final Color tone;

  @override
  Widget build(BuildContext context) => Container(
    constraints: const BoxConstraints(maxWidth: 170),
    padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 7),
    decoration: BoxDecoration(
      color: Colors.white,
      borderRadius: BorderRadius.circular(18),
      boxShadow: const [
        BoxShadow(
          color: Color(0x24000000),
          blurRadius: 8,
          offset: Offset(0, 3),
        ),
      ],
    ),
    child: Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Icon(icon, color: tone, size: 18),
        const SizedBox(width: 5),
        Flexible(
          child: Text(
            label,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: const TextStyle(fontSize: 11, fontWeight: FontWeight.w700),
          ),
        ),
      ],
    ),
  );
}

class _RouteMapDetail extends StatelessWidget {
  const _RouteMapDetail({
    required this.route,
    required this.repository,
    required this.departureLabel,
    required this.destinationLabel,
  });

  final RouteOption route;
  final DdubiRepository repository;
  final String departureLabel;
  final String destinationLabel;

  @override
  Widget build(BuildContext context) => InfoCard(
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Row(
          children: [
            Expanded(child: _liveMetric('도착까지', route.detail)),
            Container(width: 1, height: 40, color: const Color(0xFFE0E8EE)),
            Expanded(child: _liveMetric('남은 거리', '약 6.2km')),
            Container(width: 1, height: 40, color: const Color(0xFFE0E8EE)),
            Expanded(child: _liveMetric('도착 예정', route.arrival)),
          ],
        ),
        const Divider(height: 25),
        Text(route.transport, style: Theme.of(context).textTheme.titleSmall),
        Text('${route.detail} · ${route.fare}'),
        const SizedBox(height: 10),
        ...[
          '${route.departure}  출발 · $departureLabel',
          '이동 중  ${route.transport}',
          '${route.arrival}  도착 · $destinationLabel',
        ].map(
          (text) => ListTile(
            dense: true,
            contentPadding: EdgeInsets.zero,
            leading: Icon(
              Icons.circle,
              size: 12,
              color: _routeTone(route.kind),
            ),
            title: Text(text),
          ),
        ),
        PrimaryButton(
          '이동 안내 시작',
          onPressed: () => _open(
            context,
            MovingScreen(repository: repository, route: route),
          ),
        ),
      ],
    ),
  );

  static Widget _liveMetric(String label, String value) => Column(
    children: [
      Text(
        label,
        style: const TextStyle(fontSize: 11, color: Color(0xFF62758A)),
      ),
      const SizedBox(height: 3),
      Text(
        value,
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
        style: const TextStyle(
          fontSize: 14,
          fontWeight: FontWeight.w900,
          color: DdubiColors.navy,
        ),
      ),
    ],
  );
}

class _RouteDeviationBanner extends StatelessWidget {
  const _RouteDeviationBanner({required this.onDismiss});
  final VoidCallback onDismiss;

  @override
  Widget build(BuildContext context) => Container(
    key: const Key('routeDeviationBanner'),
    margin: const EdgeInsets.fromLTRB(12, 4, 12, 8),
    padding: const EdgeInsets.fromLTRB(12, 10, 4, 10),
    decoration: BoxDecoration(
      color: const Color(0xFFFFECEE),
      borderRadius: BorderRadius.circular(15),
      border: Border.all(color: const Color(0xFFFFB8BD)),
    ),
    child: Row(
      children: [
        Container(
          width: 34,
          height: 34,
          decoration: const BoxDecoration(
            color: Color(0xFFFF5964),
            shape: BoxShape.circle,
          ),
          child: const Icon(
            Icons.priority_high_rounded,
            color: Colors.white,
            size: 22,
          ),
        ),
        const SizedBox(width: 10),
        const Expanded(
          child: Text(
            '추천 경로에서 벗어났어요.\n현재 위치를 확인해 주세요.',
            style: TextStyle(
              fontSize: 13,
              height: 1.35,
              fontWeight: FontWeight.w800,
              color: Color(0xFF9D2731),
            ),
          ),
        ),
        IconButton(
          key: const Key('dismissRouteDeviationBanner'),
          onPressed: onDismiss,
          icon: const Icon(Icons.close_rounded, color: Color(0xFF9D2731)),
        ),
      ],
    ),
  );
}

Color _routeTone(RouteKind kind) => switch (kind) {
  RouteKind.recommended => DdubiColors.green,
  RouteKind.fastest => DdubiColors.blue,
  RouteKind.safest => const Color(0xFF7257E8),
};

class _MapPainter extends CustomPainter {
  const _MapPainter({required this.selectedKind});

  final RouteKind selectedKind;

  @override
  void paint(Canvas canvas, Size size) {
    final road = Paint()
      ..color = Colors.white
      ..strokeWidth = 18
      ..style = PaintingStyle.stroke;
    final basePath = Path()
      ..moveTo(size.width * .2, size.height * .84)
      ..cubicTo(
        size.width * .55,
        size.height * .72,
        size.width * .18,
        size.height * .5,
        size.width * .58,
        size.height * .4,
      )
      ..cubicTo(
        size.width * .82,
        size.height * .32,
        size.width * .55,
        size.height * .16,
        size.width * .75,
        size.height * .08,
      );
    canvas.drawPath(basePath, road);

    _drawRoute(canvas, size, selectedKind, selected: true);
  }

  void _drawRoute(
    Canvas canvas,
    Size size,
    RouteKind kind, {
    required bool selected,
  }) {
    final offset = switch (kind) {
      RouteKind.recommended => 0.0,
      RouteKind.fastest => -18.0,
      RouteKind.safest => 18.0,
    };
    final path = Path()
      ..moveTo(size.width * .2, size.height * .84)
      ..cubicTo(
        size.width * .55 + offset,
        size.height * .72,
        size.width * .18 + offset,
        size.height * .5,
        size.width * .58,
        size.height * .4,
      )
      ..cubicTo(
        size.width * .82 + offset,
        size.height * .32,
        size.width * .55 + offset,
        size.height * .16,
        size.width * .75,
        size.height * .08,
      );
    final paint = Paint()
      ..color = _routeTone(kind).withValues(alpha: selected ? 1 : .32)
      ..strokeWidth = selected ? 8 : 5
      ..strokeCap = StrokeCap.round
      ..style = PaintingStyle.stroke;
    canvas.drawPath(path, paint);
  }

  @override
  bool shouldRepaint(covariant _MapPainter oldDelegate) =>
      oldDelegate.selectedKind != selectedKind;
}

/// 저장된 일정과 서버 재평가 결과를 조회한다. 교통 데이터가 없을 때 가상의 지연·대안을 만들지 않는다.
class DelayAlertScreen extends ConsumerStatefulWidget {
  const DelayAlertScreen({super.key, required this.repository});
  // 기존 라우트 호출과의 호환 인자다. 실제 조회는 scheduleRepositoryProvider를 사용한다.
  final DdubiRepository repository;
  @override
  ConsumerState<DelayAlertScreen> createState() => _DelayAlertScreenState();
}

class _DelayAlertScreenState extends ConsumerState<DelayAlertScreen> {
  late Future<List<Schedule>> _schedules;
  @override
  void initState() {
    super.initState();
    _schedules = ref.read(scheduleRepositoryProvider).schedules();
  }

  @override
  Widget build(BuildContext context) {
    final repository = ref.watch(scheduleRepositoryProvider);
    return AppPage(
      title: '일정별 교통 상황',
      child: FutureBuilder<List<Schedule>>(
        future: _schedules,
        builder: (context, snapshot) {
          if (snapshot.connectionState != ConnectionState.done) {
            return const Center(child: CircularProgressIndicator());
          }
          if (snapshot.hasError) {
            return Center(
              child: TextButton(
                onPressed: () =>
                    setState(() => _schedules = repository.schedules()),
                child: const Text('일정을 불러오지 못했습니다. 다시 시도'),
              ),
            );
          }
          if (repository is! ScheduleEvaluationRepository) {
            return const Center(
              child: Text('실제 교통 상황은 일정 API 연결 후 확인할 수 있습니다.'),
            );
          }
          final evaluationRepository =
              repository as ScheduleEvaluationRepository;
          final schedules = snapshot.data ?? <Schedule>[];
          if (schedules.isEmpty) {
            return const Center(child: Text('등록된 일정이 없습니다.'));
          }
          return ListView(
            padding: _pad,
            children: [
              const Text('각 일정의 최신 평가와 데이터 부족 사유를 확인하세요.'),
              for (final schedule in schedules) ...[
                const SizedBox(height: 16),
                Text(
                  schedule.title,
                  style: const TextStyle(fontWeight: FontWeight.bold),
                ),
                ScheduleEvaluationCard(
                  key: ValueKey(schedule.id),
                  repository: evaluationRepository,
                  scheduleId: schedule.id,
                ),
              ],
            ],
          );
        },
      ),
    );
  }
}

class SpacerBox extends StatelessWidget {
  const SpacerBox({super.key});
  @override
  Widget build(BuildContext context) => const SizedBox(height: 24);
}

class MovingScreen extends StatefulWidget {
  const MovingScreen({super.key, required this.repository, this.route});

  final DdubiRepository repository;

  final RouteOption? route;

  @override
  State<MovingScreen> createState() => _MovingScreenState();
}

class _MovingScreenState extends State<MovingScreen> {
  bool _hasDeparted = false;

  @override
  Widget build(BuildContext context) => AppPage(
    title: _hasDeparted ? '이동 중' : '출발 안내',
    actions: const [
      IconButton(onPressed: null, icon: Icon(Icons.notifications_none)),
    ],
    child: ListView(
      padding: _pad,
      children: _hasDeparted
          ? _movingContent(context)
          : _departureContent(context),
    ),
  );

  List<Widget> _departureContent(BuildContext context) => [
    const TtubeeGuideCard(
      '지금 출발하면 딱 좋아요!',
      compact: true,
      character: TtubeeRunning(asset: 'assets/images/ttubee/running.png'),
    ),
    const SizedBox(height: 12),
    InfoCard(
      color: const Color(0xFFF0FAF7),
      child: Column(
        children: [
          const CircleAvatar(
            radius: 32,
            backgroundColor: Colors.white,
            child: Icon(
              Icons.directions_walk,
              size: 36,
              color: DdubiColors.navy,
            ),
          ),
          const SizedBox(height: 15),
          Text('지금 출발하세요', style: Theme.of(context).textTheme.titleLarge),
          const Text('여유 있게 이동하면 제시간 도착해요.'),
        ],
      ),
    ),
    const SizedBox(height: 12),
    const InfoCard(
      child: ListTile(
        contentPadding: EdgeInsets.zero,
        leading: CircleAvatar(child: Icon(Icons.directions_walk)),
        title: Text('첫 번째 단계'),
        subtitle: Text('강남역까지 도보 6분\n450m'),
      ),
    ),
    const SizedBox(height: 12),
    const InfoCard(
      child: ListTile(
        contentPadding: EdgeInsets.zero,
        leading: Icon(Icons.check_circle, color: DdubiColors.green),
        title: Text('현재 지연 상태'),
        subtitle: StatusBadge('지연 위험 낮음 · 제시간 도착 예상'),
      ),
    ),
    const SizedBox(height: 28),
    PrimaryButton(
      '출발했어요',
      onPressed: () => setState(() => _hasDeparted = true),
    ),
  ];

  List<Widget> _movingContent(BuildContext context) {
    final route = widget.route;
    final transport = route?.transport ?? '지하철 2호선';
    final arrival = route?.arrival ?? '18:45';
    return [
      const TtubeeGuideCard(
        '목적지까지 뚜비가 함께할게요!',
        compact: true,
        character: TtubeeRunning(asset: 'assets/images/ttubee/running.png'),
      ),
      const SizedBox(height: 12),
      InfoCard(
        color: const Color(0xFFF0FAF7),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const StatusBadge(
              '현재 이동 단계',
              tone: DdubiColors.green,
              icon: Icons.directions_walk,
            ),
            const SizedBox(height: 12),
            Text('강남역으로 이동 중', style: Theme.of(context).textTheme.titleLarge),
            const SizedBox(height: 4),
            const Text('도보 6분 · 450m'),
            const SizedBox(height: 14),
            const LinearProgressIndicator(value: .18),
          ],
        ),
      ),
      const SizedBox(height: 12),
      InfoCard(
        child: Row(
          children: [
            const Icon(Icons.schedule, color: DdubiColors.blue),
            const SizedBox(width: 10),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Text('예상 도착'),
                  Text(
                    '$arrival · 제시간 도착 예상',
                    style: const TextStyle(fontWeight: FontWeight.w800),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
      const SizedBox(height: 12),
      InfoCard(
        child: ListTile(
          contentPadding: EdgeInsets.zero,
          leading: const CircleAvatar(child: Icon(Icons.subway)),
          title: const Text('다음 단계'),
          subtitle: Text('강남역에서 $transport 탑승'),
        ),
      ),
      const SizedBox(height: 12),
      const InfoCard(
        child: ListTile(
          contentPadding: EdgeInsets.zero,
          leading: Icon(Icons.check_circle, color: DdubiColors.green),
          title: Text('현재 지연 상태'),
          subtitle: StatusBadge('지연 위험 낮음 · 제시간 도착 예상'),
        ),
      ),
      const SizedBox(height: 28),
      PrimaryButton(
        '도착했어요',
        onPressed: () =>
            _open(context, ArrivalScreen(repository: widget.repository)),
      ),
    ];
  }
}

class ArrivalScreen extends StatelessWidget {
  const ArrivalScreen({super.key, required this.repository});
  final DdubiRepository repository;
  @override
  Widget build(BuildContext c) => AppPage(
    title: '',
    child: ListView(
      padding: _pad,
      children: [
        const TtubeeGuideCard(
          '제시간에 도착했어요!\n오늘 이동도 정말 잘했어요.',
          character: TtubeeCelebrate(
            asset: 'assets/images/ttubee/celebrate.png',
            height: 82,
          ),
        ),
        const SizedBox(height: 18),
        const Icon(Icons.check_circle, color: Color(0xFF12B89A), size: 70),
        Center(
          child: Text(
            '제시간에 도착했어요',
            style: Theme.of(c).textTheme.titleLarge
                ?.copyWith(color: const Color(0xFF008A72)),
          ),
        ),
        const Center(child: Text('모든 도착!')),
        const SizedBox(height: 22),
        const InfoCard(
          child: Column(
            children: [
              ListTile(title: Text('계획 출발'), trailing: Text('오전 9:00')),
              ListTile(title: Text('실제 출발'), trailing: Text('오전 9:02  (+2분)')),
              Divider(),
              ListTile(title: Text('계획 도착'), trailing: Text('오전 9:39')),
              ListTile(
                title: Text('실제 도착'),
                trailing: Text(
                  '오전 9:37  (-2분)',
                  style: TextStyle(color: DdubiColors.green),
                ),
              ),
            ],
          ),
        ),
        const SizedBox(height: 12),
        InfoCard(
          color: const Color(0xFFF0FAF7),
          child: Column(
            children: [
              const Text('절약한 시간'),
              Text(
                '2분',
                style: Theme.of(c).textTheme.headlineLarge
                    ?.copyWith(color: const Color(0xFF008A72)),
              ),
            ],
          ),
        ),
        const SizedBox(height: 22),
        const Center(child: Text('이번 경험은 어땠나요?')),
        const SizedBox(height: 10),
        Row(
          children: [
            Expanded(
              child: OutlinedButton.icon(
                onPressed: () => Navigator.popUntil(c, (r) => r.isFirst),
                icon: const Icon(Icons.sentiment_satisfied_alt),
                label: const Text('만족해요'),
              ),
            ),
            const SizedBox(width: 10),
            Expanded(
              child: OutlinedButton.icon(
                onPressed: () {},
                icon: const Icon(Icons.sentiment_dissatisfied_outlined),
                label: const Text('아쉬워요'),
              ),
            ),
          ],
        ),
      ],
    ),
  );
}

class RepeatSchedulesScreen extends StatefulWidget {
  const RepeatSchedulesScreen({
    super.key,
    required this.repository,
    required this.placeSearchService,
  });

  final RepeatScheduleRepository repository;
  final PlaceSearchService placeSearchService;

  @override
  State<RepeatSchedulesScreen> createState() => _RepeatSchedulesScreenState();
}

class _RepeatSchedulesScreenState extends State<RepeatSchedulesScreen> {
  late Future<List<RepeatScheduleRule>> _rules;

  @override
  void initState() {
    super.initState();
    _reload();
  }

  void _reload() => _rules = widget.repository.repeatSchedules();

  @override
  Widget build(BuildContext context) => AppPage(
    title: '반복 일정 관리',
    actions: [
      IconButton(
        tooltip: '반복 일정 추가',
        onPressed: () async {
          final saved = await context.push<bool>('/schedules/repeat/new');
          if (saved == true && mounted) setState(_reload);
        },
        icon: const Icon(Icons.add),
      ),
    ],
    child: FutureBuilder<List<RepeatScheduleRule>>(
      future: _rules,
      builder: (context, snapshot) {
        if (snapshot.hasError) {
          return const DataLoadError(message: '반복 일정을 불러오지 못했어요.');
        }
        if (!snapshot.hasData) {
          return const Center(child: CircularProgressIndicator());
        }
        final rules = snapshot.data!;
        if (rules.isEmpty) {
          return const Center(child: Text('등록된 반복 일정이 없어요.'));
        }
        return ListView.separated(
          padding: _pad,
          itemCount: rules.length,
          separatorBuilder: (_, _) => const SizedBox(height: 10),
          itemBuilder: (context, index) {
            final rule = rules[index];
            final days = rule.weekdays.toList()..sort();
            return InfoCard(
              child: ListTile(
                contentPadding: EdgeInsets.zero,
                leading: Icon(
                  Icons.event_repeat,
                  color: rule.active ? DdubiColors.blue : Colors.grey,
                ),
                title: Row(
                  children: [
                    Expanded(
                      child: Text(
                        rule.title,
                        style: const TextStyle(fontWeight: FontWeight.w800),
                      ),
                    ),
                    StatusBadge(
                      rule.active ? '반복 중' : '중지됨',
                      tone: rule.active ? DdubiColors.green : Colors.grey,
                    ),
                  ],
                ),
                subtitle: Text(
                  '${days.map((day) => _weekdays[day - 1]).join(' · ')}  ${rule.time}\n'
                  '출발 ${rule.departurePlace}\n도착 ${rule.place}',
                ),
                isThreeLine: true,
                trailing: PopupMenuButton<String>(
                  onSelected: (value) =>
                      value == 'edit' ? _edit(rule) : _deactivate(rule),
                  itemBuilder: (_) => [
                    const PopupMenuItem(value: 'edit', child: Text('수정')),
                    if (rule.active)
                      const PopupMenuItem(value: 'stop', child: Text('반복 중지')),
                  ],
                ),
              ),
            );
          },
        );
      },
    ),
  );

  Future<void> _edit(RepeatScheduleRule rule) async {
    final changed = await context.push<bool>(
      '/schedules/repeats/${rule.id}/edit',
    );
    if (changed == true && mounted) setState(_reload);
  }

  Future<void> _deactivate(RepeatScheduleRule rule) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('반복을 중지할까요?'),
        content: Text('${rule.title}의 새 일정 생성을 중지합니다.'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(dialogContext, false),
            child: const Text('취소'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(dialogContext, true),
            child: const Text('중지'),
          ),
        ],
      ),
    );
    if (confirmed != true) return;
    try {
      await widget.repository.deactivateRepeatSchedule(rule.id);
      if (mounted) setState(_reload);
    } on Object {
      if (!mounted) return;
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('반복 일정을 중지하지 못했어요.')));
    }
  }
}

class RepeatScheduleEditScreen extends StatelessWidget {
  const RepeatScheduleEditScreen({
    super.key,
    required this.repository,
    required this.placeSearchService,
    required this.repeatId,
  });

  final RepeatScheduleRepository repository;
  final PlaceSearchService placeSearchService;
  final String repeatId;

  @override
  Widget build(BuildContext context) => FutureBuilder<RepeatScheduleRule>(
    future: repository.repeatScheduleById(repeatId),
    builder: (context, snapshot) {
      if (snapshot.hasError) {
        return const AppPage(
          title: '반복 일정 수정',
          child: DataLoadError(message: '반복 일정을 불러오지 못했어요.'),
        );
      }
      if (!snapshot.hasData) {
        return const AppPage(
          title: '반복 일정 수정',
          child: Center(child: CircularProgressIndicator()),
        );
      }
      return RepeatScheduleFormScreen(
        repository: repository,
        placeSearchService: placeSearchService,
        rule: snapshot.data,
      );
    },
  );
}

class SavedPlacesScreen extends StatefulWidget {
  const SavedPlacesScreen({
    super.key,
    required this.api,
    required this.searchService,
  });

  final PlaceApi api;
  final PlaceSearchService searchService;

  @override
  State<SavedPlacesScreen> createState() => _SavedPlacesScreenState();
}

class _SavedPlacesScreenState extends State<SavedPlacesScreen> {
  late Future<List<SavedPlace>> _places;

  @override
  void initState() {
    super.initState();
    _reload();
  }

  void _reload() => _places = widget.api.list();

  @override
  Widget build(BuildContext context) => AppPage(
    title: '저장 장소',
    actions: [
      IconButton(
        key: const Key('addSavedPlaceButton'),
        tooltip: '장소 추가',
        onPressed: _add,
        icon: const Icon(Icons.add_location_alt_outlined),
      ),
    ],
    child: FutureBuilder<List<SavedPlace>>(
      future: _places,
      builder: (context, snapshot) {
        if (snapshot.hasError) {
          return const DataLoadError(message: '저장 장소를 불러오지 못했어요.');
        }
        if (!snapshot.hasData) {
          return const Center(child: CircularProgressIndicator());
        }
        final places = snapshot.data!;
        if (places.isEmpty) {
          return const Center(child: Text('저장한 장소가 없어요.'));
        }
        return ListView.separated(
          padding: _pad,
          itemCount: places.length,
          separatorBuilder: (_, _) => const Divider(height: 1),
          itemBuilder: (context, index) {
            final place = places[index];
            return ListTile(
              leading: const Icon(Icons.bookmark_outline),
              title: Text(place.name),
              subtitle: Text(place.address ?? '주소 정보 없음'),
              onTap: () => _showDetail(place),
              trailing: PopupMenuButton<String>(
                onSelected: (value) =>
                    value == 'rename' ? _rename(place) : _delete(place),
                itemBuilder: (_) => const [
                  PopupMenuItem(value: 'rename', child: Text('이름 수정')),
                  PopupMenuItem(value: 'delete', child: Text('삭제')),
                ],
              ),
            );
          },
        );
      },
    ),
  );

  Future<void> _add() async {
    final selected = await showPlaceSearchDialog(
      context,
      title: '저장할 장소 검색',
      searchService: widget.searchService,
    );
    if (selected == null) return;
    try {
      await widget.api.create(selected);
      if (mounted) setState(_reload);
    } on Object {
      if (!mounted) return;
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('장소를 저장하지 못했어요.')));
    }
  }

  Future<void> _showDetail(SavedPlace place) async {
    try {
      final detail = await widget.api.get(place.id);
      if (!mounted) return;
      await showDialog<void>(
        context: context,
        builder: (dialogContext) => AlertDialog(
          title: Text(detail.name),
          content: Text(
            '${detail.address ?? '주소 정보 없음'}\n'
            '${detail.lat}, ${detail.lng}',
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(dialogContext),
              child: const Text('확인'),
            ),
          ],
        ),
      );
    } on Object {
      if (!mounted) return;
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('장소 상세를 불러오지 못했어요.')));
    }
  }

  Future<void> _rename(SavedPlace place) async {
    final controller = TextEditingController(text: place.name);
    final name = await showDialog<String>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('장소 이름 수정'),
        content: TextField(controller: controller, autofocus: true),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(dialogContext),
            child: const Text('취소'),
          ),
          FilledButton(
            onPressed: () =>
                Navigator.pop(dialogContext, controller.text.trim()),
            child: const Text('저장'),
          ),
        ],
      ),
    );
    controller.dispose();
    if (name == null || name.isEmpty || name == place.name) return;
    try {
      await widget.api.update(place.id, name: name);
      if (mounted) setState(_reload);
    } on Object {
      if (!mounted) return;
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('장소 이름을 수정하지 못했어요.')));
    }
  }

  Future<void> _delete(SavedPlace place) async {
    try {
      await widget.api.delete(place.id);
      if (mounted) setState(_reload);
    } on Object {
      if (!mounted) return;
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('장소를 삭제하지 못했어요.')));
    }
  }
}

class TravelRecordsScreen extends StatefulWidget {
  const TravelRecordsScreen({super.key, required this.api});
  final ReportApi api;

  @override
  State<TravelRecordsScreen> createState() => _TravelRecordsScreenState();
}

class _TravelRecordsData {
  const _TravelRecordsData(this.history, this.reports);
  final TravelHistory history;
  final List<WeeklyReport> reports;
}

class _TravelRecordsScreenState extends State<TravelRecordsScreen> {
  late Future<_TravelRecordsData> _data;

  @override
  void initState() {
    super.initState();
    final now = DateTime.now();
    final from = DateTime(now.year, now.month - 2, 1);
    final to = DateTime(now.year, now.month + 1, 0);
    _data =
        Future.wait([
          widget.api.history(from: from, to: to),
          widget.api.reports(),
        ]).then(
          (values) => _TravelRecordsData(
            values[0] as TravelHistory,
            values[1] as List<WeeklyReport>,
          ),
        );
  }

  @override
  Widget build(BuildContext context) => AppPage(
    title: '이동 기록 · 리포트',
    child: FutureBuilder<_TravelRecordsData>(
      future: _data,
      builder: (context, snapshot) {
        if (snapshot.hasError) {
          return const DataLoadError(message: '이동 기록을 불러오지 못했어요.');
        }
        if (!snapshot.hasData) {
          return const Center(child: CircularProgressIndicator());
        }
        final history = snapshot.data!.history;
        final reports = snapshot.data!.reports;
        return ListView(
          padding: _pad,
          children: [
            InfoCard(
              child: Row(
                mainAxisAlignment: MainAxisAlignment.spaceAround,
                children: [
                  _recordMetric('전체', '${history.summary.totalCount}건'),
                  _recordMetric(
                    '정시율',
                    '${(history.summary.onTimeRate * 100).round()}%',
                  ),
                  _recordMetric('지연', '${history.summary.lateCount}건'),
                ],
              ),
            ),
            const SizedBox(height: 18),
            const SectionHeader('주간 리포트'),
            if (reports.isEmpty)
              const Padding(
                padding: EdgeInsets.symmetric(vertical: 14),
                child: Text('아직 생성된 리포트가 없어요.'),
              )
            else
              ...reports.map(
                (report) => ListTile(
                  leading: const Icon(Icons.insights_outlined),
                  title: Text(
                    '${report.periodStart.month}.${report.periodStart.day} '
                    '~ ${report.periodEnd.month}.${report.periodEnd.day}',
                  ),
                  subtitle: Text(
                    '전체 ${report.totalSchedules} · 정시 ${report.onTimeEstimated} · '
                    '빠듯 ${report.tightEstimated} · 지연 ${report.delayedEstimated}',
                  ),
                  onTap: () => _showReport(report),
                ),
              ),
            const SizedBox(height: 18),
            const SectionHeader('최근 이동 기록'),
            if (history.items.isEmpty)
              const Padding(
                padding: EdgeInsets.symmetric(vertical: 14),
                child: Text('최근 이동 기록이 없어요.'),
              )
            else
              ...history.items.map(_historyTile),
          ],
        );
      },
    ),
  );

  Widget _recordMetric(String label, String value) => Column(
    children: [
      Text(label),
      const SizedBox(height: 4),
      Text(value, style: const TextStyle(fontWeight: FontWeight.w800)),
    ],
  );

  Widget _historyTile(TravelHistoryItem item) => ListTile(
    leading: const Icon(Icons.directions_transit_outlined),
    title: Text(item.title),
    subtitle: Text(_fullDate(item.arriveBy)),
    trailing: Text(_arrivalResultText(item.result)),
  );

  Future<void> _showReport(WeeklyReport report) async {
    try {
      final details = await widget.api.reportDetails(report.id);
      if (!mounted) return;
      await showModalBottomSheet<void>(
        context: context,
        showDragHandle: true,
        builder: (context) => ListView(
          padding: _pad,
          children: [
            Text('주간 리포트 상세', style: Theme.of(context).textTheme.titleLarge),
            const SizedBox(height: 12),
            if (details.isEmpty) const Text('이 주의 이동 기록이 없어요.'),
            ...details.map(_historyTile),
          ],
        ),
      );
    } on Object {
      if (!mounted) return;
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('리포트 상세를 불러오지 못했어요.')));
    }
  }
}

class NotificationDetailScreen extends StatefulWidget {
  const NotificationDetailScreen({
    super.key,
    required this.api,
    required this.notificationId,
  });

  final NotificationApi api;
  final String notificationId;

  @override
  State<NotificationDetailScreen> createState() =>
      _NotificationDetailScreenState();
}

class _NotificationDetailScreenState extends State<NotificationDetailScreen> {
  late final Future<AppNotification> notification = _load();

  Future<AppNotification> _load() async {
    final item = await widget.api.getNotification(widget.notificationId);
    if (item.isUnread) {
      await widget.api.markRead(item.id);
    }
    return item;
  }

  @override
  Widget build(BuildContext context) => AppPage(
    title: '알림 상세',
    child: FutureBuilder<AppNotification>(
      future: notification,
      builder: (context, snapshot) {
        if (snapshot.hasError) {
          return const DataLoadError(message: '알림을 불러오지 못했어요.');
        }
        if (!snapshot.hasData) {
          return const Center(child: CircularProgressIndicator());
        }

        final item = snapshot.data!;
        return ListView(
          padding: _pad,
          children: [
            Text(item.title, style: Theme.of(context).textTheme.headlineSmall),
            const SizedBox(height: 12),
            Text(item.body),
            const SizedBox(height: 20),
            if (item.scheduleId != null)
              FilledButton.icon(
                onPressed: () => context.go('/schedules/${item.scheduleId}'),
                icon: const Icon(Icons.calendar_today_outlined),
                label: const Text('연결된 일정 보기'),
              ),
          ],
        );
      },
    ),
  );
}

class AccountManagementScreen extends StatefulWidget {
  const AccountManagementScreen({
    super.key,
    required this.userApi,
    required this.authRepository,
    this.kakaoLoginService,
    this.googleLoginService,
  });

  final UserApi userApi;
  final AuthRepository authRepository;
  final KakaoLoginService? kakaoLoginService;
  final GoogleLoginService? googleLoginService;

  @override
  State<AccountManagementScreen> createState() =>
      _AccountManagementScreenState();
}

class _AccountManagementScreenState extends State<AccountManagementScreen> {
  late Future<UserProfile> _profile = widget.userApi.getMe();
  Set<String> _linkedProviders = {};
  bool _socialProvidersResolved = false;
  bool _socialBusy = false;

  @override
  Widget build(BuildContext context) => AppPage(
    title: '계정 관리',
    child: FutureBuilder<UserProfile>(
      future: _profile,
      builder: (context, snapshot) {
        if (snapshot.hasError) {
          return const DataLoadError(message: '계정 정보를 불러오지 못했어요.');
        }
        if (!snapshot.hasData) {
          return const Center(child: CircularProgressIndicator());
        }
        final user = snapshot.data!;
        return ListView(
          padding: _pad,
          children: [
            InfoCard(
              child: Column(
                children: [
                  ListTile(
                    contentPadding: EdgeInsets.zero,
                    leading: const Icon(Icons.person_outline_rounded),
                    title: const Text('닉네임'),
                    subtitle: Text(user.nickname),
                    trailing: const Icon(Icons.edit_outlined),
                    onTap: () => _editNickname(user),
                  ),
                  const Divider(),
                  ListTile(
                    contentPadding: EdgeInsets.zero,
                    leading: const Icon(Icons.mail_outline_rounded),
                    title: const Text('이메일'),
                    subtitle: Text(user.email ?? '등록된 이메일이 없어요'),
                    trailing: user.email == null
                        ? const Text(
                            '등록',
                            style: TextStyle(
                              color: Color(0xFF0AAE9D),
                              fontWeight: FontWeight.w800,
                            ),
                          )
                        : null,
                    onTap: user.email == null ? _showEmailRegistration : null,
                  ),
                  const Divider(),
                  ListTile(
                    key: const Key('changePasswordMenuButton'),
                    contentPadding: EdgeInsets.zero,
                    leading: const Icon(Icons.lock_reset_rounded),
                    title: const Text('비밀번호 변경'),
                    subtitle: const Text('새 비밀번호로 안전하게 변경해요.'),
                    trailing: const Icon(Icons.chevron_right_rounded),
                    onTap: _showPasswordChange,
                  ),
                ],
              ),
            ),
            const SizedBox(height: 16),
            _socialAccountsCard(user),
            const SizedBox(height: 16),
            InfoCard(
              child: Column(
                children: [
                  ListTile(
                    key: const Key('withdrawalMenuButton'),
                    contentPadding: EdgeInsets.zero,
                    leading: const Icon(
                      Icons.person_remove_outlined,
                      color: DdubiColors.red,
                    ),
                    title: const Text(
                      '회원 탈퇴',
                      style: TextStyle(color: DdubiColors.red),
                    ),
                    onTap: _withdraw,
                  ),
                ],
              ),
            ),
          ],
        );
      },
    ),
  );

  Future<void> _editNickname(UserProfile user) async {
    final controller = TextEditingController(text: user.nickname);
    final nickname = await showDialog<String>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('닉네임 변경'),
        content: TextField(
          controller: controller,
          autofocus: true,
          maxLength: 20,
          decoration: const InputDecoration(labelText: '닉네임'),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(dialogContext),
            child: const Text('취소'),
          ),
          FilledButton(
            onPressed: () =>
                Navigator.pop(dialogContext, controller.text.trim()),
            child: const Text('저장'),
          ),
        ],
      ),
    );
    controller.dispose();
    if (nickname == null || nickname.isEmpty || nickname == user.nickname) {
      return;
    }
    try {
      final updated = await widget.userApi.update(nickname: nickname);
      if (mounted) setState(() => _profile = Future.value(updated));
    } on Object {
      if (mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(const SnackBar(content: Text('닉네임을 변경하지 못했어요.')));
      }
    }
  }

  Widget _socialAccountsCard(UserProfile user) {
    final providers = _socialProvidersResolved
        ? _linkedProviders
        : <String>{if (user.provider != null) user.provider!};
    return InfoCard(
      color: const Color(0xFFF4F8FF),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const SectionHeader('소셜 계정 연결'),
          const SizedBox(height: 8),
          if (providers.isNotEmpty)
            Text(
              '현재 로그인 수단: ${providers.map(_providerLabel).join(', ')}',
              style: const TextStyle(
                color: DdubiColors.navy,
                fontWeight: FontWeight.w800,
              ),
            ),
          const SizedBox(height: 10),
          _socialAccountTile(
            provider: 'KAKAO',
            label: '카카오',
            connected: providers.contains('KAKAO'),
            enabled: widget.kakaoLoginService != null,
          ),
          const Divider(),
          _socialAccountTile(
            provider: 'GOOGLE',
            label: 'Google',
            connected: providers.contains('GOOGLE'),
            enabled: widget.googleLoginService != null,
          ),
          const Divider(),
          const ListTile(
            contentPadding: EdgeInsets.zero,
            leading: Icon(Icons.account_circle_outlined),
            title: Text('네이버'),
            subtitle: Text('마이페이지 연결 API와 인증 방식 협의가 필요해요.'),
            trailing: Text('준비 중'),
          ),
          if (_socialBusy) const LinearProgressIndicator(),
        ],
      ),
    );
  }

  Widget _socialAccountTile({
    required String provider,
    required String label,
    required bool connected,
    required bool enabled,
  }) => ListTile(
    contentPadding: EdgeInsets.zero,
    leading: Icon(
      connected ? Icons.verified_rounded : Icons.link_rounded,
      color: connected ? DdubiColors.green : DdubiColors.navy,
    ),
    title: Text(label),
    subtitle: Text(connected ? '연결됨' : '계정을 연결할 수 있어요.'),
    trailing: TextButton(
      onPressed: _socialBusy || !enabled
          ? null
          : () =>
                connected ? _unlinkSocial(provider) : _connectSocial(provider),
      child: Text(connected ? '연결 해제' : '연결'),
    ),
  );

  Future<void> _connectSocial(String provider) async {
    setState(() => _socialBusy = true);
    try {
      final List<String> providers;
      if (provider == 'KAKAO') {
        final token = await widget.kakaoLoginService!.login(context);
        providers = await widget.authRepository.linkSocialAccount(
          provider: provider,
          accessToken: token,
        );
      } else {
        final token = await widget.googleLoginService!.login(context);
        providers = await widget.authRepository.linkSocialAccount(
          provider: provider,
          idToken: token,
        );
      }
      if (!mounted) return;
      setState(() {
        _linkedProviders = providers.toSet();
        _socialProvidersResolved = true;
      });
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('${_providerLabel(provider)} 계정을 연결했어요.')),
      );
    } on Object {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('${_providerLabel(provider)} 계정을 연결하지 못했어요.')),
        );
      }
    } finally {
      if (mounted) setState(() => _socialBusy = false);
    }
  }

  Future<void> _unlinkSocial(String provider) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: Text('${_providerLabel(provider)} 연결을 해제할까요?'),
        content: const Text('마지막 로그인 수단은 계정 보호를 위해 해제할 수 없어요.'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(dialogContext, false),
            child: const Text('취소'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(dialogContext, true),
            child: const Text('연결 해제'),
          ),
        ],
      ),
    );
    if (confirmed != true) return;
    setState(() => _socialBusy = true);
    try {
      final providers = await widget.authRepository.unlinkSocialAccount(
        provider,
      );
      if (!mounted) return;
      setState(() {
        _linkedProviders = providers.toSet();
        _socialProvidersResolved = true;
      });
    } on Object {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('연결을 해제하지 못했어요. 로그인 수단을 확인해주세요.')),
        );
      }
    } finally {
      if (mounted) setState(() => _socialBusy = false);
    }
  }

  static String _providerLabel(String provider) => switch (provider) {
    'LOCAL' => '이메일',
    'KAKAO' => '카카오',
    'NAVER' => '네이버',
    'GOOGLE' => 'Google',
    _ => provider,
  };

  Future<void> _showEmailRegistration() => _showUnavailableAccountFeature(
    title: '이메일 등록',
    icon: Icons.mark_email_unread_outlined,
    description:
        '이메일 인증 후 현재 계정에 연결할 수 있도록 화면을 준비했어요. '
        '계정 연결 API가 제공되면 이곳에서 바로 등록할 수 있어요.',
  );

  Future<void> _showPasswordChange() => _showUnavailableAccountFeature(
    title: '비밀번호 변경',
    icon: Icons.lock_reset_rounded,
    description:
        '현재 비밀번호 확인과 새 비밀번호 입력 화면을 준비했어요. '
        '비밀번호 변경 API가 제공되면 이곳에서 안전하게 변경할 수 있어요.',
  );

  Future<void> _showUnavailableAccountFeature({
    required String title,
    required IconData icon,
    required String description,
  }) => showModalBottomSheet<void>(
    context: context,
    useSafeArea: true,
    showDragHandle: true,
    builder: (sheetContext) => Padding(
      padding: EdgeInsets.fromLTRB(
        24,
        8,
        24,
        24 + MediaQuery.viewInsetsOf(sheetContext).bottom,
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          CircleAvatar(
            radius: 31,
            backgroundColor: const Color(0xFFDDF8F3),
            child: Icon(icon, color: const Color(0xFF0CB3A2), size: 32),
          ),
          const SizedBox(height: 14),
          Text(
            title,
            style: const TextStyle(
              color: Color(0xFF102454),
              fontSize: 22,
              fontWeight: FontWeight.w900,
            ),
          ),
          const SizedBox(height: 9),
          Text(
            description,
            textAlign: TextAlign.center,
            style: const TextStyle(color: Color(0xFF64708B), height: 1.55),
          ),
          const SizedBox(height: 20),
          FilledButton(
            onPressed: () => Navigator.pop(sheetContext),
            style: FilledButton.styleFrom(
              backgroundColor: const Color(0xFF10B8A5),
              minimumSize: const Size.fromHeight(50),
              shape: const StadiumBorder(),
            ),
            child: const Text('확인'),
          ),
        ],
      ),
    ),
  );

  Future<void> _withdraw() async {
    final confirmed = await showDialog<bool>(
      context: context,
      barrierColor: const Color(0x730B1C2E),
      builder: (dialogContext) => Dialog(
        key: const Key('withdrawalConfirmationDialog'),
        backgroundColor: Colors.transparent,
        elevation: 0,
        insetPadding: const EdgeInsets.symmetric(horizontal: 26),
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 400),
          child: SingleChildScrollView(
            child: Container(
              padding: const EdgeInsets.fromLTRB(20, 20, 20, 20),
              decoration: BoxDecoration(
                color: Colors.white,
                borderRadius: BorderRadius.circular(30),
                border: Border.all(color: const Color(0xFFD7F1ED)),
                boxShadow: const [
                  BoxShadow(
                    color: Color(0x33102A43),
                    blurRadius: 30,
                    offset: Offset(0, 14),
                  ),
                ],
              ),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  SizedBox(
                    height: 178,
                    child: Stack(
                      alignment: Alignment.bottomCenter,
                      children: [
                        Align(
                          alignment: const Alignment(0, .18),
                          child: Container(
                            width: 270,
                            height: 132,
                            decoration: BoxDecoration(
                              gradient: const LinearGradient(
                                begin: Alignment.topLeft,
                                end: Alignment.bottomRight,
                                colors: [Color(0xFFE5FAF7), Color(0xFFCFF4EE)],
                              ),
                              borderRadius: BorderRadius.circular(80),
                            ),
                          ),
                        ),
                        Positioned(
                          left: 16,
                          bottom: 18,
                          child: Container(
                            width: 86,
                            height: 86,
                            decoration: const BoxDecoration(
                              color: Color(0xFFDCF8F4),
                              shape: BoxShape.circle,
                            ),
                          ),
                        ),
                        Positioned(
                          right: 22,
                          bottom: 25,
                          child: Container(
                            width: 76,
                            height: 76,
                            decoration: const BoxDecoration(
                              color: Color(0xFFD7F6F1),
                              shape: BoxShape.circle,
                            ),
                          ),
                        ),
                        Image.asset(
                          'assets/images/ttubee/withdrawal-confirmation.png',
                          key: const Key('withdrawalCharacter'),
                          height: 174,
                          fit: BoxFit.contain,
                          filterQuality: FilterQuality.high,
                          semanticLabel: '탈퇴를 재확인하는 슬픈 뚜비',
                        ),
                        Positioned(
                          top: 12,
                          right: 44,
                          child: Container(
                            width: 42,
                            height: 42,
                            decoration: BoxDecoration(
                              color: const Color(0xFFFF5964),
                              borderRadius: BorderRadius.circular(14),
                              boxShadow: const [
                                BoxShadow(
                                  color: Color(0x33FF5964),
                                  blurRadius: 10,
                                  offset: Offset(0, 4),
                                ),
                              ],
                            ),
                            child: const Icon(
                              Icons.priority_high_rounded,
                              color: Colors.white,
                              size: 29,
                            ),
                          ),
                        ),
                      ],
                    ),
                  ),
                  const SizedBox(height: 12),
                  const Text(
                    '정말 탈퇴하시겠습니까?',
                    textAlign: TextAlign.center,
                    style: TextStyle(
                      color: Color(0xFF102454),
                      fontSize: 23,
                      height: 1.25,
                      fontWeight: FontWeight.w900,
                      letterSpacing: -.6,
                    ),
                  ),
                  const SizedBox(height: 10),
                  const Text(
                    '탈퇴하면 계정 정보와 이용 기록이 삭제돼요.\n'
                    '필요한 내용이 있다면 먼저 확인해 주세요.',
                    textAlign: TextAlign.center,
                    style: TextStyle(
                      color: Color(0xFF66738D),
                      fontSize: 13.5,
                      height: 1.55,
                    ),
                  ),
                  const SizedBox(height: 22),
                  Row(
                    children: [
                      Expanded(
                        child: SizedBox(
                          height: 52,
                          child: TextButton(
                            key: const Key('withdrawalCancelButton'),
                            onPressed: () =>
                                Navigator.pop(dialogContext, false),
                            style: TextButton.styleFrom(
                              foregroundColor: const Color(0xFF079B8D),
                              backgroundColor: const Color(0xFFE6F8F5),
                              shape: RoundedRectangleBorder(
                                borderRadius: BorderRadius.circular(18),
                              ),
                            ),
                            child: const Text(
                              '취소',
                              style: TextStyle(fontWeight: FontWeight.w800),
                            ),
                          ),
                        ),
                      ),
                      const SizedBox(width: 10),
                      Expanded(
                        child: SizedBox(
                          height: 52,
                          child: FilledButton(
                            key: const Key('withdrawalConfirmButton'),
                            onPressed: () => Navigator.pop(dialogContext, true),
                            style: FilledButton.styleFrom(
                              backgroundColor: const Color(0xFFFF4E5A),
                              foregroundColor: Colors.white,
                              shape: RoundedRectangleBorder(
                                borderRadius: BorderRadius.circular(18),
                              ),
                            ),
                            child: const Text(
                              '탈퇴하기',
                              style: TextStyle(fontWeight: FontWeight.w900),
                            ),
                          ),
                        ),
                      ),
                    ],
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
    if (confirmed != true) return;
    try {
      await widget.userApi.withdraw();
      if (mounted) context.go('/login');
    } on Object {
      if (mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(const SnackBar(content: Text('회원 탈퇴를 처리하지 못했어요.')));
      }
    }
  }
}

class SettingsScreen extends StatefulWidget {
  const SettingsScreen({
    super.key,
    this.go,
    required this.repository,
    required this.userApi,
    required this.authRepository,
    required this.reportApi,
  });
  final ValueChanged<int>? go;
  final DdubiRepository repository;
  final UserApi userApi;
  final AuthRepository authRepository;
  final ReportApi reportApi;
  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends State<SettingsScreen> {
  late Future<UserProfile> profile;
  late Future<TravelHistory> travelHistory;
  int? _selectedProfileCharacter = _ProfileImageCache.characterIndex;
  Uint8List? _selectedProfileBytes = _ProfileImageCache.bytes;
  bool _photoAccessExplained = false;
  bool? _locationAllowed;
  bool? _notificationAllowed;

  @override
  void initState() {
    super.initState();
    profile = widget.userApi.getMe();
    final now = DateTime.now();
    travelHistory = widget.reportApi.history(
      from: DateTime(now.year, now.month - 2, 1),
      to: now,
    );
    _loadPermissionStatus();
  }

  Future<void> _loadPermissionStatus() async {
    try {
      final location = await _AppPermissionController.hasLocationPermission();
      final notification =
          await _AppPermissionController.hasNotificationPermission();
      if (mounted) {
        setState(() {
          _locationAllowed = location;
          _notificationAllowed = notification;
        });
      }
    } on Object {
      if (mounted) {
        setState(() {
          _locationAllowed ??= false;
          _notificationAllowed ??= false;
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final isWide =
        MediaQuery.sizeOf(context).width >=
        LayoutConstants.dashboardScreenBreakpoint;
    final content = FutureBuilder<UserProfile>(
      future: profile,
      builder: (context, snapshot) {
        if (snapshot.hasError) {
          return const DataLoadError(message: '사용자 설정을 불러오지 못했어요.');
        }
        if (!snapshot.hasData) {
          return const Center(child: CircularProgressIndicator());
        }
        return isWide
            ? _webLayout(context, snapshot.data!)
            : _mobileLayout(context, snapshot.data!);
      },
    );

    if (isWide) {
      return AppPage(
        title: '더보기',
        webTitle: '더보기',
        webSubtitle: '뚜비와 더 편리한 이동을 위한 설정을 관리해요.',
        child: content,
      );
    }

    return Scaffold(
      backgroundColor: const Color(0xFFF5FBFD),
      body: SafeArea(child: content),
      bottomNavigationBar: widget.go == null
          ? null
          : DdubiBottomNav(index: 4, onTap: widget.go!),
    );
  }

  Widget _mobileLayout(BuildContext context, UserProfile user) => ListView(
    key: const Key('mobileSettingsLayout'),
    padding: EdgeInsets.zero,
    children: [
      _mobileHeader(context),
      Padding(
        padding: const EdgeInsets.fromLTRB(16, 0, 16, 24),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Transform.translate(
              offset: const Offset(0, -18),
              child: _mobileAccountCard(context, user),
            ),
            const SizedBox(height: 2),
            _weeklyStampBoardCard(context),
            const SizedBox(height: 14),
            _mobileServiceStatus(context),
            const SizedBox(height: 14),
            _travelHistoryPreview(context),
            const SizedBox(height: 14),
            _accountActionsCard(context),
            const SizedBox(height: 14),
            _mobilePermissionCard(),
            const SizedBox(height: 14),
            _helpCard(context),
            const SizedBox(height: 14),
            _logoutCard(context),
          ],
        ),
      ),
    ],
  );

  Widget _mobileHeader(BuildContext context) => Container(
    height: 164,
    padding: const EdgeInsets.fromLTRB(22, 20, 18, 16),
    decoration: const BoxDecoration(
      gradient: LinearGradient(
        begin: Alignment.topLeft,
        end: Alignment.bottomRight,
        colors: [Color(0xFFF7FCFF), Color(0xFFE2F8F7)],
      ),
    ),
    child: Stack(
      children: [
        Positioned(
          right: 2,
          bottom: -22,
          child: Opacity(
            opacity: .96,
            child: Image.asset(
              'assets/images/ttubee/celebrate.png',
              width: 132,
              height: 132,
              fit: BoxFit.contain,
              semanticLabel: '이동 설정을 안내하는 뚜비',
            ),
          ),
        ),
        const Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              '더보기',
              style: TextStyle(
                color: Color(0xFF102454),
                fontSize: 30,
                fontWeight: FontWeight.w900,
                letterSpacing: -.8,
              ),
            ),
            SizedBox(height: 7),
            Text(
              '뚜비와 더 편리한 이동을 위한 설정을 관리해요.',
              style: TextStyle(
                color: Color(0xFF5D6B89),
                fontSize: 15,
                fontWeight: FontWeight.w500,
              ),
            ),
          ],
        ),
      ],
    ),
  );

  Widget _webLayout(BuildContext context, UserProfile user) => ListView(
    key: const Key('webSettingsLayout'),
    padding: _pad,
    children: [
      Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SizedBox(
            width: 300,
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                _accountCard(context, user),
                const SizedBox(height: 16),
                const InfoCard(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      StatusBadge(
                        '계정 연결됨',
                        tone: DdubiColors.green,
                        icon: Icons.verified_user_outlined,
                      ),
                      SizedBox(height: 12),
                      Text('현재 설정은 모바일 앱과 Web에서 동일하게 사용돼요.'),
                      SizedBox(height: 12),
                      Divider(),
                      SizedBox(height: 4),
                      Text('최근 로그인'),
                      SizedBox(height: 4),
                      Text(
                        '오늘 15:20 · Web',
                        style: TextStyle(fontWeight: FontWeight.w700),
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(width: 20),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Text('이동 설정', style: Theme.of(context).textTheme.titleLarge),
                const SizedBox(height: 4),
                const Text('앱 권한과 계정 정보를 한곳에서 관리하세요.'),
                const SizedBox(height: 16),
                _permissionCard(),
                const SizedBox(height: 16),
                _weeklyStampBoardCard(context),
                const SizedBox(height: 16),
                _serviceStatus(context),
                const SizedBox(height: 16),
                _travelHistoryPreview(context),
                const SizedBox(height: 16),
                _accountActionsCard(context),
              ],
            ),
          ),
        ],
      ),
    ],
  );

  Widget _mobileAccountCard(BuildContext context, UserProfile user) =>
      _settingsCard(
        padding: const EdgeInsets.fromLTRB(18, 18, 18, 16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Stack(
                  clipBehavior: Clip.none,
                  children: [
                    Container(
                      width: 70,
                      height: 70,
                      padding: const EdgeInsets.all(3),
                      decoration: const BoxDecoration(
                        color: Color(0xFFD9F8F3),
                        shape: BoxShape.circle,
                      ),
                      child: ClipOval(
                        child: _profileImage(
                          characterIndex: _selectedProfileCharacter,
                          bytes: _selectedProfileBytes,
                        ),
                      ),
                    ),
                    Positioned(
                      right: -2,
                      bottom: -2,
                      child: Material(
                        color: const Color(0xFF14B8A6),
                        shape: CircleBorder(
                          side: BorderSide(color: Colors.white, width: 2),
                        ),
                        child: InkWell(
                          key: const Key('changeProfileImageButton'),
                          onTap: () => _showProfileImageSheet(context),
                          customBorder: const CircleBorder(),
                          child: const SizedBox(
                            width: 29,
                            height: 29,
                            child: Icon(
                              Icons.add_rounded,
                              color: Colors.white,
                              size: 19,
                            ),
                          ),
                        ),
                      ),
                    ),
                  ],
                ),
                const SizedBox(width: 16),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        user.nickname,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(
                          color: Color(0xFF102454),
                          fontSize: 22,
                          fontWeight: FontWeight.w900,
                        ),
                      ),
                      const SizedBox(height: 3),
                      Text(
                        user.email ?? '연결된 소셜 계정',
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(
                          color: Color(0xFF64708B),
                          fontSize: 14,
                        ),
                      ),
                    ],
                  ),
                ),
              ],
            ),
            const Padding(
              padding: EdgeInsets.symmetric(vertical: 16),
              child: Divider(height: 1, color: Color(0xFFE1E9EE)),
            ),
            const Row(
              children: [
                Icon(Icons.settings_rounded, color: Color(0xFF10B8A5)),
                SizedBox(width: 8),
                Text(
                  '계정 관리',
                  style: TextStyle(
                    color: Color(0xFF102454),
                    fontSize: 17,
                    fontWeight: FontWeight.w900,
                  ),
                ),
              ],
            ),
            const SizedBox(height: 4),
            const Padding(
              padding: EdgeInsets.only(left: 32),
              child: Text(
                '비밀번호를 확인한 후 아래 메뉴를 이용할 수 있어요.',
                style: TextStyle(color: Color(0xFF64708B), fontSize: 12),
              ),
            ),
            const SizedBox(height: 13),
            const Padding(
              padding: EdgeInsets.only(left: 32),
              child: Text(
                '계정 연결 API 연동 후 제공될 예정이에요.',
                key: Key('accountManagementPreparing'),
                style: TextStyle(color: Color(0xFF64708B), fontSize: 13),
              ),
            ),
          ],
        ),
      );

  // ignore: unused_element
  Future<void> _openAccountManagement(
    BuildContext context,
    UserProfile user,
  ) async {
    var password = '';
    var obscured = true;
    var loading = false;
    String? errorText;

    final verified = await showDialog<bool>(
      context: context,
      barrierDismissible: false,
      barrierColor: const Color(0x730B1C2E),
      builder: (dialogContext) => StatefulBuilder(
        builder: (dialogContext, setDialogState) => Dialog(
          key: const Key('accountPasswordDialog'),
          backgroundColor: Colors.transparent,
          elevation: 0,
          insetPadding: const EdgeInsets.symmetric(
            horizontal: 24,
            vertical: 24,
          ),
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 390),
            child: SingleChildScrollView(
              child: DecoratedBox(
                decoration: BoxDecoration(
                  color: Colors.white,
                  borderRadius: BorderRadius.circular(32),
                  boxShadow: const [
                    BoxShadow(
                      color: Color(0x260B1C2E),
                      blurRadius: 28,
                      offset: Offset(0, 12),
                    ),
                  ],
                ),
                child: Padding(
                  padding: const EdgeInsets.fromLTRB(24, 20, 24, 18),
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Align(
                        alignment: Alignment.centerRight,
                        child: IconButton(
                          tooltip: '닫기',
                          onPressed: loading
                              ? null
                              : () => Navigator.pop(dialogContext, false),
                          icon: const Icon(Icons.close_rounded),
                          color: const Color(0xFF102454),
                        ),
                      ),
                      Container(
                        width: 168,
                        height: 168,
                        decoration: const BoxDecoration(
                          shape: BoxShape.circle,
                          gradient: RadialGradient(
                            colors: [Color(0xFFE0FAF6), Color(0xFFF4FCFB)],
                          ),
                        ),
                        child: ClipOval(
                          child: Image.asset(
                            'assets/images/ttubee/account-password-confirmation.png',
                            key: const Key('accountPasswordCharacter'),
                            fit: BoxFit.contain,
                            filterQuality: FilterQuality.high,
                            semanticLabel: '자물쇠를 들고 비밀번호 확인을 안내하는 뚜비',
                          ),
                        ),
                      ),
                      const SizedBox(height: 18),
                      const Text(
                        '비밀번호를 확인할게요',
                        textAlign: TextAlign.center,
                        style: TextStyle(
                          color: Color(0xFF102454),
                          fontSize: 22,
                          fontWeight: FontWeight.w900,
                          height: 1.25,
                        ),
                      ),
                      const SizedBox(height: 9),
                      const Text(
                        '개인정보 보호를 위해 계정 관리 전 한 번 더 확인해요.',
                        textAlign: TextAlign.center,
                        style: TextStyle(
                          color: Color(0xFF748096),
                          fontSize: 14,
                          height: 1.5,
                        ),
                      ),
                      const SizedBox(height: 22),
                      TextField(
                        key: const Key('accountPasswordField'),
                        obscureText: obscured,
                        autofillHints: const [AutofillHints.password],
                        onChanged: (value) => password = value,
                        onSubmitted: loading
                            ? null
                            : (_) => _verifyAccountPassword(
                                dialogContext: dialogContext,
                                email: user.email,
                                isLocalPreview: user.id == 'debug',
                                password: password,
                                setDialogState: setDialogState,
                                setLoading: (value) => loading = value,
                                setError: (value) => errorText = value,
                              ),
                        decoration: InputDecoration(
                          hintText: '현재 비밀번호',
                          hintStyle: const TextStyle(color: Color(0xFF8A96A9)),
                          prefixIcon: const Icon(
                            Icons.lock_outline_rounded,
                            color: Color(0xFF64748B),
                          ),
                          errorText: errorText,
                          suffixIcon: IconButton(
                            tooltip: obscured ? '비밀번호 보기' : '비밀번호 숨기기',
                            onPressed: () =>
                                setDialogState(() => obscured = !obscured),
                            icon: Icon(
                              obscured
                                  ? Icons.visibility_off_outlined
                                  : Icons.visibility_outlined,
                              color: const Color(0xFF64748B),
                            ),
                          ),
                          filled: true,
                          fillColor: const Color(0xFFFBFDFD),
                          contentPadding: const EdgeInsets.symmetric(
                            horizontal: 18,
                            vertical: 17,
                          ),
                          enabledBorder: OutlineInputBorder(
                            borderRadius: BorderRadius.circular(18),
                            borderSide: const BorderSide(
                              color: Color(0xFFCADAE0),
                            ),
                          ),
                          focusedBorder: OutlineInputBorder(
                            borderRadius: BorderRadius.circular(18),
                            borderSide: const BorderSide(
                              color: Color(0xFF10B8A5),
                              width: 1.6,
                            ),
                          ),
                          errorBorder: OutlineInputBorder(
                            borderRadius: BorderRadius.circular(18),
                            borderSide: const BorderSide(
                              color: Color(0xFFE85D68),
                            ),
                          ),
                          focusedErrorBorder: OutlineInputBorder(
                            borderRadius: BorderRadius.circular(18),
                            borderSide: const BorderSide(
                              color: Color(0xFFE85D68),
                              width: 1.6,
                            ),
                          ),
                        ),
                      ),
                      const SizedBox(height: 20),
                      SizedBox(
                        width: double.infinity,
                        height: 54,
                        child: FilledButton(
                          key: const Key('confirmAccountPasswordButton'),
                          onPressed: loading
                              ? null
                              : () => _verifyAccountPassword(
                                  dialogContext: dialogContext,
                                  email: user.email,
                                  isLocalPreview: user.id == 'debug',
                                  password: password,
                                  setDialogState: setDialogState,
                                  setLoading: (value) => loading = value,
                                  setError: (value) => errorText = value,
                                ),
                          style: FilledButton.styleFrom(
                            backgroundColor: const Color(0xFF10B8A5),
                            foregroundColor: Colors.white,
                            disabledBackgroundColor: const Color(0xFF9EDCD4),
                            shape: const StadiumBorder(),
                            textStyle: const TextStyle(
                              fontSize: 16,
                              fontWeight: FontWeight.w800,
                            ),
                          ),
                          child: loading
                              ? const SizedBox(
                                  width: 20,
                                  height: 20,
                                  child: CircularProgressIndicator(
                                    strokeWidth: 2.2,
                                    color: Colors.white,
                                  ),
                                )
                              : const Text('확인'),
                        ),
                      ),
                      const SizedBox(height: 4),
                      TextButton(
                        key: const Key('cancelAccountPasswordButton'),
                        onPressed: loading
                            ? null
                            : () => Navigator.pop(dialogContext, false),
                        style: TextButton.styleFrom(
                          foregroundColor: const Color(0xFF158F86),
                          padding: const EdgeInsets.symmetric(
                            horizontal: 24,
                            vertical: 12,
                          ),
                          textStyle: const TextStyle(
                            fontSize: 15,
                            fontWeight: FontWeight.w700,
                          ),
                        ),
                        child: const Text('취소'),
                      ),
                    ],
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    );

    if (verified == true && context.mounted) {
      context.push('/settings/account');
    }
  }

  Future<void> _verifyAccountPassword({
    required BuildContext dialogContext,
    required String? email,
    required bool isLocalPreview,
    required String password,
    required StateSetter setDialogState,
    required ValueChanged<bool> setLoading,
    required ValueChanged<String?> setError,
  }) async {
    if (password.isEmpty) {
      setDialogState(() => setError('현재 비밀번호를 입력해주세요.'));
      return;
    }
    if (isLocalPreview) {
      if (password == 'local1234') {
        Navigator.pop(dialogContext, true);
      } else {
        setDialogState(() => setError('비밀번호가 일치하지 않아요.'));
      }
      return;
    }
    final normalizedEmail = email?.trim();
    if (normalizedEmail == null || normalizedEmail.isEmpty) {
      setDialogState(() => setError('비밀번호 확인이 가능한 이메일 계정 정보가 없어요.'));
      return;
    }
    setDialogState(() {
      setLoading(true);
      setError(null);
    });
    try {
      await widget.authRepository.login(
        email: normalizedEmail,
        password: password,
      );
      if (dialogContext.mounted) Navigator.pop(dialogContext, true);
    } on AuthException catch (error) {
      if (dialogContext.mounted) {
        setDialogState(() {
          setLoading(false);
          setError(error.message ?? '비밀번호가 일치하지 않아요.');
        });
      }
    } on Object {
      if (dialogContext.mounted) {
        setDialogState(() {
          setLoading(false);
          setError('계정 정보를 확인하지 못했어요.');
        });
      }
    }
  }

  Widget _profileImage({required int? characterIndex, Uint8List? bytes}) =>
      bytes != null
      ? Image.memory(
          bytes,
          fit: BoxFit.cover,
          semanticLabel: '갤러리에서 선택한 사용자 프로필',
        )
      : _ProfileCharacterImage(index: characterIndex ?? 0);

  Future<void> _showProfileImageSheet(BuildContext context) async {
    final selection = await showModalBottomSheet<_ProfileImageSelection>(
      context: context,
      isScrollControlled: true,
      useSafeArea: true,
      backgroundColor: Colors.transparent,
      builder: (sheetContext) => _ProfileImageSheet(
        initialCharacterIndex: _selectedProfileCharacter,
        initialBytes: _selectedProfileBytes,
        onPickGallery: () => _pickGalleryImage(sheetContext),
      ),
    );
    if (selection == null || !mounted) return;
    setState(() {
      _selectedProfileCharacter = selection.characterIndex;
      _selectedProfileBytes = selection.bytes;
      _ProfileImageCache.characterIndex = selection.characterIndex;
      _ProfileImageCache.bytes = selection.bytes;
    });
  }

  Future<Uint8List?> _pickGalleryImage(BuildContext context) async {
    if (!_photoAccessExplained) {
      final allowed = await showDialog<bool>(
        context: context,
        barrierColor: const Color(0x730B1C2E),
        builder: (dialogContext) => Dialog(
          key: const Key('photoPermissionDialog'),
          backgroundColor: Colors.transparent,
          elevation: 0,
          insetPadding: const EdgeInsets.symmetric(horizontal: 28),
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 420),
            child: SingleChildScrollView(
              child: Container(
                padding: const EdgeInsets.fromLTRB(20, 22, 20, 20),
                decoration: BoxDecoration(
                  gradient: const LinearGradient(
                    begin: Alignment.topCenter,
                    end: Alignment.bottomCenter,
                    colors: [Color(0xFFFFFFFF), Color(0xFFF5FFFD)],
                  ),
                  borderRadius: BorderRadius.circular(30),
                  border: Border.all(color: const Color(0xFFCDEFEA)),
                  boxShadow: const [
                    BoxShadow(
                      color: Color(0x33102A43),
                      blurRadius: 30,
                      offset: Offset(0, 14),
                    ),
                  ],
                ),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    FractionallySizedBox(
                      widthFactor: .82,
                      child: Image.asset(
                        'assets/images/ttubee/photo-permission.png',
                        key: const Key('photoPermissionCharacter'),
                        fit: BoxFit.contain,
                        filterQuality: FilterQuality.high,
                        semanticLabel: '사진 선택을 안내하는 뚜비',
                      ),
                    ),
                    const SizedBox(height: 14),
                    const Text.rich(
                      TextSpan(
                        children: [
                          TextSpan(text: '사진 '),
                          TextSpan(
                            text: '접근 권한',
                            style: TextStyle(color: Color(0xFF0AAE9D)),
                          ),
                          TextSpan(text: '이 필요해요'),
                        ],
                      ),
                      textAlign: TextAlign.center,
                      style: TextStyle(
                        color: Color(0xFF102454),
                        fontSize: 22,
                        height: 1.25,
                        fontWeight: FontWeight.w900,
                        letterSpacing: -.5,
                      ),
                    ),
                    const SizedBox(height: 10),
                    const Text(
                      '프로필 사진을 선택하려면 갤러리 접근 권한이 필요해요.\n'
                      '권한은 프로필 사진을 선택할 때만 사용돼요.',
                      textAlign: TextAlign.center,
                      style: TextStyle(
                        color: Color(0xFF66738D),
                        fontSize: 13,
                        height: 1.55,
                      ),
                    ),
                    const SizedBox(height: 22),
                    Row(
                      children: [
                        Expanded(
                          child: SizedBox(
                            height: 52,
                            child: TextButton(
                              key: const Key('photoPermissionLaterButton'),
                              onPressed: () =>
                                  Navigator.pop(dialogContext, false),
                              style: TextButton.styleFrom(
                                foregroundColor: const Color(0xFF079B8D),
                                backgroundColor: const Color(0xFFE6F8F5),
                                shape: RoundedRectangleBorder(
                                  borderRadius: BorderRadius.circular(18),
                                ),
                              ),
                              child: const Text(
                                '나중에',
                                style: TextStyle(fontWeight: FontWeight.w800),
                              ),
                            ),
                          ),
                        ),
                        const SizedBox(width: 10),
                        Expanded(
                          child: SizedBox(
                            height: 52,
                            child: FilledButton(
                              key: const Key('photoPermissionAllowButton'),
                              onPressed: () =>
                                  Navigator.pop(dialogContext, true),
                              style: FilledButton.styleFrom(
                                backgroundColor: const Color(0xFF12B8A6),
                                foregroundColor: Colors.white,
                                shape: RoundedRectangleBorder(
                                  borderRadius: BorderRadius.circular(18),
                                ),
                              ),
                              child: const Text(
                                '허용하기',
                                style: TextStyle(fontWeight: FontWeight.w900),
                              ),
                            ),
                          ),
                        ),
                      ],
                    ),
                  ],
                ),
              ),
            ),
          ),
        ),
      );
      if (allowed != true) return null;
      _photoAccessExplained = true;
    }

    try {
      final image = await ImagePicker().pickImage(
        source: ImageSource.gallery,
        imageQuality: 88,
        maxWidth: 1600,
        maxHeight: 1600,
      );
      if (image == null) return null;
      return await image.readAsBytes();
    } on Object {
      if (context.mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(const SnackBar(content: Text('갤러리 접근 권한을 확인해주세요.')));
      }
      return null;
    }
  }

  Widget _mobilePermissionCard() => _settingsCard(
    child: Column(
      children: [
        _SettingsPermissionRow(
          icon: Icons.location_on_outlined,
          title: '위치 권한',
          subtitle: '현재 위치 기반 경로 안내에 사용',
          status: _permissionLabel(_locationAllowed),
          onTap: _changeLocationPermission,
        ),
        const Padding(
          padding: EdgeInsets.symmetric(vertical: 4),
          child: Divider(height: 1, color: Color(0xFFE1E9EE)),
        ),
        _SettingsPermissionRow(
          icon: Icons.notifications_none_rounded,
          title: '알림 권한',
          subtitle: '출발 시간과 지연 안내에 사용',
          status: _permissionLabel(_notificationAllowed),
          onTap: _changeNotificationPermission,
        ),
      ],
    ),
  );

  String _permissionLabel(bool? value) => switch (value) {
    true => '허용됨',
    false => '허용 안 됨',
    null => '확인 중',
  };

  Future<void> _changeLocationPermission() async {
    if (_locationAllowed == true) {
      await _AppPermissionController.openSettings();
      return;
    }
    final allowed = await _AppPermissionController.requestLocationPermission();
    if (mounted) setState(() => _locationAllowed = allowed);
  }

  Future<void> _changeNotificationPermission() async {
    if (_notificationAllowed == true) {
      await _AppPermissionController.openSettings();
      return;
    }
    final allowed =
        await _AppPermissionController.requestNotificationPermission();
    if (mounted) setState(() => _notificationAllowed = allowed);
  }

  Widget _weeklyStampBoardCard(BuildContext context) => const Card(
    key: Key('weeklyStampBoardCard'),
    child: Padding(
      padding: EdgeInsets.all(16),
      child: Row(
        children: [
          Icon(Icons.workspace_premium_outlined, color: Color(0xFF10B8A5)),
          SizedBox(width: 12),
          Expanded(
            child: Text(
              '도장판은 실제 미션 데이터 연동 후 제공될 예정이에요.',
              key: Key('stampBoardPreparing'),
              style: TextStyle(color: Color(0xFF64708B), fontSize: 13),
            ),
          ),
        ],
      ),
    ),
  );

  Widget _mobileServiceStatus(BuildContext context) => _settingsCard(
    color: const Color(0xFFFFFCFA),
    child: Stack(
      children: [
        Positioned(
          right: -8,
          bottom: -18,
          child: Opacity(
            opacity: .92,
            child: Image.asset(
              'assets/images/ttubee/concerned.png',
              width: 88,
              height: 88,
              fit: BoxFit.contain,
              semanticLabel: '서비스 상태를 안내하는 뚜비',
            ),
          ),
        ),
        Padding(
          padding: const EdgeInsets.only(right: 60),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const SectionHeader('서비스 연결 안내'),
              const SizedBox(height: 10),
              const Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Icon(
                    Icons.cloud_off_outlined,
                    color: Color(0xFFFF6B35),
                    size: 20,
                  ),
                  SizedBox(width: 6),
                  Expanded(
                    child: Text(
                      '일정별 최신 교통 상황을 확인하세요.',
                      style: TextStyle(
                        color: Color(0xFFFF5A2A),
                        fontSize: 13,
                        fontWeight: FontWeight.w800,
                      ),
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 7),
              const Text(
                '등록한 일정의 평가 결과와 정보 갱신 시각을 확인할 수 있어요.',
                style: TextStyle(
                  color: Color(0xFF64708B),
                  fontSize: 12,
                  height: 1.4,
                ),
              ),
              const SizedBox(height: 12),
              OutlinedButton.icon(
                onPressed: () => _open(
                  context,
                  DelayAlertScreen(repository: widget.repository),
                ),
                style: OutlinedButton.styleFrom(
                  foregroundColor: const Color(0xFF0FAE9F),
                  side: const BorderSide(color: Color(0xFF37C7BA)),
                  shape: const StadiumBorder(),
                  padding: const EdgeInsets.symmetric(horizontal: 15),
                ),
                iconAlignment: IconAlignment.end,
                icon: const Icon(Icons.chevron_right_rounded, size: 19),
                label: const Text(
                  '상세 확인',
                  style: TextStyle(fontWeight: FontWeight.w800),
                ),
              ),
            ],
          ),
        ),
      ],
    ),
  );

  // ignore: unused_element
  Future<void> _showStampBoardGuide(BuildContext context) =>
      showModalBottomSheet<void>(
        context: context,
        isScrollControlled: true,
        useSafeArea: true,
        backgroundColor: Colors.transparent,
        builder: (_) => const StampBoardGuideSheet(),
      );

  // ignore: unused_element
  Future<void> _showStampBoardDetails(BuildContext context) =>
      showModalBottomSheet<void>(
        context: context,
        isScrollControlled: true,
        useSafeArea: true,
        backgroundColor: Colors.transparent,
        builder: (_) => const StampBoardDetailSheet(),
      );

  Widget _logoutCard(BuildContext context) => Material(
    color: const Color(0xFFFFF3F5),
    borderRadius: BorderRadius.circular(19),
    child: InkWell(
      key: const Key('logoutCardButton'),
      onTap: () => _showLogoutDialog(context),
      borderRadius: BorderRadius.circular(19),
      child: const Padding(
        padding: EdgeInsets.symmetric(horizontal: 18, vertical: 17),
        child: Row(
          children: [
            Icon(Icons.logout_rounded, color: Color(0xFFFF4765), size: 27),
            SizedBox(width: 13),
            Expanded(
              child: Text(
                '로그아웃',
                style: TextStyle(
                  color: Color(0xFFFF4765),
                  fontSize: 17,
                  fontWeight: FontWeight.w900,
                ),
              ),
            ),
            Icon(Icons.chevron_right_rounded, color: Color(0xFF667085)),
          ],
        ),
      ),
    ),
  );

  Widget _helpCard(BuildContext context) => Material(
    color: Colors.white,
    borderRadius: BorderRadius.circular(22),
    child: InkWell(
      key: const Key('helpInquiryButton'),
      onTap: () => _open(context, const HelpInquiryScreen()),
      borderRadius: BorderRadius.circular(22),
      child: const Padding(
        padding: EdgeInsets.symmetric(horizontal: 17, vertical: 15),
        child: _MoreMenuRow(
          icon: Icons.help_outline_rounded,
          title: '도움말 · 문의하기',
          subtitle: '궁금한 점이 있다면 언제든 문의해 주세요.',
        ),
      ),
    ),
  );

  Widget _settingsCard({
    required Widget child,
    EdgeInsetsGeometry padding = const EdgeInsets.all(17),
    Color color = Colors.white,
  }) => Container(
    padding: padding,
    decoration: BoxDecoration(
      color: color,
      borderRadius: BorderRadius.circular(22),
      border: Border.all(color: const Color(0xFFE5EEF3)),
      boxShadow: const [
        BoxShadow(
          color: Color(0x101A6275),
          blurRadius: 18,
          offset: Offset(0, 7),
        ),
      ],
    ),
    child: child,
  );

  Widget _accountCard(BuildContext context, UserProfile user) => InfoCard(
    child: Column(
      children: [
        ListTile(
          contentPadding: EdgeInsets.zero,
          leading: CircleAvatar(child: Icon(Icons.person_outline)),
          title: Text(
            user.nickname,
            style: const TextStyle(fontWeight: FontWeight.w800),
          ),
          subtitle: Text(user.email ?? '연결된 소셜 계정'),
        ),
        const Divider(),
        ListTile(
          contentPadding: EdgeInsets.zero,
          leading: const Icon(Icons.logout, color: DdubiColors.red),
          title: const Text(
            '로그아웃',
            style: TextStyle(
              color: DdubiColors.red,
              fontWeight: FontWeight.w700,
            ),
          ),
          onTap: () => _showLogoutDialog(context),
        ),
      ],
    ),
  );

  Widget _accountActionsCard(BuildContext context) => _settingsCard(
    child: Column(
      children: [
        InkWell(
          key: const Key('savedPlacesMenuButton'),
          onTap: () => context.push('/settings/places'),
          borderRadius: BorderRadius.circular(12),
          child: const _MoreMenuRow(
            icon: Icons.bookmark_outline_rounded,
            title: '저장 장소 관리',
            subtitle: '자주 가는 장소를 등록하고 관리해요.',
          ),
        ),
        const Divider(height: 18, color: Color(0xFFE1E9EE)),
        InkWell(
          key: const Key('travelRecordsMenuButton'),
          onTap: () => context.push('/settings/records'),
          borderRadius: BorderRadius.circular(12),
          child: const _MoreMenuRow(
            icon: Icons.description_outlined,
            title: '이동 기록 · 주간 리포트',
            subtitle: '나의 이동 패턴을 한눈에 확인해요.',
          ),
        ),
      ],
    ),
  );

  Widget _travelHistoryPreview(BuildContext context) => InfoCard(
    child: FutureBuilder<TravelHistory>(
      future: travelHistory,
      builder: (context, snapshot) {
        final items = snapshot.data?.items.take(3).toList() ?? const [];
        return Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Row(
              children: [
                const Expanded(child: SectionHeader('최근 이동 기록')),
                TextButton(
                  onPressed: () => context.push('/settings/records'),
                  child: const Text('전체 보기'),
                ),
              ],
            ),
            if (snapshot.connectionState != ConnectionState.done)
              const LinearProgressIndicator()
            else if (snapshot.hasError)
              const Text('이동 기록을 불러오지 못했어요.')
            else if (items.isEmpty)
              const Text('최근 이동 기록이 없어요.')
            else
              for (final item in items)
                ListTile(
                  dense: true,
                  contentPadding: EdgeInsets.zero,
                  leading: const Icon(Icons.directions_transit_outlined),
                  title: Text(item.title),
                  subtitle: Text(_fullDate(item.arriveBy)),
                  trailing: Text(_arrivalResultText(item.result)),
                ),
          ],
        );
      },
    ),
  );

  Widget _permissionCard() => InfoCard(
    child: Column(
      children: [
        ListTile(
          leading: const Icon(Icons.location_on_outlined),
          title: const Text('위치 권한'),
          subtitle: const Text('현재 위치 기반 경로 안내에 사용'),
          trailing: Text('${_permissionLabel(_locationAllowed)}  ›'),
          onTap: _changeLocationPermission,
        ),
        const Divider(),
        ListTile(
          leading: const Icon(Icons.notifications_none),
          title: const Text('알림 권한'),
          subtitle: const Text('출발 시간과 지연 안내에 사용'),
          trailing: Text('${_permissionLabel(_notificationAllowed)}  ›'),
          onTap: _changeNotificationPermission,
        ),
      ],
    ),
  );

  Widget _serviceStatus(BuildContext context) => InfoCard(
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const SectionHeader('서비스 연결 안내'),
        const SizedBox(height: 10),
        const StatusBadge(
          '일정별 최신 교통 상황을 확인하세요.',
          tone: DdubiColors.orange,
          icon: Icons.cloud_off_outlined,
        ),
        const SizedBox(height: 6),
        const Text('등록한 일정의 평가 결과와 정보 갱신 시각을 확인할 수 있어요.'),
        const SizedBox(height: 12),
        Align(
          alignment: Alignment.centerRight,
          child: OutlinedButton(
            onPressed: () =>
                _open(context, DelayAlertScreen(repository: widget.repository)),
            child: const Text('상세 확인'),
          ),
        ),
      ],
    ),
  );

  void _showLogoutDialog(BuildContext context) => showDialog<void>(
    context: context,
    barrierColor: Colors.black.withValues(alpha: .45),
    builder: (dialogContext) => _LogoutConfirmationDialog(
      onCancel: () => Navigator.pop(dialogContext),
      onLogout: () async {
        Navigator.pop(dialogContext);
        try {
          await widget.authRepository.logout();
          if (context.mounted) context.go('/login');
        } on AuthException catch (error) {
          if (!context.mounted) return;
          ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(content: Text(error.message ?? '로그아웃하지 못했어요.')),
          );
        }
      },
    ),
  );
}

class _LogoutConfirmationDialog extends StatelessWidget {
  const _LogoutConfirmationDialog({
    required this.onCancel,
    required this.onLogout,
  });

  final VoidCallback onCancel;
  final Future<void> Function() onLogout;

  @override
  Widget build(BuildContext context) => Dialog(
    key: const Key('logoutConfirmationDialog'),
    backgroundColor: Colors.transparent,
    elevation: 0,
    insetPadding: const EdgeInsets.symmetric(horizontal: 24, vertical: 24),
    child: ConstrainedBox(
      constraints: const BoxConstraints(maxWidth: 410),
      child: Container(
        padding: const EdgeInsets.fromLTRB(24, 24, 24, 26),
        decoration: BoxDecoration(
          color: Colors.white,
          borderRadius: BorderRadius.circular(30),
          border: Border.all(color: const Color(0xFFE1F2EF)),
          boxShadow: const [
            BoxShadow(
              color: Color(0x33001F2B),
              blurRadius: 30,
              offset: Offset(0, 14),
            ),
          ],
        ),
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              SizedBox(
                height: 196,
                child: Stack(
                  alignment: Alignment.center,
                  children: [
                    Container(
                      width: 174,
                      height: 174,
                      decoration: BoxDecoration(
                        gradient: const RadialGradient(
                          colors: [Color(0xFFE4FBF7), Color(0xFFF4FCFB)],
                        ),
                        shape: BoxShape.circle,
                      ),
                    ),
                    Image.asset(
                      'assets/images/ttubee/logout-sad.png',
                      key: const Key('logoutDialogCharacter'),
                      width: 206,
                      height: 196,
                      fit: BoxFit.contain,
                      filterQuality: FilterQuality.high,
                      semanticLabel: '로그아웃을 아쉬워하는 뚜비',
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 12),
              Text(
                '로그아웃할까요?',
                textAlign: TextAlign.center,
                style: Theme.of(context).textTheme.headlineSmall?.copyWith(
                  color: DdubiColors.navy,
                  fontWeight: FontWeight.w900,
                ),
              ),
              const SizedBox(height: 12),
              Text(
                '다시 로그인하면 일정과\n추천 경로를 이어서 확인할 수 있어요.',
                textAlign: TextAlign.center,
                style: Theme.of(context).textTheme.bodyMedium
                    ?.copyWith(color: const Color(0xFF748096), height: 1.55),
              ),
              const SizedBox(height: 26),
              Row(
                children: [
                  Expanded(
                    child: SizedBox(
                      height: 52,
                      child: OutlinedButton(
                        key: const Key('logoutCancelButton'),
                        onPressed: onCancel,
                        style: OutlinedButton.styleFrom(
                          foregroundColor: const Color(0xFF009D91),
                          backgroundColor: const Color(0xFFF8FDFC),
                          side: const BorderSide(
                            color: Color(0xFF77D8CF),
                            width: 1.5,
                          ),
                          shape: const StadiumBorder(),
                          textStyle: const TextStyle(
                            fontSize: 16,
                            fontWeight: FontWeight.w800,
                          ),
                        ),
                        child: const Text('취소'),
                      ),
                    ),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: SizedBox(
                      height: 52,
                      child: FilledButton(
                        key: const Key('logoutConfirmButton'),
                        onPressed: onLogout,
                        style: FilledButton.styleFrom(
                          foregroundColor: Colors.white,
                          backgroundColor: const Color(0xFF08AA9D),
                          shape: const StadiumBorder(),
                          textStyle: const TextStyle(
                            fontSize: 16,
                            fontWeight: FontWeight.w900,
                          ),
                          elevation: 1,
                          shadowColor: const Color(0x55008D82),
                        ),
                        child: const Text('로그아웃'),
                      ),
                    ),
                  ),
                ],
              ),
            ],
          ),
        ),
      ),
    ),
  );
}

class _ProfileCharacterImage extends StatelessWidget {
  const _ProfileCharacterImage({required this.index});

  static const _assets = [
    'assets/images/ttubee/profiles/profile_01.png',
    'assets/images/ttubee/profiles/profile_02.png',
    'assets/images/ttubee/profiles/profile_03.png',
    'assets/images/ttubee/profiles/profile_04.png',
    'assets/images/ttubee/profiles/profile_05.png',
    'assets/images/ttubee/profiles/profile_06.png',
    'assets/images/ttubee/profiles/profile_07.png',
    'assets/images/ttubee/profiles/profile_08.png',
    'assets/images/ttubee/profiles/profile_09.png',
    'assets/images/ttubee/profiles/profile_10.png',
  ];

  final int index;

  @override
  Widget build(BuildContext context) => Semantics(
    image: true,
    label: '프로필 캐릭터 ${index + 1}',
    child: Image.asset(
      _assets[index],
      fit: BoxFit.cover,
      filterQuality: FilterQuality.high,
      excludeFromSemantics: true,
    ),
  );
}

class _ProfileImageSelection {
  const _ProfileImageSelection({required this.characterIndex, this.bytes});

  final int? characterIndex;
  final Uint8List? bytes;
}

class _ProfileImageSheet extends StatefulWidget {
  const _ProfileImageSheet({
    required this.initialCharacterIndex,
    required this.initialBytes,
    required this.onPickGallery,
  });

  final int? initialCharacterIndex;
  final Uint8List? initialBytes;
  final Future<Uint8List?> Function() onPickGallery;

  @override
  State<_ProfileImageSheet> createState() => _ProfileImageSheetState();
}

class _ProfileImageSheetState extends State<_ProfileImageSheet> {
  int? selectedCharacterIndex;
  Uint8List? selectedBytes;
  bool pickingImage = false;

  @override
  void initState() {
    super.initState();
    selectedCharacterIndex = widget.initialCharacterIndex;
    selectedBytes = widget.initialBytes;
  }

  Widget _image({required int? characterIndex, Uint8List? bytes}) =>
      bytes != null
      ? Image.memory(bytes, fit: BoxFit.cover)
      : _ProfileCharacterImage(index: characterIndex ?? 0);

  @override
  Widget build(BuildContext context) => FractionallySizedBox(
    heightFactor: .9,
    child: Material(
      color: Colors.white,
      borderRadius: const BorderRadius.vertical(top: Radius.circular(30)),
      clipBehavior: Clip.antiAlias,
      child: Column(
        children: [
          const SizedBox(height: 11),
          Container(
            width: 44,
            height: 5,
            decoration: BoxDecoration(
              color: const Color(0xFFDCE4E8),
              borderRadius: BorderRadius.circular(99),
            ),
          ),
          Expanded(
            child: SingleChildScrollView(
              padding: const EdgeInsets.fromLTRB(22, 18, 22, 18),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Text(
                    '프로필 이미지 변경',
                    style: TextStyle(
                      color: Color(0xFF102454),
                      fontSize: 24,
                      fontWeight: FontWeight.w900,
                      letterSpacing: -.5,
                    ),
                  ),
                  const SizedBox(height: 5),
                  const Text(
                    '마음에 드는 이미지를 선택해보세요.',
                    style: TextStyle(color: Color(0xFF65718B), fontSize: 14),
                  ),
                  const SizedBox(height: 20),
                  Center(
                    child: Column(
                      children: [
                        Container(
                          width: 112,
                          height: 112,
                          padding: const EdgeInsets.all(4),
                          decoration: BoxDecoration(
                            color: const Color(0xFFE5FAF7),
                            shape: BoxShape.circle,
                            border: Border.all(
                              color: const Color(0xFF18B8A7),
                              width: 2,
                            ),
                            boxShadow: const [
                              BoxShadow(
                                color: Color(0x2412A997),
                                blurRadius: 18,
                                offset: Offset(0, 7),
                              ),
                            ],
                          ),
                          child: ClipOval(
                            child: _image(
                              characterIndex: selectedCharacterIndex,
                              bytes: selectedBytes,
                            ),
                          ),
                        ),
                        const SizedBox(height: 8),
                        const Text(
                          '현재 프로필',
                          style: TextStyle(
                            color: Color(0xFF102454),
                            fontWeight: FontWeight.w800,
                          ),
                        ),
                      ],
                    ),
                  ),
                  const SizedBox(height: 22),
                  const Text(
                    '기본 이미지',
                    style: TextStyle(
                      color: Color(0xFF102454),
                      fontSize: 17,
                      fontWeight: FontWeight.w900,
                    ),
                  ),
                  const SizedBox(height: 11),
                  GridView.builder(
                    shrinkWrap: true,
                    physics: const NeverScrollableScrollPhysics(),
                    gridDelegate:
                        const SliverGridDelegateWithFixedCrossAxisCount(
                          crossAxisCount: 4,
                          mainAxisSpacing: 10,
                          crossAxisSpacing: 10,
                        ),
                    itemCount: 10,
                    itemBuilder: (context, index) {
                      final selected =
                          selectedBytes == null &&
                          index == selectedCharacterIndex;
                      return Material(
                        color: const Color(0xFFF4FAFC),
                        borderRadius: BorderRadius.circular(18),
                        child: InkWell(
                          key: Key('profileAsset-$index'),
                          onTap: () => setState(() {
                            selectedCharacterIndex = index;
                            selectedBytes = null;
                          }),
                          borderRadius: BorderRadius.circular(18),
                          child: Stack(
                            children: [
                              Positioned.fill(
                                child: Container(
                                  padding: const EdgeInsets.all(3),
                                  decoration: BoxDecoration(
                                    borderRadius: BorderRadius.circular(18),
                                    border: Border.all(
                                      color: selected
                                          ? const Color(0xFF12B8A6)
                                          : const Color(0xFFE2EAEE),
                                      width: selected ? 2 : 1,
                                    ),
                                  ),
                                  child: ClipRRect(
                                    borderRadius: BorderRadius.circular(14),
                                    child: _ProfileCharacterImage(index: index),
                                  ),
                                ),
                              ),
                              if (selected)
                                const Positioned(
                                  right: 4,
                                  bottom: 4,
                                  child: CircleAvatar(
                                    radius: 10,
                                    backgroundColor: Color(0xFF12B8A6),
                                    child: Icon(
                                      Icons.check_rounded,
                                      color: Colors.white,
                                      size: 14,
                                    ),
                                  ),
                                ),
                            ],
                          ),
                        ),
                      );
                    },
                  ),
                  const SizedBox(height: 22),
                  const Text(
                    '내 사진 사용하기',
                    style: TextStyle(
                      color: Color(0xFF102454),
                      fontSize: 17,
                      fontWeight: FontWeight.w900,
                    ),
                  ),
                  const SizedBox(height: 10),
                  Material(
                    color: const Color(0xFFEAF9F8),
                    borderRadius: BorderRadius.circular(17),
                    child: InkWell(
                      key: const Key('pickProfileImageFromGallery'),
                      onTap: pickingImage
                          ? null
                          : () async {
                              setState(() => pickingImage = true);
                              final bytes = await widget.onPickGallery();
                              if (!mounted) return;
                              setState(() {
                                pickingImage = false;
                                if (bytes != null) {
                                  selectedCharacterIndex = null;
                                  selectedBytes = bytes;
                                }
                              });
                            },
                      borderRadius: BorderRadius.circular(17),
                      child: Padding(
                        padding: const EdgeInsets.symmetric(
                          horizontal: 16,
                          vertical: 15,
                        ),
                        child: Row(
                          children: [
                            const Icon(
                              Icons.photo_library_outlined,
                              color: Color(0xFF10AD9E),
                              size: 25,
                            ),
                            const SizedBox(width: 12),
                            Expanded(
                              child: Text(
                                pickingImage ? '사진을 불러오는 중...' : '갤러리에서 사진 선택',
                                style: const TextStyle(
                                  color: Color(0xFF17304D),
                                  fontWeight: FontWeight.w800,
                                ),
                              ),
                            ),
                            const Icon(
                              Icons.chevron_right_rounded,
                              color: Color(0xFF64708B),
                            ),
                          ],
                        ),
                      ),
                    ),
                  ),
                ],
              ),
            ),
          ),
          Container(
            padding: const EdgeInsets.fromLTRB(22, 12, 22, 16),
            decoration: const BoxDecoration(
              color: Colors.white,
              border: Border(top: BorderSide(color: Color(0xFFE7EEF2))),
            ),
            child: Row(
              children: [
                Expanded(
                  child: OutlinedButton(
                    onPressed: () => Navigator.pop(context),
                    style: OutlinedButton.styleFrom(
                      foregroundColor: const Color(0xFF5F6D89),
                      minimumSize: const Size.fromHeight(52),
                      shape: RoundedRectangleBorder(
                        borderRadius: BorderRadius.circular(16),
                      ),
                    ),
                    child: const Text('취소'),
                  ),
                ),
                const SizedBox(width: 10),
                Expanded(
                  flex: 2,
                  child: FilledButton(
                    key: const Key('applyProfileImageButton'),
                    onPressed: pickingImage
                        ? null
                        : () => Navigator.pop(
                            context,
                            _ProfileImageSelection(
                              characterIndex: selectedCharacterIndex,
                              bytes: selectedBytes,
                            ),
                          ),
                    style: FilledButton.styleFrom(
                      backgroundColor: const Color(0xFF12B8A6),
                      minimumSize: const Size.fromHeight(52),
                      shape: RoundedRectangleBorder(
                        borderRadius: BorderRadius.circular(16),
                      ),
                    ),
                    child: const Text(
                      '적용하기',
                      style: TextStyle(fontWeight: FontWeight.w900),
                    ),
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    ),
  );
}

class _MoreMenuRow extends StatelessWidget {
  const _MoreMenuRow({
    required this.icon,
    required this.title,
    required this.subtitle,
  });

  final IconData icon;
  final String title;
  final String subtitle;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.symmetric(vertical: 5),
    child: Row(
      children: [
        Container(
          width: 44,
          height: 44,
          decoration: const BoxDecoration(
            color: Color(0xFFE4F9F6),
            shape: BoxShape.circle,
          ),
          child: Icon(icon, color: const Color(0xFF0CB3A2), size: 25),
        ),
        const SizedBox(width: 12),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                title,
                style: const TextStyle(
                  color: Color(0xFF102454),
                  fontSize: 16,
                  fontWeight: FontWeight.w900,
                ),
              ),
              const SizedBox(height: 3),
              Text(
                subtitle,
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(
                  color: Color(0xFF687691),
                  fontSize: 12,
                  height: 1.3,
                ),
              ),
            ],
          ),
        ),
        const SizedBox(width: 6),
        const Icon(Icons.chevron_right_rounded, color: Color(0xFF425273)),
      ],
    ),
  );
}

// ignore: unused_element
class _SettingsAccountAction extends StatelessWidget {
  const _SettingsAccountAction({
    required this.icon,
    required this.label,
    required this.onTap,
  });

  final IconData icon;
  final String label;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) => Material(
    color: const Color(0xFFF2F8FC),
    borderRadius: BorderRadius.circular(14),
    child: InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(14),
      child: SizedBox(
        height: 54,
        child: Row(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(icon, color: const Color(0xFF17243F), size: 20),
            const SizedBox(width: 7),
            Text(
              label,
              style: const TextStyle(
                color: Color(0xFF17243F),
                fontSize: 13,
                fontWeight: FontWeight.w800,
              ),
            ),
            const SizedBox(width: 3),
            const Icon(
              Icons.chevron_right_rounded,
              color: Color(0xFF62708B),
              size: 18,
            ),
          ],
        ),
      ),
    ),
  );
}

class _SettingsPermissionRow extends StatelessWidget {
  const _SettingsPermissionRow({
    required this.icon,
    required this.title,
    required this.subtitle,
    required this.status,
    required this.onTap,
  });

  final IconData icon;
  final String title;
  final String subtitle;
  final String status;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) => InkWell(
    onTap: onTap,
    borderRadius: BorderRadius.circular(12),
    child: Padding(
      padding: const EdgeInsets.symmetric(vertical: 9),
      child: Row(
        children: [
          SizedBox(
            width: 39,
            child: Icon(icon, color: const Color(0xFF17243F), size: 27),
          ),
          const SizedBox(width: 7),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  title,
                  style: const TextStyle(
                    color: Color(0xFF102454),
                    fontSize: 16,
                    fontWeight: FontWeight.w900,
                  ),
                ),
                const SizedBox(height: 2),
                Text(
                  subtitle,
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(
                    color: Color(0xFF64708B),
                    fontSize: 12,
                    height: 1.3,
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(width: 8),
          Text(
            status,
            style: const TextStyle(
              color: Color(0xFF5F6D89),
              fontSize: 12,
              fontWeight: FontWeight.w700,
            ),
          ),
          const SizedBox(width: 2),
          const Icon(
            Icons.chevron_right_rounded,
            color: Color(0xFF65718B),
            size: 20,
          ),
        ],
      ),
    ),
  );
}
