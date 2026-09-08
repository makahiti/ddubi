enum AuthErrorCode {
  invalidCredentials,
  emailAlreadyExists,
  connectionFailed,
  registrationRequiresVerification,
  serverError,
}

class AuthException implements Exception {
  const AuthException(this.code, {this.message});

  final AuthErrorCode code;
  final String? message;
}

class AuthUser {
  const AuthUser({
    required this.id,
    required this.provider,
    required this.email,
    required this.nickname,
  });

  final int id;
  final String provider;
  final String? email;
  final String nickname;

  factory AuthUser.fromJson(Map<String, dynamic> json) {
    return AuthUser(
      id: (json['id'] as num).toInt(),
      provider: json['provider'] as String,
      email: json['email'] as String?,
      nickname: json['nickname'] as String,
    );
  }
}

class AuthSession {
  const AuthSession({
    required this.accessToken,
    required this.refreshToken,
    required this.tokenType,
    required this.deviceId,
    required this.user,
  });

  final String accessToken;
  final String refreshToken;
  final String tokenType;
  final String deviceId;
  final AuthUser user;

  factory AuthSession.fromJson(Map<String, dynamic> json) {
    return AuthSession(
      accessToken: json['accessToken'] as String,
      refreshToken: json['refreshToken'] as String,
      tokenType: json['tokenType'] as String? ?? 'Bearer',
      deviceId: json['deviceId'] as String,
      user: AuthUser.fromJson(json['user'] as Map<String, dynamic>),
    );
  }
}

class AuthSessionStore {
  AuthSession? _session;

  AuthSession? get session => _session;
  String? get accessToken => _session?.accessToken;

  void save(AuthSession session) => _session = session;
  void clear() => _session = null;
}

abstract interface class AuthRepository {
  Future<void> login({required String email, required String password});
  Future<void> loginWithOAuthToken({
    required String provider,
    required String accessToken,
  });
  Future<void> loginWithOAuthIdToken({
    required String provider,
    required String idToken,
  });
  Future<void> requestEmailVerification(String email);
  Future<void> confirmEmailVerification({
    required String email,
    required String code,
  });
  Future<void> signUp({
    required String email,
    required String password,
    required String nickname,
  });
  Future<void> logout();
}

class MockAuthRepository implements AuthRepository {
  final Map<String, String> _credentials = {'member@ddubi.app': 'password123'};
  @override
  Future<void> login({required String email, required String password}) async {
    await Future<void>.delayed(const Duration(milliseconds: 350));
    if (_credentials[email.toLowerCase()] == password) return;
    throw const AuthException(AuthErrorCode.invalidCredentials);
  }

  @override
  Future<void> loginWithOAuthToken({
    required String provider,
    required String accessToken,
  }) async {
    await Future<void>.delayed(const Duration(milliseconds: 100));
  }

  @override
  Future<void> loginWithOAuthIdToken({
    required String provider,
    required String idToken,
  }) async {
    await Future<void>.delayed(const Duration(milliseconds: 100));
  }

  @override
  Future<void> requestEmailVerification(String email) async {}

  @override
  Future<void> confirmEmailVerification({
    required String email,
    required String code,
  }) async {}

  @override
  Future<void> signUp({
    required String email,
    required String password,
    required String nickname,
  }) async {
    await Future<void>.delayed(const Duration(milliseconds: 450));
    if (_credentials.containsKey(email.toLowerCase())) {
      throw const AuthException(AuthErrorCode.emailAlreadyExists);
    }
    _credentials[email.toLowerCase()] = password;
  }

  @override
  Future<void> logout() async {}
}

String? validateEmail(String? value) {
  final email = value?.trim() ?? '';
  if (email.isEmpty) return '이메일을 입력해주세요.';
  if (!RegExp(r'^[^@\s]+@[^@\s]+\.[^@\s]+$').hasMatch(email)) {
    return '올바른 이메일 형식으로 입력해주세요.';
  }
  return null;
}

String? validatePassword(String? value) {
  final password = value ?? '';
  if (password.isEmpty) return '비밀번호를 입력해주세요.';
  if (password.length < 8 ||
      !RegExp('[A-Za-z]').hasMatch(password) ||
      !RegExp('[0-9]').hasMatch(password)) {
    return '영문과 숫자를 포함해 8자 이상 입력해주세요.';
  }
  return null;
}
