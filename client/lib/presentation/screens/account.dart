import 'dart:async';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import '../../core/theme.dart';
import '../../domain/auth.dart';
import '../../domain/models.dart';
import '../../services/kakao_login_service.dart';
import '../../services/google_login_service.dart';
import '../../services/naver_login_service.dart';
import '../../services/app_api.dart';
import '../widgets.dart';
import 'shared.dart';

class AccountManagementScreen extends StatefulWidget {
  const AccountManagementScreen({
    super.key,
    required this.userApi,
    required this.authRepository,
    this.kakaoLoginService,
    this.googleLoginService,
    this.naverLoginService,
  });

  final UserApi userApi;
  final AuthRepository authRepository;
  final KakaoLoginService? kakaoLoginService;
  final GoogleLoginService? googleLoginService;
  final NaverLoginService? naverLoginService;

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
          padding: screenPadding,
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
                    trailing: Text(
                      user.email == null ? '등록' : '변경',
                      style: const TextStyle(
                        color: Color(0xFF0AAE9D),
                        fontWeight: FontWeight.w800,
                      ),
                    ),
                    onTap: _showEmailChange,
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
                    onTap: () => _withdraw(user),
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
          _socialAccountTile(
            provider: 'NAVER',
            label: '네이버',
            connected: providers.contains('NAVER'),
            enabled: widget.naverLoginService != null,
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
      } else if (provider == 'NAVER') {
        // 네이버는 웹 창으로 로그인해서 토큰이 아니라 인가 코드가 온다.
        // 코드를 토큰으로 바꾸는 일은 비밀키가 필요해서 서버가 맡는다.
        final authorization = await widget.naverLoginService!.login(context);
        providers = await widget.authRepository.linkSocialAccount(
          provider: provider,
          authorizationCode: authorization.authorizationCode,
          redirectUri: authorization.redirectUri,
          state: authorization.state,
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

  /// 비밀번호 변경.
  ///
  /// 지금 쓰는 비밀번호를 함께 받는다. 토큰만으로 바꾸게 하면 잠깐 자리를 비운 사이
  /// 폰을 집어 든 사람이 주인을 계정에서 밀어낼 수 있다.
  ///
  /// 바꾸면 서버가 다른 기기를 전부 로그아웃시킨다. 이 기기만 새 토큰을 받아 이어서 쓴다.
  Future<void> _showPasswordChange() async {
    final current = TextEditingController();
    final next = TextEditingController();
    final again = TextEditingController();
    final changed = await showModalBottomSheet<bool>(
      context: context,
      isScrollControlled: true,
      useSafeArea: true,
      showDragHandle: true,
      builder: (sheetContext) => _AccountFormSheet(
        title: '비밀번호 변경',
        icon: Icons.lock_reset_rounded,
        description: '바꾸면 이 기기를 뺀 나머지 기기는 모두 로그아웃돼요.',
        submitLabel: '변경하기',
        fields: [
          _AccountFormField(
            controller: current,
            label: '현재 비밀번호',
            obscure: true,
          ),
          _AccountFormField(
            controller: next,
            label: '새 비밀번호 (8자 이상)',
            obscure: true,
          ),
          _AccountFormField(
            controller: again,
            label: '새 비밀번호 확인',
            obscure: true,
          ),
        ],
        validate: () {
          if (current.text.isEmpty) return '현재 비밀번호를 입력해주세요.';
          if (next.text.length < 8) return '새 비밀번호는 8자 이상이어야 해요.';
          if (next.text != again.text) return '새 비밀번호가 서로 달라요.';
          if (next.text == current.text) return '지금 쓰는 비밀번호와 다른 값을 입력해주세요.';
          return null;
        },
        onSubmit: () => widget.authRepository.changePassword(
          currentPassword: current.text,
          newPassword: next.text,
        ),
      ),
    );
    current.dispose();
    next.dispose();
    again.dispose();
    if (changed == true && mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('비밀번호를 바꿨어요. 다른 기기는 다시 로그인해야 해요.')),
      );
    }
  }

