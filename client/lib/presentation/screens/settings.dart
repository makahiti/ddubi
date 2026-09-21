import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:go_router/go_router.dart';
import 'package:image_picker/image_picker.dart';

import '../../core/constants/layout_constants.dart';
import '../../core/theme.dart';
import '../../domain/auth.dart';
import '../../domain/models.dart';
import '../../services/app_api.dart';
import '../help_inquiry_screen.dart';
import '../widgets.dart';
import '../../core/diagnostics/developer_diagnostics.dart';
import '../../core/config/app_environment.dart';
import 'journey.dart';
import 'shared.dart';

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
  int? _selectedProfileCharacter = ProfileImageCache.characterIndex;
  Uint8List? _selectedProfileBytes = ProfileImageCache.bytes;
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
      final location = await AppPermissionController.hasLocationPermission();
      final notification =
          await AppPermissionController.hasNotificationPermission();
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
            // 개발자 옵션은 개발 환경에서만 보인다. 운영 빌드에서는 카드 자체가 없다.
            if (AppEnvironment.allowDeveloperTools) ...[
              _developerOptionsCard(),
              const SizedBox(height: 14),
            ],
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
    padding: screenPadding,
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

  Widget _mobileAccountCard(
    BuildContext context,
    UserProfile user,
  ) => _settingsCard(
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
        // 계정 관리로 들어가는 자리. 비밀번호를 한 번 확인한 뒤 실제 화면으로 보낸다.
        //
        // 예전에는 여기가 누를 수 없는 글자 두 줄이었다. "준비 중" 이라고만 적혀 있었고
        // 화면으로 가는 길이 없어서, 비밀번호·이메일 변경과 소셜 연결을 만들어 두고도
        // 앱에서는 닿을 수 없었다(2026-09-18).
        Material(
          color: Colors.transparent,
          child: InkWell(
            key: const Key('accountManagementMenuButton'),
            onTap: () => _openAccountManagement(context, user),
            borderRadius: BorderRadius.circular(12),
            child: Padding(
              padding: const EdgeInsets.symmetric(vertical: 4),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: const [
                  Row(
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
                      Spacer(),
                      Icon(
                        Icons.chevron_right_rounded,
                        color: Color(0xFF64708B),
                      ),
                    ],
                  ),
                  SizedBox(height: 4),
                  Padding(
                    padding: EdgeInsets.only(left: 32),
                    child: Text(
                      '비밀번호를 확인한 뒤 비밀번호·이메일 변경과 소셜 연결을 할 수 있어요.',
                      style: TextStyle(color: Color(0xFF64708B), fontSize: 12),
                    ),
                  ),
                ],
              ),
            ),
          ),
        ),
      ],
    ),
  );

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
                                isLocalPreview:
                                    AppEnvironment.allowFakeAuth &&
                                    user.id == 'debug',
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
                                  isLocalPreview:
                                      AppEnvironment.allowFakeAuth &&
                                      user.id == 'debug',
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
      ProfileImageCache.characterIndex = selection.characterIndex;
      ProfileImageCache.bytes = selection.bytes;
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
      await AppPermissionController.openSettings();
      return;
    }
    final allowed = await AppPermissionController.requestLocationPermission();
    if (mounted) setState(() => _locationAllowed = allowed);
  }

  Future<void> _changeNotificationPermission() async {
    if (_notificationAllowed == true) {
      await AppPermissionController.openSettings();
      return;
    }
    final allowed =
        await AppPermissionController.requestNotificationPermission();
    if (mounted) setState(() => _notificationAllowed = allowed);
  }

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
              'assets/images/ttubee/route-guide.png',
              width: 88,
              height: 88,
              fit: BoxFit.contain,
              semanticLabel: '일정별 교통 상황을 안내하는 뚜비',
            ),
          ),
        ),
        Padding(
          padding: const EdgeInsets.only(right: 60),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              // 제목·아이콘·그림을 들어가는 화면(일정별 교통 상황)과 맞춘다. 예전에는
              // 제목이 '서비스 연결 안내' 이고 연결 끊김 아이콘에 걱정하는 뚜비까지 붙어
              // 있어서, 기능 안내가 아니라 고장 알림으로 보였다.
              const SectionHeader('일정별 교통 상황'),
              const SizedBox(height: 10),
              const Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Icon(
                    Icons.directions_transit_outlined,
                    color: DdubiColors.blue,
                    size: 20,
                  ),
                  SizedBox(width: 6),
                  Expanded(
                    child: Text(
                      '등록한 일정마다 교통 상황을 확인할 수 있어요.',
                      style: TextStyle(
                        color: DdubiColors.navy,
                        fontSize: 13,
                        fontWeight: FontWeight.w800,
                      ),
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 7),
              const Text(
                '일정별로 계산 결과와 마지막 확인 시각을 볼 수 있어요.',
                style: TextStyle(
                  color: Color(0xFF64708B),
                  fontSize: 12,
                  height: 1.4,
                ),
              ),
              const SizedBox(height: 12),
              OutlinedButton.icon(
                onPressed: () => openPage(
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

  /// 개발자 옵션. 켜면 일정 상세의 이동 예측 카드에 모델 버전·적용 여부·원본 코드가 붙는다.
  /// 디버그 빌드는 기본 켜짐, 릴리스는 기본 꺼짐이며 앱을 다시 시작하면 빌드 기본값으로 돌아간다.
  Widget _developerOptionsCard() => Material(
    color: Colors.white,
    borderRadius: BorderRadius.circular(22),
    child: ValueListenableBuilder<bool>(
      valueListenable: DeveloperDiagnostics.enabled,
      builder: (_, enabled, _) => SwitchListTile(
        key: const Key('developerDiagnosticsSwitch'),
        value: enabled,
        onChanged: (value) => DeveloperDiagnostics.enabled.value = value,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(22)),
        contentPadding: const EdgeInsets.symmetric(horizontal: 17, vertical: 6),
        title: const Text(
          '개발자 진단 표시',
          style: TextStyle(fontWeight: FontWeight.w700),
        ),
        subtitle: const Text(
          '이동 예측 카드에 모델 버전·적용 여부·원본 코드를 함께 보여줘요. 일반 사용에는 필요 없어요.',
        ),
      ),
    ),
  );

  Widget _helpCard(BuildContext context) => Material(
    color: Colors.white,
    borderRadius: BorderRadius.circular(22),
    child: InkWell(
      key: const Key('helpInquiryButton'),
      onTap: () => openPage(context, const HelpInquiryScreen()),
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
                  subtitle: Text(formatFullDate(item.arriveBy)),
                  trailing: Text(arrivalResultText(item.result)),
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
        const SectionHeader('일정별 교통 상황'),
        const SizedBox(height: 10),
        const StatusBadge(
          '등록한 일정마다 교통 상황을 확인할 수 있어요.',
          tone: DdubiColors.blue,
          icon: Icons.directions_transit_outlined,
        ),
        const SizedBox(height: 6),
        const Text('일정별로 계산 결과와 마지막 확인 시각을 볼 수 있어요.'),
        const SizedBox(height: 12),
        Align(
          alignment: Alignment.centerRight,
          child: OutlinedButton(
            onPressed: () => openPage(
              context,
              DelayAlertScreen(repository: widget.repository),
            ),
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
