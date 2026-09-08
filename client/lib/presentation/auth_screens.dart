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
          final accessToken = await service.login(context);
          await widget.repository.loginWithOAuthToken(
            provider: 'NAVER',
            accessToken: accessToken,
          );
          break;
        default:
          throw const AuthException(
            AuthErrorCode.serverError,
            message: '지원하지 않는 로그인 방식이에요.',
          );
      }
      if (mounted) widget.onAuthenticated();
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
}

class AccountLinkPromptScreen extends StatelessWidget {
  const AccountLinkPromptScreen({
    super.key,
    required this.provider,
    required this.email,
    required this.repository,
    required this.onLinked,
  });

  final String provider;
  final String email;
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
    required this.repository,
    required this.onLinked,
  });

  final String provider;
  final String email;
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
      await widget.repository.login(
        email: widget.email,
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

class EmailLoginScreen extends StatefulWidget {
  const EmailLoginScreen({
    super.key,
    required this.onAuthenticated,
    required this.repository,
  });
  final VoidCallback onAuthenticated;
  final AuthRepository repository;
  @override
  State<EmailLoginScreen> createState() => _EmailLoginScreenState();
}

class _EmailLoginScreenState extends State<EmailLoginScreen> {
  final email = TextEditingController();
  final password = TextEditingController();
  final formKey = GlobalKey<FormState>();
  bool obscure = true;
  bool loading = false;
  String? serverError;
  @override
  void dispose() {
    email.dispose();
    password.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('이메일로 로그인')),
    body: SafeArea(
      child: Center(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 560),
          child: Form(
            key: formKey,
            child: ListView(
              padding: const EdgeInsets.all(24),
              children: [
                Center(
                  child: Image.asset(
                    'assets/images/ttubee-guide.png',
                    height: 130,
                  ),
                ),
                const SizedBox(height: 16),
                Text(
                  '다시 만나서 반가워요!',
                  style: Theme.of(context).textTheme.titleLarge,
                ),
                const SizedBox(height: 6),
                const Text('가입한 이메일과 비밀번호를 입력해주세요.'),
                const SizedBox(height: 24),
                TextFormField(
                  controller: email,
                  validator: validateEmail,
                  keyboardType: TextInputType.emailAddress,
                  autofillHints: const [AutofillHints.email],
                  decoration: const InputDecoration(
                    labelText: '이메일',
                    prefixIcon: Icon(Icons.email_outlined),
                  ),
                ),
                const SizedBox(height: 12),
                TextFormField(
                  controller: password,
                  validator: validatePassword,
                  obscureText: obscure,
                  autofillHints: const [AutofillHints.password],
                  decoration: InputDecoration(
                    labelText: '비밀번호',
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
                if (serverError != null)
                  Padding(
                    padding: const EdgeInsets.only(top: 10),
                    child: Text(
                      serverError!,
                      style: const TextStyle(color: DdubiColors.red),
                    ),
                  ),
                const SizedBox(height: 10),
                Row(
                  mainAxisAlignment: MainAxisAlignment.end,
                  children: [
                    TextButton(
                      onPressed: () => Navigator.push(
                        context,
                        MaterialPageRoute(
                          builder: (_) =>
                              const AccountRecoveryScreen(initialIndex: 0),
                        ),
                      ),
                      child: const Text('이메일을 잊으셨나요?'),
                    ),
                    TextButton(
                      onPressed: () => Navigator.push(
                        context,
                        MaterialPageRoute(
                          builder: (_) =>
                              const AccountRecoveryScreen(initialIndex: 1),
                        ),
                      ),
                      child: const Text('비밀번호를 잊으셨나요?'),
                    ),
                  ],
                ),
                const SizedBox(height: 8),
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
                            '로그인',
                            style: TextStyle(fontWeight: FontWeight.w800),
                          ),
                  ),
                ),
                const SizedBox(height: 12),
                OutlinedButton(
                  onPressed: () async {
                    final registeredEmail = await Navigator.push<String>(
                      context,
                      MaterialPageRoute(
                        builder: (_) =>
                            SignUpScreen(repository: widget.repository),
                      ),
                    );
                    if (registeredEmail != null) email.text = registeredEmail;
                  },
                  child: const Text('처음이신가요? 회원가입'),
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
    if (!(formKey.currentState?.validate() ?? false)) return;
    setState(() => loading = true);
    try {
      await widget.repository.login(
        email: email.text.trim(),
        password: password.text,
      );
      if (mounted) widget.onAuthenticated();
    } on AuthException catch (error) {
      if (mounted) {
        setState(() => serverError = error.message ?? '이메일 또는 비밀번호를 확인해주세요.');
      }
    } finally {
      if (mounted) setState(() => loading = false);
    }
  }
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