  /// 이메일 등록·변경.
  ///
  /// 새 주소로 인증 코드를 보내고 확인한 뒤에야 바꾼다. 소유 확인 없이 바꾸게 두면
  /// 남의 이메일을 자기 계정에 달아 선점할 수 있다.
  Future<void> _showEmailChange() async {
    final email = TextEditingController();
    final code = TextEditingController();
    final password = TextEditingController();
    final changed = await showModalBottomSheet<bool>(
      context: context,
      isScrollControlled: true,
      useSafeArea: true,
      showDragHandle: true,
      builder: (sheetContext) => _AccountFormSheet(
        title: '이메일 변경',
        icon: Icons.mark_email_unread_outlined,
        description: '새 주소로 인증 코드를 보내 확인한 뒤에 바꿔요.',
        submitLabel: '변경하기',
        sendCodeLabel: '인증 코드 받기',
        onSendCode: () =>
            widget.authRepository.requestEmailVerification(email.text.trim()),
        canSendCode: () =>
            email.text.trim().contains('@') ? null : '이메일 주소를 확인해주세요.',
        fields: [
          _AccountFormField(controller: email, label: '새 이메일'),
          _AccountFormField(controller: code, label: '인증 코드 6자리'),
          _AccountFormField(
            controller: password,
            label: '현재 비밀번호 (소셜 전용 계정은 비워두세요)',
            obscure: true,
          ),
        ],
        validate: () {
          if (!email.text.trim().contains('@')) return '이메일 주소를 확인해주세요.';
          if (code.text.trim().length != 6) return '인증 코드는 숫자 6자리예요.';
          return null;
        },
        onSubmit: () async {
          await widget.authRepository.confirmEmailVerification(
            email: email.text.trim(),
            code: code.text.trim(),
          );
          await widget.authRepository.changeEmail(
            newEmail: email.text.trim(),
            password: password.text.isEmpty ? null : password.text,
          );
        },
      ),
    );
    email.dispose();
    code.dispose();
    password.dispose();
    if (changed == true && mounted) {
      setState(() => _profile = widget.userApi.getMe());
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('이메일을 바꿨어요. 다음 로그인부터 새 주소를 써요.')),
      );
    }
  }

  Future<void> _withdraw(UserProfile user) async {
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
    if (confirmed != true || !mounted) return;

    // 서버는 LOCAL 인증 수단이 있는 계정에 비밀번호를 요구한다(토큰만으로 남이 지우지
    // 못하게 막는 장치다). 예전에는 앱이 비밀번호를 보내지 않아 이메일 가입자는 탈퇴가
    // 항상 실패했다. 소셜 전용 계정은 비밀번호 자체가 없어 그대로 보낸다.
    if (user.provider != null && user.provider != 'LOCAL') {
      await _submitWithdraw(null);
      return;
    }

    final password = TextEditingController();
    final done = await showModalBottomSheet<bool>(
      context: context,
      isScrollControlled: true,
      useSafeArea: true,
      showDragHandle: true,
      builder: (sheetContext) => _AccountFormSheet(
        title: '회원 탈퇴',
        icon: Icons.person_remove_outlined,
        description: '본인 확인을 위해 비밀번호를 입력해주세요. 탈퇴하면 되돌릴 수 없어요.',
        submitLabel: '탈퇴하기',
        fields: [
          _AccountFormField(
            controller: password,
            label: user.provider == 'LOCAL'
                ? '현재 비밀번호'
                : '현재 비밀번호 (소셜 전용 계정은 비워두세요)',
            obscure: true,
          ),
        ],
        // 로그인 수단을 모를 때(provider 미제공)는 비워두는 것을 허용한다. 소셜 전용
        // 계정이라면 서버가 비밀번호 확인을 건너뛴다.
        validate: () => user.provider == 'LOCAL' && password.text.isEmpty
            ? '비밀번호를 입력해주세요.'
            : null,
        onSubmit: () =>
            _requestWithdraw(password.text.isEmpty ? null : password.text),
      ),
    );
    password.dispose();
    if (done == true && mounted) context.go('/login');
  }

  /// 비밀번호 없이 보내는 경로(소셜 전용 계정). 실패하면 이유를 구분해 알린다.
  Future<void> _submitWithdraw(String? password) async {
    try {
      await _requestWithdraw(password);
      if (mounted) context.go('/login');
    } on AuthException catch (error) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(error.message ?? '회원 탈퇴를 처리하지 못했어요.')),
      );
    }
  }

  /// 탈퇴 요청 한 번. 실패 이유를 사용자 문구로 옮겨 [AuthException] 으로 올린다 —
  /// 입력 시트가 그 문구를 그대로 보여주고 시트를 닫지 않는다.
  Future<void> _requestWithdraw(String? password) async {
    try {
      await widget.userApi.withdraw(password: password);
    } on DioException catch (error) {
      final status = error.response?.statusCode;
      if (status == 401) {
        throw const AuthException(
          AuthErrorCode.invalidCredentials,
          message: '비밀번호가 맞지 않아요.',
        );
      }
      if (status == null) {
        throw const AuthException(
          AuthErrorCode.connectionFailed,
          message: '연결 상태를 확인해주세요.',
        );
      }
      throw const AuthException(
        AuthErrorCode.serverError,
        message: '회원 탈퇴를 처리하지 못했어요. 잠시 뒤 다시 시도해주세요.',
      );
    }
  }
}

/// 계정 관리 입력 칸 하나. [_AccountFormSheet] 가 순서대로 그린다.
class _AccountFormField {
  const _AccountFormField({
    required this.controller,
    required this.label,
    this.obscure = false,
  });

  final TextEditingController controller;
  final String label;
  final bool obscure;
}

