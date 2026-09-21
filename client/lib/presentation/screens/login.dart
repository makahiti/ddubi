import 'dart:async';

import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import '../../core/theme.dart';
import '../../domain/auth.dart';
import '../../services/kakao_login_service.dart';
import '../../services/google_login_service.dart';
import '../../services/naver_login_service.dart';
import '../auth_screens.dart';
import '../../core/config/app_environment.dart';
import 'shared.dart';

class LoginScreen extends StatefulWidget {
  const LoginScreen({
    super.key,
    required this.authRepository,
    required this.kakaoLoginService,
    required this.googleLoginService,
    required this.naverLoginService,
    this.onAuthenticated,
    this.onKeepSignedInChanged,
  });
  final AuthRepository authRepository;
  final KakaoLoginService kakaoLoginService;
  final GoogleLoginService googleLoginService;
  final NaverLoginService naverLoginService;
  final VoidCallback? onAuthenticated;
  final ValueChanged<bool>? onKeepSignedInChanged;

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
                          onChanged: loading
                              ? null
                              : (value) {
                                  setState(() => keepSignedIn = value ?? true);
                                  widget.onKeepSignedInChanged?.call(
                                    keepSignedIn,
                                  );
                                },
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
                            onPressed: () => openPage(
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
                            onPressed: () => openPage(
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
    if (AppEnvironment.allowFakeAuth &&
        email.text == 'localadmin' &&
        password.text == 'local1234') {
      _completeLogin(context);
      return;
    }
    if (!(formKey.currentState?.validate() ?? false)) return;
    widget.onKeepSignedInChanged?.call(keepSignedIn);
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
  void _social(BuildContext c, String provider) {
    widget.onKeepSignedInChanged?.call(keepSignedIn);
    openPage(
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
