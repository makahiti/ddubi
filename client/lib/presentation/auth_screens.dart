import 'package:flutter/material.dart';

import '../core/theme.dart';
import '../domain/auth.dart';
import '../services/kakao_login_service.dart';
import '../services/google_login_service.dart';
import '../services/naver_login_service.dart';
import 'widgets.dart';
import 'micro_animations.dart';

class SocialAuthScreen extends StatefulWidget {
  const SocialAuthScreen({
    super.key,
    required this.provider,
    required this.onAuthenticated,
    required this.repository,
    this.kakaoLoginService,
    this.googleLoginService,
    this.naverLoginService,
  });
  final String provider;
  final VoidCallback onAuthenticated;
  final AuthRepository repository;
  final KakaoLoginService? kakaoLoginService;
  final GoogleLoginService? googleLoginService;
  final NaverLoginService? naverLoginService;

  @override
  State<SocialAuthScreen> createState() => _SocialAuthScreenState();
}

class _SocialAuthScreenState extends State<SocialAuthScreen> {
  bool _loading = false;
  String? _error;

  @override
  Widget build(BuildContext context) {
    final provider = widget.provider;
    final isKakao = provider == '카카오';
    final isNaver = provider == '네이버';
    final isGoogle = provider == 'Google';
    if (isKakao) return _buildKakaoScreen(context);
    if (isNaver) return _buildNaverScreen(context);
    if (isGoogle) return _buildGoogleScreen(context);
    final buttonBackground = isKakao
        ? const Color(0xFFFFD600)
        : isNaver
        ? const Color(0xFF03C75A)
        : Colors.white;
    final buttonForeground = isKakao
        ? Colors.black
        : isNaver
        ? Colors.white
        : DdubiColors.navy;
    return Scaffold(
      appBar: AppBar(title: Text('$provider 로그인')),
      body: SafeArea(
        child: Center(
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 520),
            child: Padding(
              padding: const EdgeInsets.all(24),
              child: Column(
                children: [
                  const Spacer(),
                  Image.asset('assets/images/ttubee-guide.png', height: 180),
                  const SizedBox(height: 20),
                  Text(
                    '$provider 공식 로그인',
                    style: Theme.of(context).textTheme.titleLarge,
                  ),
                  const SizedBox(height: 10),
                  Text(
                    '$provider 공식 로그인 화면에서 인증한 뒤\n뚜비 계정으로 안전하게 로그인합니다.',
                    textAlign: TextAlign.center,
                    style: Theme.of(context).textTheme.bodyMedium,
                  ),
                  const Spacer(),
                  if (_error != null) ...[
                    Text(
                      _error!,
                      textAlign: TextAlign.center,
                      style: const TextStyle(color: DdubiColors.red),
                    ),
                    const SizedBox(height: 12),
                  ],
                  SizedBox(
                    width: double.infinity,
                    height: 52,
                    child: FilledButton(
                      style: FilledButton.styleFrom(
                        backgroundColor: buttonBackground,
                        foregroundColor: buttonForeground,
                        side: BorderSide(
                          color: isKakao || isNaver
                              ? buttonBackground
                              : DdubiColors.border,
                        ),
                      ),
                      onPressed: _loading ? null : _login,
                      child: Text(
                        _loading ? '로그인 중...' : '$provider 계정으로 계속하기',
                        style: const TextStyle(fontWeight: FontWeight.w800),
                      ),
                    ),
                  ),
                  const SizedBox(height: 10),
                  Text(
                    isKakao || isNaver
                        ? '액세스 토큰은 백엔드에서 다시 검증해요.'
                        : 'ID 토큰은 백엔드에서 다시 검증해요.',
                    style: Theme.of(context).textTheme.bodySmall,
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildKakaoScreen(BuildContext context) {
    const kakaoYellow = Color(0xFFFEE500);
    const kakaoAccent = Color(0xFFF2B700);
    const kakaoBrown = Color(0xFF3C1E1E);
    const navy = Color(0xFF14213D);

    return Scaffold(
      backgroundColor: Colors.white,
      appBar: AppBar(
        backgroundColor: Colors.white,
        surfaceTintColor: Colors.white,
        elevation: 0,
        scrolledUnderElevation: 0,
        leading: const BackButton(color: Color(0xFF202631)),
        title: const Text(
          '카카오 로그인',
          style: TextStyle(
            color: Color(0xFF202631),
            fontSize: 23,
            fontWeight: FontWeight.w800,
          ),
        ),
      ),
      body: SafeArea(
        top: false,
        child: LayoutBuilder(
          builder: (context, constraints) {
            final compact = constraints.maxHeight < 700;
            final heroHeight = (constraints.maxHeight * (compact ? .43 : .46))
                .clamp(compact ? 220.0 : 290.0, compact ? 410.0 : 440.0);
            final cardHeight = compact ? 218.0 : 280.0;

            return SingleChildScrollView(
              child: ConstrainedBox(
                constraints: BoxConstraints(minHeight: constraints.maxHeight),
                child: Column(
                  children: [
                    SizedBox(
                      height: heroHeight + cardHeight - 14,
                      child: Stack(
                        clipBehavior: Clip.none,
                        children: [
                          Positioned(
                            top: 0,
                            left: 0,
                            right: 0,
                            height: heroHeight,
                            child: Stack(
                              fit: StackFit.expand,
                              children: [
                                Image.asset(
                                  'assets/images/auth/kakao_login_bg.png',
                                  fit: BoxFit.cover,
                                  alignment: Alignment.topCenter,
                                ),
                                const DecoratedBox(
                                  decoration: BoxDecoration(
                                    gradient: LinearGradient(
                                      begin: Alignment.topCenter,
                                      end: Alignment.bottomCenter,
                                      colors: [
                                        Color(0x35FFFFFF),
                                        Color(0x00FFFFFF),
                                      ],
                                    ),
                                  ),
                                ),
                                Positioned(
                                  left: 18,
                                  bottom: compact ? 100 : 138,
                                  child: Transform.rotate(
                                    angle: -.06,
                                    child: const Text(
                                      '좋은 이야기가\n더 가까워져요!',
                                      style: TextStyle(
                                        color: Color(0xFF9A6200),
                                        fontSize: 16,
                                        height: 1.45,
                                        fontWeight: FontWeight.w900,
                                        shadows: [
                                          Shadow(
                                            color: Colors.white,
                                            blurRadius: 8,
                                          ),
                                        ],
                                      ),
                                    ),
                                  ),
                                ),
                                Align(
                                  alignment: const Alignment(.10, .66),
                                  child: Transform.translate(
                                    offset: Offset(0, compact ? 7 : 12),
                                    child: TtubeeEntrance(
                                      asset:
                                          'assets/images/auth/kakao_ttubee.png',
                                      height: compact ? 160 : 220,
                                      semanticLabel: '카카오 로그인을 안내하는 뚜비',
                                    ),
                                  ),
                                ),
                              ],
                            ),
                          ),
                          Positioned(
                            top: heroHeight - 14,
                            left: 24,
                            right: 24,
                            height: cardHeight,
                            child: Container(
                              padding: EdgeInsets.fromLTRB(
                                20,
                                compact ? 20 : 25,
                                20,
                                compact ? 17 : 22,
                              ),
                              decoration: BoxDecoration(
                                color: Colors.white,
                                borderRadius: BorderRadius.circular(30),
                                boxShadow: const [
                                  BoxShadow(
                                    color: Color(0x1AC78C00),
                                    blurRadius: 24,
                                    offset: Offset(0, 9),
                                  ),
                                ],
                              ),
                              child: Column(
                                children: [
                                  Text.rich(
                                    const TextSpan(
                                      children: [
                                        TextSpan(
                                          text: '카카오',
                                          style: TextStyle(color: kakaoAccent),
                                        ),
                                        TextSpan(
                                          text: '로 간편하게 시작해요',
                                          style: TextStyle(color: navy),
                                        ),
                                      ],
                                    ),
                                    textAlign: TextAlign.center,
                                    style: TextStyle(
                                      fontSize: compact ? 21 : 24,
                                      fontWeight: FontWeight.w900,
                                    ),
                                  ),
                                  SizedBox(height: compact ? 9 : 13),
                                  Text(
                                    '카카오 계정으로 본인 인증 후,\n뚜비 계정과 안전하게 연결돼요.',
                                    textAlign: TextAlign.center,
                                    style: TextStyle(
                                      color: const Color(0xFF667085),
                                      fontSize: compact ? 13 : 14,
                                      height: 1.5,
                                    ),
                                  ),
                                  const Spacer(),
                                  const Row(
                                    children: [
                                      Expanded(
                                        child: _KakaoBenefit(
                                          icon: Icons.bolt_rounded,
                                          firstLine: '빠른',
                                          secondLine: '로그인',
                                        ),
                                      ),
                                      _KakaoBenefitDivider(),
                                      Expanded(
                                        child: _KakaoBenefit(
                                          icon: Icons.link_rounded,
                                          firstLine: '쉬운',
                                          secondLine: '계정 연결',
                                        ),
                                      ),
                                      _KakaoBenefitDivider(),
                                      Expanded(
                                        child: _KakaoBenefit(
                                          icon:
                                              Icons.sentiment_satisfied_rounded,
                                          firstLine: '친숙한',
                                          secondLine: '사용 경험',
                                        ),
                                      ),
                                    ],
                                  ),
                                ],
                              ),
                            ),
                          ),
                        ],
                      ),
                    ),
                    SizedBox(height: compact ? 18 : 60),
                    if (_error != null) ...[
                      Padding(
                        padding: const EdgeInsets.symmetric(horizontal: 28),
                        child: Text(
                          _error!,
                          textAlign: TextAlign.center,
                          style: const TextStyle(color: DdubiColors.red),
                        ),
                      ),
                      const SizedBox(height: 10),
                    ],
                    Padding(
                      padding: const EdgeInsets.symmetric(horizontal: 24),
                      child: SizedBox(
                        width: double.infinity,
                        height: 60,
                        child: FilledButton(
                          style: FilledButton.styleFrom(
                            backgroundColor: kakaoYellow,
                            foregroundColor: kakaoBrown,
                            disabledBackgroundColor: const Color(0x99FEE500),
                            shape: const StadiumBorder(),
                            elevation: 2,
                            shadowColor: const Color(0x33D9A400),
                          ),
                          onPressed: _loading ? null : _login,
                          child: Row(
                            mainAxisAlignment: MainAxisAlignment.center,
                            children: [
                              const Icon(
                                Icons.chat_bubble_rounded,
                                color: kakaoBrown,
                                size: 27,
                              ),
                              Container(
                                width: 1,
                                height: 24,
                                margin: const EdgeInsets.symmetric(
                                  horizontal: 17,
                                ),
                                color: Color(0x553C1E1E),
                              ),
                              Text(
                                _loading ? '로그인 중...' : '카카오 계정으로 계속하기',
                                style: const TextStyle(
                                  fontSize: 16,
                                  fontWeight: FontWeight.w800,
                                ),
                              ),
                            ],
                          ),
                        ),
                      ),
                    ),
                    const SizedBox(height: 12),
                    const Padding(
                      padding: EdgeInsets.fromLTRB(24, 0, 24, 20),
                      child: Text(
                        '인증 정보는 백엔드에서 한 번 더 안전하게 확인해요.',
                        textAlign: TextAlign.center,
                        style: TextStyle(
                          color: Color(0xFF7A8494),
                          fontSize: 12,
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            );
          },
        ),
      ),
    );
  }

  Widget _buildNaverScreen(BuildContext context) {
    const naverGreen = Color(0xFF03C75A);
    const navy = Color(0xFF14213D);

    return Scaffold(
      backgroundColor: Colors.white,
      appBar: AppBar(
        backgroundColor: Colors.white,
        surfaceTintColor: Colors.white,
        elevation: 0,
        scrolledUnderElevation: 0,
        leading: const BackButton(color: Color(0xFF202631)),
        title: const Text(
          '네이버 로그인',
          style: TextStyle(
            color: Color(0xFF202631),
            fontSize: 23,
            fontWeight: FontWeight.w800,
          ),
        ),
      ),
      body: SafeArea(
        top: false,
        child: LayoutBuilder(
          builder: (context, constraints) {
            final compact = constraints.maxHeight < 700;
            final heroHeight = (constraints.maxHeight * (compact ? .43 : .46))
                .clamp(compact ? 220.0 : 290.0, compact ? 410.0 : 440.0);
            final cardHeight = compact ? 218.0 : 280.0;

            return SingleChildScrollView(
              child: ConstrainedBox(
                constraints: BoxConstraints(minHeight: constraints.maxHeight),
                child: Column(
                  children: [
                    SizedBox(
                      height: heroHeight + cardHeight - 14,
                      child: Stack(
                        clipBehavior: Clip.none,
                        children: [
                          Positioned(
                            top: 0,
                            left: 0,
                            right: 0,
                            height: heroHeight,
                            child: Stack(
                              fit: StackFit.expand,
                              children: [
                                Image.asset(
                                  'assets/images/auth/naver_login_bg.png',
                                  fit: BoxFit.cover,
                                  alignment: const Alignment(0, -.2),
                                ),
                                const DecoratedBox(
                                  decoration: BoxDecoration(
                                    gradient: LinearGradient(
                                      begin: Alignment.topCenter,
                                      end: Alignment.bottomCenter,
                                      colors: [
                                        Color(0x52FFFFFF),
                                        Color(0x00FFFFFF),
                                      ],
                                    ),
                                  ),
                                ),
                                Positioned(
                                  left: 18,
                                  bottom: 159,
                                  child: Transform.rotate(
                                    angle: -.06,
                                    child: const Text(
                                      '좋은 하루,\n네이버에서\n시작해요!',
                                      style: TextStyle(
                                        color: naverGreen,
                                        fontSize: 15,
                                        height: 1.45,
                                        fontWeight: FontWeight.w800,
                                        shadows: [
                                          Shadow(
                                            color: Colors.white,
                                            blurRadius: 8,
                                          ),
                                        ],
                                      ),
                                    ),
                                  ),
                                ),
                                Align(
                                  alignment: const Alignment(-.22, .62),
                                  child: TtubeeEntrance(
                                    asset: 'assets/images/ttubee-guide.png',
                                    height: compact ? 160 : 220,
                                    semanticLabel: '네이버 로그인을 안내하는 뚜비',
                                  ),
                                ),
                              ],
                            ),
                          ),
                          Positioned(
                            top: heroHeight - 14,
                            left: 24,
                            right: 24,
                            height: cardHeight,
                            child: Container(
                              padding: EdgeInsets.fromLTRB(
                                20,
                                compact ? 20 : 25,
                                20,
                                compact ? 17 : 22,
                              ),
                              decoration: BoxDecoration(
                                color: Colors.white,
                                borderRadius: BorderRadius.circular(30),
                                boxShadow: const [
                                  BoxShadow(
                                    color: Color(0x14006D3A),
                                    blurRadius: 24,
                                    offset: Offset(0, 9),
                                  ),
                                ],
                              ),
                              child: Column(
                                children: [
                                  Text.rich(
                                    const TextSpan(
                                      children: [
                                        TextSpan(
                                          text: '네이버',
                                          style: TextStyle(color: naverGreen),
                                        ),
                                        TextSpan(text: '로 빠르게 시작해요'),
                                      ],
                                    ),
                                    textAlign: TextAlign.center,
                                    style: TextStyle(
                                      color: navy,
                                      fontSize: compact ? 22 : 25,
                                      fontWeight: FontWeight.w900,
                                    ),
                                  ),
                                  SizedBox(height: compact ? 9 : 13),
                                  Text(
                                    '네이버 계정으로 본인 인증 후,\n뚜비 계정과 안전하게 연결돼요.',
                                    textAlign: TextAlign.center,
                                    style: TextStyle(
                                      color: const Color(0xFF667085),
                                      fontSize: compact ? 13 : 14,
                                      height: 1.5,
                                    ),
                                  ),
                                  const Spacer(),
                                  const Row(
                                    children: [
                                      Expanded(
                                        child: _NaverBenefit(
                                          icon: Icons.verified_user_rounded,
                                          firstLine: '안전한',
                                          secondLine: '본인 인증',
                                        ),
                                      ),
                                      _NaverBenefitDivider(),
                                      Expanded(
                                        child: _NaverBenefit(
                                          icon: Icons.person_rounded,
                                          firstLine: '간편한',
                                          secondLine: '계정 연결',
                                        ),
                                      ),
                                      _NaverBenefitDivider(),
                                      Expanded(
                                        child: _NaverBenefit(
                                          icon: Icons.eco_rounded,
                                          firstLine: '더 편리한',
                                          secondLine: '서비스 이용',
                                        ),
                                      ),
                                    ],
                                  ),
                                ],
                              ),
                            ),
                          ),
                        ],
                      ),
                    ),
                    SizedBox(height: compact ? 18 : 60),
                    if (_error != null) ...[
                      Padding(
                        padding: const EdgeInsets.symmetric(horizontal: 28),
                        child: Text(
                          _error!,
                          textAlign: TextAlign.center,
                          style: const TextStyle(color: DdubiColors.red),
                        ),
                      ),
                      const SizedBox(height: 10),
                    ],
                    Padding(
                      padding: const EdgeInsets.symmetric(horizontal: 24),
                      child: SizedBox(
                        width: double.infinity,
                        height: 60,
                        child: FilledButton(
                          style: FilledButton.styleFrom(
                            backgroundColor: naverGreen,
                            foregroundColor: Colors.white,
                            disabledBackgroundColor: naverGreen.withValues(
                              alpha: .55,
                            ),
                            shape: const StadiumBorder(),
                            elevation: 2,
                            shadowColor: const Color(0x3303C75A),
                          ),
                          onPressed: _loading ? null : _login,
                          child: Row(
                            mainAxisAlignment: MainAxisAlignment.center,
                            children: [
                              const Text(
                                'N',
                                style: TextStyle(
                                  fontSize: 28,
                                  fontWeight: FontWeight.w900,
                                  height: 1,
                                ),
                              ),
                              Container(
                                width: 1,
                                height: 24,
                                margin: const EdgeInsets.symmetric(
                                  horizontal: 17,
                                ),
                                color: Colors.white38,
                              ),
                              Text(
                                _loading ? '로그인 중...' : '네이버 계정으로 계속하기',
                                style: const TextStyle(
                                  fontSize: 16,
                                  fontWeight: FontWeight.w800,
                                ),
                              ),
                            ],
                          ),
                        ),
                      ),
                    ),
                    const SizedBox(height: 12),
                    const Padding(
                      padding: EdgeInsets.fromLTRB(24, 0, 24, 20),
                      child: Text(
                        '인증 정보는 백엔드에서 한 번 더 안전하게 확인해요.',
                        textAlign: TextAlign.center,
                        style: TextStyle(
                          color: Color(0xFF7A8494),
                          fontSize: 12,
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            );
          },
        ),
      ),
    );
  }

  Widget _buildGoogleScreen(BuildContext context) {
    const navy = Color(0xFF14213D);
    const googleBlue = Color(0xFF4285F4);

    return Scaffold(
      backgroundColor: Colors.white,
      appBar: AppBar(
        backgroundColor: Colors.white,
        surfaceTintColor: Colors.white,
        elevation: 0,
        scrolledUnderElevation: 0,
        leading: const BackButton(color: Color(0xFF202631)),
        title: const Text(
          'Google 로그인',
          style: TextStyle(
            color: Color(0xFF202631),
            fontSize: 23,
            fontWeight: FontWeight.w800,
          ),
        ),
      ),
      body: SafeArea(
        top: false,
        child: LayoutBuilder(
          builder: (context, constraints) {
            final compact = constraints.maxHeight < 700;
            final heroHeight = (constraints.maxHeight * (compact ? .43 : .46))
                .clamp(compact ? 220.0 : 290.0, compact ? 410.0 : 440.0);
            final cardHeight = compact ? 218.0 : 280.0;

            return SingleChildScrollView(
              child: ConstrainedBox(
                constraints: BoxConstraints(minHeight: constraints.maxHeight),
                child: Column(
                  children: [
                    SizedBox(
                      height: heroHeight + cardHeight - 14,
                      child: Stack(
                        clipBehavior: Clip.none,
                        children: [
                          Positioned(
                            top: 0,
                            left: 0,
                            right: 0,
                            height: heroHeight,
                            child: Stack(
                              fit: StackFit.expand,
                              children: [
                                Image.asset(
                                  'assets/images/auth/google_login_bg.png',
                                  fit: BoxFit.cover,
                                  alignment: const Alignment(0, -.2),
                                ),
                                const DecoratedBox(
                                  decoration: BoxDecoration(
                                    gradient: LinearGradient(
                                      begin: Alignment.topCenter,
                                      end: Alignment.bottomCenter,
                                      colors: [
                                        Color(0x42FFFFFF),
                                        Color(0x00FFFFFF),
                                      ],
                                    ),
                                  ),
                                ),
                                Positioned(
                                  left: 18,
                                  bottom: compact ? 100 : 138,
                                  child: Transform.rotate(
                                    angle: -.06,
                                    child: const Text(
                                      '더 넓은 세상으로,\nGoogle과 함께\n시작해요!',
                                      style: TextStyle(
                                        color: googleBlue,
                                        fontSize: 15,
                                        height: 1.45,
                                        fontWeight: FontWeight.w800,
                                        shadows: [
                                          Shadow(
                                            color: Colors.white,
                                            blurRadius: 8,
                                          ),
                                        ],
                                      ),
                                    ),
                                  ),
                                ),
                                Align(
                                  alignment: const Alignment(.08, .62),
                                  child: TtubeeEntrance(
                                    asset: 'assets/images/ttubee-guide.png',
                                    height: compact ? 160 : 220,
                                    semanticLabel: 'Google 로그인을 안내하는 뚜비',
                                  ),
                                ),
                              ],
                            ),
                          ),
                          Positioned(
                            top: heroHeight - 14,
                            left: 24,
                            right: 24,
                            height: cardHeight,
                            child: Container(
                              padding: EdgeInsets.fromLTRB(
                                20,
                                compact ? 20 : 25,
                                20,
                                compact ? 17 : 22,
                              ),
                              decoration: BoxDecoration(
                                color: Colors.white,
                                borderRadius: BorderRadius.circular(30),
                                boxShadow: const [
                                  BoxShadow(
                                    color: Color(0x142D68B8),
                                    blurRadius: 24,
                                    offset: Offset(0, 9),
                                  ),
                                ],
                              ),
                              child: Column(
                                children: [
                                  Text.rich(
                                    TextSpan(
                                      children: [
                                        const TextSpan(
                                          text: 'G',
                                          style: TextStyle(color: googleBlue),
                                        ),
                                        const TextSpan(
                                          text: 'o',
                                          style: TextStyle(
                                            color: Color(0xFFEA4335),
                                          ),
                                        ),
                                        const TextSpan(
                                          text: 'o',
                                          style: TextStyle(
                                            color: Color(0xFFFBBC05),
                                          ),
                                        ),
                                        const TextSpan(
                                          text: 'g',
                                          style: TextStyle(color: googleBlue),
                                        ),
                                        const TextSpan(
                                          text: 'l',
                                          style: TextStyle(
                                            color: Color(0xFF34A853),
                                          ),
                                        ),
                                        const TextSpan(
                                          text: 'e',
                                          style: TextStyle(
                                            color: Color(0xFFEA4335),
                                          ),
                                        ),
                                        const TextSpan(
                                          text: '로 스마트하게 시작해요',
                                          style: TextStyle(color: navy),
                                        ),
                                      ],
                                    ),
                                    textAlign: TextAlign.center,
                                    style: TextStyle(
                                      fontSize: compact ? 21 : 24,
                                      fontWeight: FontWeight.w900,
                                    ),
                                  ),
                                  SizedBox(height: compact ? 9 : 13),
                                  Text(
                                    'Google 계정으로 본인 인증 후,\n뚜비 계정과 안전하게 연결돼요.',
                                    textAlign: TextAlign.center,
                                    style: TextStyle(
                                      color: const Color(0xFF667085),
                                      fontSize: compact ? 13 : 14,
                                      height: 1.5,
                                    ),
                                  ),
                                  const Spacer(),
                                  const Row(
                                    children: [
                                      Expanded(
                                        child: _GoogleBenefit(
                                          icon: Icons.bolt_rounded,
                                          color: Color(0xFF4285F4),
                                          background: Color(0xFFEAF2FF),
                                          firstLine: '간편한',
                                          secondLine: '인증',
                                        ),
                                      ),
                                      _GoogleBenefitDivider(),
                                      Expanded(
                                        child: _GoogleBenefit(
                                          icon: Icons.link_rounded,
                                          color: Color(0xFFFBBC05),
                                          background: Color(0xFFFFF7DE),
                                          firstLine: '빠른',
                                          secondLine: '연동',
                                        ),
                                      ),
                                      _GoogleBenefitDivider(),
                                      Expanded(
                                        child: _GoogleBenefit(
                                          icon: Icons.verified_user_rounded,
                                          color: Color(0xFF34A853),
                                          background: Color(0xFFEAF8EF),
                                          firstLine: '안전한',
                                          secondLine: '로그인',
                                        ),
                                      ),
                                    ],
                                  ),
                                ],
                              ),
                            ),
                          ),
                        ],
                      ),
                    ),
                    SizedBox(height: compact ? 18 : 60),
                    if (_error != null) ...[
                      Padding(
                        padding: const EdgeInsets.symmetric(horizontal: 28),
                        child: Text(
                          _error!,
                          textAlign: TextAlign.center,
                          style: const TextStyle(color: DdubiColors.red),
                        ),
                      ),
                      const SizedBox(height: 10),
                    ],
                    Padding(
                      padding: const EdgeInsets.symmetric(horizontal: 24),
                      child: SizedBox(
                        width: double.infinity,
                        height: 60,
                        child: FilledButton(
                          style: FilledButton.styleFrom(
                            backgroundColor: Colors.white,
                            foregroundColor: navy,
                            disabledBackgroundColor: const Color(0xFFF2F4F7),
                            shape: const StadiumBorder(
                              side: BorderSide(color: Color(0xFFE4E9F0)),
                            ),
                            elevation: 2,
                            shadowColor: const Color(0x222D68B8),
                          ),
                          onPressed: _loading ? null : _login,
                          child: Row(
                            mainAxisAlignment: MainAxisAlignment.center,
                            children: [
                              const Text(
                                'G',
                                style: TextStyle(
                                  color: googleBlue,
                                  fontSize: 28,
                                  fontWeight: FontWeight.w900,
                                  height: 1,
                                ),
                              ),
                              Container(
                                width: 1,
                                height: 24,
                                margin: const EdgeInsets.symmetric(
                                  horizontal: 17,
                                ),
                                color: Color(0xFFD7DDE5),
                              ),
                              Text(
                                _loading ? '로그인 중...' : 'Google 계정으로 계속하기',
                                style: const TextStyle(
                                  fontSize: 16,
                                  fontWeight: FontWeight.w800,
                                ),
                              ),
                            ],
                          ),
                        ),
                      ),
                    ),
                    const SizedBox(height: 12),
                    const Padding(
                      padding: EdgeInsets.fromLTRB(24, 0, 24, 20),
                      child: Text(
                        'ID 토큰은 백엔드에서 한 번 더 안전하게 검증해요.',
                        textAlign: TextAlign.center,
                        style: TextStyle(
                          color: Color(0xFF7A8494),
                          fontSize: 12,
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            );
          },
        ),
      ),
    );
  }

  Future<void> _login() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      switch (widget.provider) {
        case '카카오':
          final service = widget.kakaoLoginService;
          if (service == null) {
            throw const KakaoLoginException('카카오 로그인 설정을 확인해주세요.');
          }
          final accessToken = await service.login(context);
          await widget.repository.loginWithOAuthToken(
            provider: 'KAKAO',
            accessToken: accessToken,
          );
          break;
        case 'Google':
          final service = widget.googleLoginService;
          if (service == null) {
            throw const GoogleLoginException('Google 로그인 설정을 확인해주세요.');
          }
          final idToken = await service.login(context);
          await widget.repository.loginWithOAuthIdToken(
            provider: 'GOOGLE',
            idToken: idToken,
          );
          break;
        case '네이버':
          final service = widget.naverLoginService;
          if (service == null) {
            throw const NaverLoginException('네이버 로그인 설정을 확인해주세요.');
          }
          final authorization = await service.login(context);
          await widget.repository.loginWithOAuthCode(
            provider: 'NAVER',
            authorizationCode: authorization.authorizationCode,
            redirectUri: authorization.redirectUri,
            state: authorization.state,
          );
          break;
        default:
          throw const AuthException(
            AuthErrorCode.serverError,
            message: '지원하지 않는 로그인 방식이에요.',
          );
      }
      if (mounted) widget.onAuthenticated();
    } on AccountLinkRequiredException catch (error) {
      if (!mounted) return;
      await Navigator.of(context).push(
        MaterialPageRoute<void>(
          builder: (_) => AccountLinkPromptScreen(
            provider: _providerLabel(error.provider),
            email: error.email,
            linkToken: error.linkToken,
            repository: widget.repository,
            onLinked: widget.onAuthenticated,
          ),
        ),
      );
    } on KakaoLoginException catch (error) {
      if (mounted) setState(() => _error = error.message);
    } on GoogleLoginException catch (error) {
      if (mounted) setState(() => _error = error.message);
    } on NaverLoginException catch (error) {
      if (mounted) setState(() => _error = error.message);
    } on AuthException catch (error) {
      if (mounted) {
        setState(() => _error = error.message ?? '소셜 로그인에 실패했어요.');
      }
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  String _providerLabel(String provider) => switch (provider.toUpperCase()) {
    'KAKAO' => '카카오',
    'NAVER' => '네이버',
    'GOOGLE' => 'Google',
    _ => provider,
  };
}

class _KakaoBenefit extends StatelessWidget {
  const _KakaoBenefit({
    required this.icon,
    required this.firstLine,
    required this.secondLine,
  });

  final IconData icon;
  final String firstLine;
  final String secondLine;

  @override
  Widget build(BuildContext context) => Column(
    children: [
      Container(
        width: 48,
        height: 48,
        decoration: const BoxDecoration(
          color: Color(0xFFFFF8D6),
          shape: BoxShape.circle,
        ),
        child: Icon(icon, color: Color(0xFFFFBD00), size: 27),
      ),
      const SizedBox(height: 9),
      Text(
        '$firstLine\n$secondLine',
        textAlign: TextAlign.center,
        style: const TextStyle(
          color: Color(0xFF263550),
          fontSize: 12,
          height: 1.35,
          fontWeight: FontWeight.w700,
        ),
      ),
    ],
  );
}

class _KakaoBenefitDivider extends StatelessWidget {
  const _KakaoBenefitDivider();

  @override
  Widget build(BuildContext context) =>
      Container(width: 1, height: 74, color: const Color(0xFFE9ECEF));
}

class _NaverBenefit extends StatelessWidget {
  const _NaverBenefit({
    required this.icon,
    required this.firstLine,
    required this.secondLine,
  });

  final IconData icon;
  final String firstLine;
  final String secondLine;

  @override
  Widget build(BuildContext context) => Column(
    children: [
      Container(
        width: 48,
        height: 48,
        decoration: const BoxDecoration(
          color: Color(0xFFE9FAF0),
          shape: BoxShape.circle,
        ),
        child: Icon(icon, color: Color(0xFF03C75A), size: 26),
      ),
      const SizedBox(height: 9),
      Text(
        '$firstLine\n$secondLine',
        textAlign: TextAlign.center,
        style: const TextStyle(
          color: Color(0xFF263550),
          fontSize: 12,
          height: 1.35,
          fontWeight: FontWeight.w700,
        ),
      ),
    ],
  );
}

class _NaverBenefitDivider extends StatelessWidget {
  const _NaverBenefitDivider();

  @override
  Widget build(BuildContext context) =>
      Container(width: 1, height: 74, color: const Color(0xFFE9ECEF));
}

class _GoogleBenefit extends StatelessWidget {
  const _GoogleBenefit({
    required this.icon,
    required this.color,
    required this.background,
    required this.firstLine,
    required this.secondLine,
  });

  final IconData icon;
  final Color color;
  final Color background;
  final String firstLine;
  final String secondLine;

  @override
  Widget build(BuildContext context) => Column(
    children: [
      Container(
        width: 48,
        height: 48,
        decoration: BoxDecoration(color: background, shape: BoxShape.circle),
        child: Icon(icon, color: color, size: 27),
      ),
      const SizedBox(height: 9),
      Text(
        '$firstLine\n$secondLine',
        textAlign: TextAlign.center,
        style: const TextStyle(
          color: Color(0xFF263550),
          fontSize: 12,
          height: 1.35,
          fontWeight: FontWeight.w700,
        ),
      ),
    ],
  );
}

class _GoogleBenefitDivider extends StatelessWidget {
  const _GoogleBenefitDivider();

  @override
  Widget build(BuildContext context) =>
      Container(width: 1, height: 74, color: const Color(0xFFE9ECEF));
}

class AccountLinkPromptScreen extends StatelessWidget {
  const AccountLinkPromptScreen({
    super.key,
    required this.provider,
    required this.email,
    required this.linkToken,
    required this.repository,
    required this.onLinked,
  });

  final String provider;
  final String email;
  final String linkToken;
  final AuthRepository repository;
  final VoidCallback onLinked;

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('계정 연동')),
    body: SafeArea(
      child: Center(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 560),
          child: ListView(
            padding: const EdgeInsets.all(24),
            children: [
              const Icon(
                Icons.link_rounded,
                size: 72,
                color: DdubiColors.green,
              ),
              const SizedBox(height: 20),
              Text(
                '이미 가입된 이메일이 있어요',
                textAlign: TextAlign.center,
                style: Theme.of(context).textTheme.headlineSmall,
              ),
              const SizedBox(height: 10),
              Text(
                '$provider에서 확인된 이메일과 같은 뚜비 계정을 찾았어요.',
                textAlign: TextAlign.center,
              ),
              const SizedBox(height: 20),
              InfoCard(
                color: const Color(0xFFF0FAF7),
                child: ListTile(
                  contentPadding: EdgeInsets.zero,
                  leading: const CircleAvatar(
                    child: Icon(Icons.email_outlined),
                  ),
                  title: const Text('기존 이메일 계정'),
                  subtitle: Text(email),
                  trailing: const StatusBadge('연동 가능'),
                ),
              ),
              const SizedBox(height: 16),
              const Text(
                '계정을 연동하면 이메일 또는 소셜 로그인 중 어떤 방법을 사용해도 같은 일정과 설정을 이용할 수 있어요.',
              ),
              const SizedBox(height: 28),
              FilledButton(
                key: const Key('startEmailLinkButton'),
                onPressed: () => Navigator.push(
                  context,
                  MaterialPageRoute(
                    builder: (_) => EmailAccountLinkScreen(
                      provider: provider,
                      email: email,
                      linkToken: linkToken,
                      repository: repository,
                      onLinked: onLinked,
                    ),
                  ),
                ),
                child: const Text('이메일 계정 확인하고 연동하기'),
              ),
              const SizedBox(height: 8),
              TextButton(
                onPressed: () => Navigator.pop(context),
                child: const Text('지금은 연동하지 않기'),
              ),
            ],
          ),
        ),
      ),
    ),
  );
}

class EmailAccountLinkScreen extends StatefulWidget {
  const EmailAccountLinkScreen({
    super.key,
    required this.provider,
    required this.email,
    required this.linkToken,
    required this.repository,
    required this.onLinked,
  });

  final String provider;
  final String email;
  final String linkToken;
  final AuthRepository repository;
  final VoidCallback onLinked;

  @override
  State<EmailAccountLinkScreen> createState() => _EmailAccountLinkScreenState();
}

class _EmailAccountLinkScreenState extends State<EmailAccountLinkScreen> {
  final _formKey = GlobalKey<FormState>();
  final _password = TextEditingController();
  bool _obscure = true;
  bool _loading = false;
  String? _serverError;

  @override
  void dispose() {
    _password.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('이메일 계정 확인')),
    body: SafeArea(
      child: Center(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 560),
          child: Form(
            key: _formKey,
            child: ListView(
              padding: const EdgeInsets.all(24),
              children: [
                const TtubeeGuideCard(
                  '기존 이메일 계정이 본인 계정인지 확인할게요.',
                  compact: true,
                ),
                const SizedBox(height: 20),
                TextFormField(
                  key: const Key('linkEmailField'),
                  initialValue: widget.email,
                  readOnly: true,
                  decoration: const InputDecoration(
                    labelText: '이메일',
                    prefixIcon: Icon(Icons.email_outlined),
                  ),
                ),
                const SizedBox(height: 12),
                TextFormField(
                  key: const Key('linkPasswordField'),
                  controller: _password,
                  obscureText: _obscure,
                  validator: validatePassword,
                  decoration: InputDecoration(
                    labelText: '기존 계정 비밀번호',
                    prefixIcon: const Icon(Icons.lock_outline),
                    suffixIcon: IconButton(
                      onPressed: () => setState(() => _obscure = !_obscure),
                      icon: Icon(
                        _obscure
                            ? Icons.visibility_off_outlined
                            : Icons.visibility_outlined,
                      ),
                    ),
                  ),
                ),
                if (_serverError != null)
                  Padding(
                    padding: const EdgeInsets.only(top: 10),
                    child: Text(
                      _serverError!,
                      style: const TextStyle(color: DdubiColors.red),
                    ),
                  ),
                const SizedBox(height: 24),
                FilledButton(
                  key: const Key('completeEmailLinkButton'),
                  onPressed: _loading ? null : _submit,
                  child: _loading
                      ? const SizedBox.square(
                          dimension: 22,
                          child: CircularProgressIndicator(strokeWidth: 2),
                        )
                      : const Text('확인하고 계정 연동'),
                ),
              ],
            ),
          ),
        ),
      ),
    ),
  );

  Future<void> _submit() async {
    setState(() => _serverError = null);
    if (!(_formKey.currentState?.validate() ?? false)) return;
    setState(() => _loading = true);
    try {
      await widget.repository.linkOAuthAccount(
        linkToken: widget.linkToken,
        password: _password.text,
      );
      if (!mounted) return;
      Navigator.pushReplacement(
        context,
        MaterialPageRoute(
          builder: (_) => AccountLinkCompleteScreen(
            provider: widget.provider,
            email: widget.email,
            onContinue: widget.onLinked,
          ),
        ),
      );
    } on AuthException {
      if (mounted) {
        setState(() => _serverError = '기존 이메일 계정의 비밀번호를 확인해주세요.');
      }
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }
}

class AccountLinkCompleteScreen extends StatelessWidget {
  const AccountLinkCompleteScreen({
    super.key,
    required this.provider,
    required this.email,
    required this.onContinue,
  });

  final String provider;
  final String email;
  final VoidCallback onContinue;

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('계정 연동 완료')),
    body: SafeArea(
      child: Center(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 520),
          child: Padding(
            padding: const EdgeInsets.all(24),
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                const Icon(
                  Icons.check_circle,
                  size: 88,
                  color: DdubiColors.green,
                ),
                const SizedBox(height: 20),
                Text(
                  '계정 연동이 완료됐어요!',
                  style: Theme.of(context).textTheme.headlineSmall,
                ),
                const SizedBox(height: 10),
                Text(
                  '$provider 또는 $email로\n같은 뚜비 계정을 이용할 수 있어요.',
                  textAlign: TextAlign.center,
                ),
                const SizedBox(height: 28),
                SizedBox(
                  width: double.infinity,
                  child: FilledButton(
                    key: const Key('linkedAccountHomeButton'),
                    onPressed: onContinue,
                    child: const Text('홈으로 이동'),
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    ),
  );
}

class SignUpScreen extends StatefulWidget {
  const SignUpScreen({super.key, required this.repository});
  final AuthRepository repository;
  @override
  State<SignUpScreen> createState() => _SignUpScreenState();
}

class _SignUpScreenState extends State<SignUpScreen> {
  bool agreed = false;
  bool obscurePassword = true,
      obscureConfirmation = true,
      loading = false,
      codeSent = false,
      emailVerified = false;
  final formKey = GlobalKey<FormState>();
  final email = TextEditingController(),
      nickname = TextEditingController(),
      verificationCode = TextEditingController(),
      password = TextEditingController(),
      confirmation = TextEditingController();
  String? verifiedEmail;
  String? serverError;
  @override
  void dispose() {
    email.dispose();
    nickname.dispose();
    verificationCode.dispose();
    password.dispose();
    confirmation.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('회원가입')),
    body: SafeArea(
      child: Center(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 560),
          child: Form(
            key: formKey,
            child: ListView(
              padding: const EdgeInsets.all(24),
              children: [
                Text(
                  '뚜비와 함께 시작해요',
                  style: Theme.of(context).textTheme.titleLarge,
                ),
                const SizedBox(height: 20),
                TextFormField(
                  controller: email,
                  validator: validateEmail,
                  onChanged: (_) {
                    if (emailVerified) {
                      setState(() {
                        emailVerified = false;
                        verifiedEmail = null;
                      });
                    }
                  },
                  keyboardType: TextInputType.emailAddress,
                  decoration: const InputDecoration(
                    labelText: '이메일',
                    prefixIcon: Icon(Icons.email_outlined),
                  ),
                ),
                const SizedBox(height: 12),
                SizedBox(
                  height: 44,
                  child: OutlinedButton.icon(
                    key: const Key('requestEmailVerificationButton'),
                    onPressed: loading ? null : _requestVerification,
                    icon: const Icon(Icons.mark_email_read_outlined),
                    label: Text(codeSent ? '인증 코드 다시 받기' : '인증 코드 받기'),
                  ),
                ),
                if (codeSent) ...[
                  const SizedBox(height: 12),
                  Row(
                    children: [
                      Expanded(
                        child: TextFormField(
                          key: const Key('emailVerificationCodeField'),
                          controller: verificationCode,
                          keyboardType: TextInputType.number,
                          maxLength: 6,
                          decoration: const InputDecoration(
                            labelText: '인증 코드 6자리',
                            counterText: '',
                          ),
                        ),
                      ),
                      const SizedBox(width: 8),
                      FilledButton(
                        key: const Key('confirmEmailVerificationButton'),
                        onPressed: loading ? null : _confirmVerification,
                        child: const Text('확인'),
                      ),
                    ],
                  ),
                ],
                if (emailVerified)
                  const Padding(
                    padding: EdgeInsets.only(top: 8),
                    child: Text(
                      '이메일 인증이 완료됐어요.',
                      style: TextStyle(color: DdubiColors.green),
                    ),
                  ),
                const SizedBox(height: 12),
                TextFormField(
                  controller: nickname,
                  validator: (value) =>
                      (value ?? '').trim().isEmpty ? '닉네임을 입력해주세요.' : null,
                  decoration: const InputDecoration(
                    labelText: '닉네임',
                    prefixIcon: Icon(Icons.person_outline),
                  ),
                ),
                const SizedBox(height: 12),
                TextFormField(
                  controller: password,
                  validator: validatePassword,
                  obscureText: obscurePassword,
                  decoration: InputDecoration(
                    labelText: '비밀번호',
                    helperText: '영문, 숫자 포함 8자 이상',
                    prefixIcon: const Icon(Icons.lock_outline),
                    suffixIcon: IconButton(
                      onPressed: () =>
                          setState(() => obscurePassword = !obscurePassword),
                      icon: Icon(
                        obscurePassword
                            ? Icons.visibility_off_outlined
                            : Icons.visibility_outlined,
                      ),
                    ),
                  ),
                ),
                const SizedBox(height: 12),
                TextFormField(
                  controller: confirmation,
                  validator: (value) {
                    if ((value ?? '').isEmpty) return '비밀번호를 한 번 더 입력해주세요.';
                    if (value != password.text) return '비밀번호가 일치하지 않습니다.';
                    return null;
                  },
                  obscureText: obscureConfirmation,
                  decoration: InputDecoration(
                    labelText: '비밀번호 확인',
                    prefixIcon: const Icon(Icons.lock_outline),
                    suffixIcon: IconButton(
                      onPressed: () => setState(
                        () => obscureConfirmation = !obscureConfirmation,
                      ),
                      icon: Icon(
                        obscureConfirmation
                            ? Icons.visibility_off_outlined
                            : Icons.visibility_outlined,
                      ),
                    ),
                  ),
                ),
                if (serverError != null)
                  Text(
                    serverError!,
                    style: const TextStyle(color: DdubiColors.red),
                  ),
                const SizedBox(height: 12),
                CheckboxListTile(
                  contentPadding: EdgeInsets.zero,
                  value: agreed,
                  onChanged: (v) => setState(() => agreed = v ?? false),
                  title: const Text('서비스 이용약관과 개인정보 처리방침에 동의합니다.'),
                ),
                const SizedBox(height: 12),
                SizedBox(
                  height: 50,
                  child: FilledButton(
                    onPressed: loading ? null : _submit,
                    child: loading
                        ? const SizedBox.square(
                            dimension: 22,
                            child: CircularProgressIndicator(strokeWidth: 2),
                          )
                        : const Text(
                            '회원가입',
                            style: TextStyle(fontWeight: FontWeight.w800),
                          ),
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    ),
  );

  Future<void> _submit() async {
    setState(() => serverError = null);
    final valid = formKey.currentState?.validate() ?? false;
    if (!agreed) {
      setState(() => serverError = '서비스 이용약관 동의가 필요해요.');
      return;
    }
    if (!emailVerified || verifiedEmail != email.text.trim()) {
      setState(() => serverError = '이메일 인증을 먼저 완료해주세요.');
      return;
    }
    if (!valid) return;
    setState(() => loading = true);
    try {
      await widget.repository.signUp(
        email: email.text.trim(),
        password: password.text,
        nickname: nickname.text.trim(),
      );
      if (!mounted) return;
      await showGeneralDialog<void>(
        context: context,
        barrierDismissible: false,
        barrierLabel: '회원가입 완료',
        transitionDuration: const Duration(milliseconds: 280),
        transitionBuilder: (_, animation, _, child) => FadeTransition(
          opacity: animation,
          child: ScaleTransition(
            scale: Tween(begin: .88, end: 1.0).animate(
              CurvedAnimation(parent: animation, curve: Curves.easeOutBack),
            ),
            child: child,
          ),
        ),
        pageBuilder: (dialogContext, _, _) => Dialog(
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(24),
          ),
          child: Padding(
            padding: const EdgeInsets.all(24),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                const TtubeeCelebrate(
                  asset: 'assets/images/ttubee/celebrate.png',
                  height: 124,
                ),
                const SizedBox(height: 8),
                Text(
                  '회원가입 완료!',
                  textAlign: TextAlign.center,
                  style: Theme.of(dialogContext).textTheme.titleLarge,
                ),
                const SizedBox(height: 8),
                const Text(
                  '이제 뚜비와 함께\n늦지 않는 하루를 시작해볼까요?',
                  textAlign: TextAlign.center,
                ),
                const SizedBox(height: 20),
                PrimaryButton(
                  '로그인하러 가기',
                  onPressed: () => Navigator.pop(dialogContext),
                ),
              ],
            ),
          ),
        ),
      );
      if (mounted) {
        Navigator.pop(context, email.text.trim());
      }
    } on AuthException catch (error) {
      if (mounted) {
        setState(
          () => serverError =
              error.message ??
              (error.code == AuthErrorCode.emailAlreadyExists
                  ? '이미 사용 중인 이메일이에요.'
                  : '잠시 후 다시 시도해주세요.'),
        );
      }
    } finally {
      if (mounted) {
        setState(() => loading = false);
      }
    }
  }

  Future<void> _requestVerification() async {
    final error = validateEmail(email.text);
    if (error != null) {
      setState(() => serverError = error);
      return;
    }
    setState(() {
      loading = true;
      serverError = null;
    });
    try {
      await widget.repository.requestEmailVerification(email.text.trim());
      if (mounted) setState(() => codeSent = true);
    } on AuthException catch (error) {
      if (mounted) {
        setState(() => serverError = error.message ?? '인증 코드를 보내지 못했어요.');
      }
    } finally {
      if (mounted) setState(() => loading = false);
    }
  }

  Future<void> _confirmVerification() async {
    if (!RegExp(r'^\d{6}$').hasMatch(verificationCode.text.trim())) {
      setState(() => serverError = '인증 코드는 숫자 6자리예요.');
      return;
    }
    setState(() {
      loading = true;
      serverError = null;
    });
    try {
      final currentEmail = email.text.trim();
      await widget.repository.confirmEmailVerification(
        email: currentEmail,
        code: verificationCode.text.trim(),
      );
      if (mounted) {
        setState(() {
          emailVerified = true;
          verifiedEmail = currentEmail;
        });
      }
    } on AuthException catch (error) {
      if (mounted) {
        setState(() => serverError = error.message ?? '인증 코드를 확인해주세요.');
      }
    } finally {
      if (mounted) setState(() => loading = false);
    }
  }
}

class AccountRecoveryScreen extends StatefulWidget {
  const AccountRecoveryScreen({super.key, required this.initialIndex});
  final int initialIndex;
  @override
  State<AccountRecoveryScreen> createState() => _AccountRecoveryScreenState();
}

class _AccountRecoveryScreenState extends State<AccountRecoveryScreen>
    with SingleTickerProviderStateMixin {
  late final TabController tabs = TabController(
    length: 2,
    vsync: this,
    initialIndex: widget.initialIndex,
  );
  @override
  void dispose() {
    tabs.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(
      title: const Text('계정 찾기'),
      bottom: TabBar(
        controller: tabs,
        tabs: const [
          Tab(text: '가입 이메일 안내'),
          Tab(text: '비밀번호 재설정'),
        ],
      ),
    ),
    body: TabBarView(
      controller: tabs,
      children: [
        _RecoveryForm(
          title: '가입할 때 사용한 이메일을 확인해요',
          fields: const ['이름', '기억나는 이메일'],
          action: '이메일 확인 요청',
        ),
        _RecoveryForm(
          title: '비밀번호를 다시 설정할게요',
          fields: const ['이메일'],
          action: '재설정 링크 받기',
        ),
      ],
    ),
  );
}

class _RecoveryForm extends StatelessWidget {
  const _RecoveryForm({
    required this.title,
    required this.fields,
    required this.action,
  });
  final String title, action;
  final List<String> fields;
  @override
  Widget build(BuildContext context) => Center(
    child: ConstrainedBox(
      constraints: const BoxConstraints(maxWidth: 560),
      child: ListView(
        padding: const EdgeInsets.all(24),
        children: [
          Image.asset('assets/images/ttubee-guide.png', height: 130),
          const SizedBox(height: 18),
          Text(title, style: Theme.of(context).textTheme.titleLarge),
          const SizedBox(height: 20),
          ...fields.map(
            (field) => Padding(
              padding: const EdgeInsets.only(bottom: 12),
              child: TextField(decoration: InputDecoration(labelText: field)),
            ),
          ),
          const SizedBox(height: 8),
          PrimaryButton(
            action,
            onPressed: () => ScaffoldMessenger.of(context).showSnackBar(
              const SnackBar(content: Text('입력한 정보로 확인 요청을 보냈어요.')),
            ),
          ),
        ],
      ),
    ),
  );
}
