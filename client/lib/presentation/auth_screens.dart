import 'package:flutter/material.dart';

import '../core/theme.dart';
import '../domain/auth.dart';
import 'widgets.dart';

class SocialAuthScreen extends StatelessWidget {
  const SocialAuthScreen({
    super.key,
    required this.provider,
    required this.onAuthenticated,
  });
  final String provider;
  final VoidCallback onAuthenticated;

  @override
  Widget build(BuildContext context) {
    final isKakao = provider == '카카오';
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
                    '$provider 계정으로 계속하기',
                    style: Theme.of(context).textTheme.titleLarge,
                  ),
                  const SizedBox(height: 10),
                  Text(
                    '$provider OAuth 앱 키와 Redirect URI가 연결되면\n이 화면에서 공식 인증 화면으로 이동합니다.',
                    textAlign: TextAlign.center,
                    style: Theme.of(context).textTheme.bodyMedium,
                  ),
                  const Spacer(),
                  SizedBox(
                    width: double.infinity,
                    height: 52,
                    child: FilledButton(
                      style: FilledButton.styleFrom(
                        backgroundColor: isKakao
                            ? const Color(0xFFFFD600)
                            : Colors.white,
                        foregroundColor: isKakao
                            ? Colors.black
                            : DdubiColors.navy,
                        side: BorderSide(
                          color: isKakao
                              ? const Color(0xFFFFD600)
                              : DdubiColors.border,
                        ),
                      ),
                      onPressed: onAuthenticated,
                      child: Text(
                        '개발용 $provider 인증 완료',
                        style: const TextStyle(fontWeight: FontWeight.w800),
                      ),
                    ),
                  ),
                  const SizedBox(height: 10),
                  Text(
                    '실서비스에서는 공식 SDK 인증 성공 후에만 진행돼요.',
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
    } on AuthException {
      if (mounted) setState(() => serverError = '이메일 또는 비밀번호를 확인해주세요.');
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
  bool obscurePassword = true, obscureConfirmation = true, loading = false;
  final formKey = GlobalKey<FormState>();
  final email = TextEditingController(),
      password = TextEditingController(),
      confirmation = TextEditingController();
  String? serverError;
  @override
  void dispose() {
    email.dispose();
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
                  keyboardType: TextInputType.emailAddress,
                  decoration: const InputDecoration(
                    labelText: '이메일',
                    prefixIcon: Icon(Icons.email_outlined),
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
    if (!valid) return;
    setState(() => loading = true);
    try {
      await widget.repository.signUp(
        email: email.text.trim(),
        password: password.text,
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
                Container(
                  width: 58,
                  height: 58,
                  decoration: const BoxDecoration(
                    color: Color(0xFFE3FAF6),
                    shape: BoxShape.circle,
                  ),
                  child: const Icon(
                    Icons.check_rounded,
                    color: DdubiColors.blue,
                    size: 38,
                  ),
                ),
                const SizedBox(height: 8),
                Image.asset('assets/images/ttubee-guide.png', height: 105),
                const SizedBox(height: 8),
                Text(
                  '회원가입이 완료되었어요!',
                  textAlign: TextAlign.center,
                  style: Theme.of(dialogContext).textTheme.titleLarge,
                ),
                const SizedBox(height: 8),
                const Text(
                  '이제 뚜비와 함께 일정을\n편하게 관리해보세요.',
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
          () => serverError = error.code == AuthErrorCode.emailAlreadyExists
              ? '이미 사용 중인 이메일이에요.'
              : '잠시 후 다시 시도해주세요.',
        );
      }
    } finally {
      if (mounted) {
        setState(() => loading = false);
      }
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