/// 비밀번호 변경·이메일 변경이 함께 쓰는 입력 시트.
///
/// 두 화면이 하는 일은 "몇 칸 받아서 한 번 보내고 결과를 알린다" 로 같다. 다른 것은
/// 칸 구성과 보낼 내용뿐이라 그 둘만 밖에서 받는다.
///
/// 닫을 때 `true` 를 돌려주면 성공이다. 실패는 시트 안에서 문구로 보여주고 닫지 않는다 —
/// 닫아버리면 사용자가 방금 친 값을 다시 입력해야 한다.
class _AccountFormSheet extends StatefulWidget {
  const _AccountFormSheet({
    required this.title,
    required this.icon,
    required this.description,
    required this.submitLabel,
    required this.fields,
    required this.validate,
    required this.onSubmit,
    this.sendCodeLabel,
    this.onSendCode,
    this.canSendCode,
  });

  final String title;
  final IconData icon;
  final String description;
  final String submitLabel;
  final List<_AccountFormField> fields;

  /// 보내기 전에 앱에서 먼저 거르는 검사. 문제가 없으면 null.
  final String? Function() validate;
  final Future<void> Function() onSubmit;

  /// 인증 코드 받기 버튼. 이메일 변경만 쓴다.
  final String? sendCodeLabel;
  final Future<void> Function()? onSendCode;
  final String? Function()? canSendCode;

  @override
  State<_AccountFormSheet> createState() => _AccountFormSheetState();
}

class _AccountFormSheetState extends State<_AccountFormSheet> {
  String? _error;
  String? _notice;
  bool _busy = false;

  Future<void> _sendCode() async {
    final problem = widget.canSendCode?.call();
    if (problem != null) {
      setState(() => _error = problem);
      return;
    }
    setState(() {
      _busy = true;
      _error = null;
      _notice = null;
    });
    try {
      await widget.onSendCode!();
      if (mounted) setState(() => _notice = '인증 코드를 보냈어요. 메일함을 확인해주세요.');
    } on AuthException catch (error) {
      if (mounted) setState(() => _error = error.message ?? '인증 코드를 보내지 못했어요.');
    } on Object {
      if (mounted) setState(() => _error = '인증 코드를 보내지 못했어요.');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _submit() async {
    final problem = widget.validate();
    if (problem != null) {
      setState(() => _error = problem);
      return;
    }
    setState(() {
      _busy = true;
      _error = null;
      _notice = null;
    });
    try {
      await widget.onSubmit();
      if (mounted) Navigator.pop(context, true);
    } on AuthException catch (error) {
      if (mounted) setState(() => _error = error.message ?? '처리하지 못했어요.');
    } on Object {
      if (mounted) setState(() => _error = '처리하지 못했어요. 잠시 뒤 다시 시도해주세요.');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) => Padding(
    padding: EdgeInsets.fromLTRB(
      24,
      8,
      24,
      24 + MediaQuery.viewInsetsOf(context).bottom,
    ),
    child: SingleChildScrollView(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Center(
            child: CircleAvatar(
              radius: 31,
              backgroundColor: const Color(0xFFDDF8F3),
              child: Icon(
                widget.icon,
                color: const Color(0xFF0CB3A2),
                size: 32,
              ),
            ),
          ),
          const SizedBox(height: 14),
          Text(
            widget.title,
            textAlign: TextAlign.center,
            style: const TextStyle(
              color: Color(0xFF102454),
              fontSize: 22,
              fontWeight: FontWeight.w900,
            ),
          ),
          const SizedBox(height: 8),
          Text(
            widget.description,
            textAlign: TextAlign.center,
            style: const TextStyle(color: Color(0xFF5B6B8C), height: 1.5),
          ),
          const SizedBox(height: 18),
          for (final field in widget.fields) ...[
            TextField(
              controller: field.controller,
              obscureText: field.obscure,
              enabled: !_busy,
              decoration: InputDecoration(
                labelText: field.label,
                border: const OutlineInputBorder(),
              ),
            ),
            const SizedBox(height: 10),
          ],
          if (widget.onSendCode != null) ...[
            OutlinedButton(
              onPressed: _busy ? null : _sendCode,
              child: Text(widget.sendCodeLabel ?? '인증 코드 받기'),
            ),
            const SizedBox(height: 10),
          ],
          if (_notice != null)
            Padding(
              padding: const EdgeInsets.only(bottom: 8),
              child: Text(
                _notice!,
                style: const TextStyle(color: DdubiColors.green),
              ),
            ),
          if (_error != null)
            Padding(
              padding: const EdgeInsets.only(bottom: 8),
              child: Text(
                _error!,
                style: const TextStyle(color: Color(0xFFD64545)),
              ),
            ),
          if (_busy) const LinearProgressIndicator(),
          const SizedBox(height: 6),
          FilledButton(
            onPressed: _busy ? null : _submit,
            child: Text(widget.submitLabel),
          ),
        ],
      ),
    ),
  );
}
