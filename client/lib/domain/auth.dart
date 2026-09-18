import 'dart:convert';

import 'package:flutter/foundation.dart';

enum AuthErrorCode {
  invalidCredentials,
  emailAlreadyExists,
  accountLinkRequired,
  connectionFailed,
  registrationRequiresVerification,
  serverError,
}

class AuthException implements Exception {
  const AuthException(this.code, {this.message});

  final AuthErrorCode code;
  final String? message;
}

class AccountLinkRequiredException extends AuthException {
  const AccountLinkRequiredException({
    required this.linkToken,
    required this.provider,
    required this.email,
  }) : super(
         AuthErrorCode.accountLinkRequired,
         message: '기존 이메일 계정 확인이 필요합니다.',
       );

  final String linkToken;
  final String provider;
  final String email;
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

  AuthSession withRefreshedTokens(Map<String, dynamic> json) {
    return AuthSession(
      accessToken: json['accessToken'] as String,
      refreshToken: json['refreshToken'] as String,
      tokenType: json['tokenType'] as String? ?? tokenType,
      deviceId: deviceId,
      user: user,
    );
  }
}

abstract interface class SessionStorage {
  Future<String?> read();
  Future<void> write(String? value);
}

class AuthSessionStore extends ChangeNotifier {
  AuthSessionStore({SessionStorage? storage})
    : _storage = storage; // ignore: prefer_initializing_formals
  final SessionStorage? _storage;
  Future<void> _writes = Future<void>.value();
  bool keepSignedIn = true;
  bool hasLaunched = false;
  int _revision = 0;
  int get revision => _revision;
  AuthSession? _session;

  AuthSession? get session => _session;
  String? get accessToken => _session?.accessToken;

  Future<void> restore() async {
    final revision = _revision;
    final raw = await _storage?.read();
    if (revision != _revision || raw == null) return;
    try {
      _session = AuthSession.fromJson(jsonDecode(raw) as Map<String, dynamic>);
      if (_session!.accessToken.isEmpty ||
          _session!.refreshToken.isEmpty ||
          _session!.deviceId.isEmpty) {
        throw const FormatException();
      }
      _revision++;
      notifyListeners();
    } on Object {
      await clear();
    }
  }

  Future<void> _persist(String? value) {
    final next = _writes.then((_) => _storage?.write(value));
    // Preserve operation ordering even if an earlier platform write failed.
    _writes = next.catchError((Object _) {});
    return next;
  }

  Future<void> save(AuthSession session) {
    _session = session;
    _revision++;
    notifyListeners();
    return _persist(
      keepSignedIn
          ? jsonEncode({
              'accessToken': session.accessToken,
              'refreshToken': session.refreshToken,
              'tokenType': session.tokenType,
              'deviceId': session.deviceId,
              'user': {
                'id': session.user.id,
                'provider': session.user.provider,
                'email': session.user.email,
                'nickname': session.user.nickname,
              },
            })
          : null,
    );
  }

  Future<void> clear() {
    _session = null;
    _revision++;
    notifyListeners();
    return _persist(null);
  }
}

abstract interface class AuthRepository {
  Future<void> login({required String email, required String password});
  Future<void> loginWithOAuthToken({
    required String provider,
    required String accessToken,
  });
  Future<void> loginWithOAuthCode({
    required String provider,
    required String authorizationCode,
    required String redirectUri,
    required String state,
  });
  Future<void> loginWithOAuthIdToken({
    required String provider,
    required String idToken,
  });
  Future<void> linkOAuthAccount({
    required String linkToken,
    required String password,
  });
  Future<List<String>> linkSocialAccount({
    required String provider,
    String? accessToken,
    String? idToken,
  });
  Future<List<String>> unlinkSocialAccount(String provider);
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
  Future<void> loginWithOAuthCode({
    required String provider,
    required String authorizationCode,
    required String redirectUri,
    required String state,
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
  Future<void> linkOAuthAccount({
    required String linkToken,
    required String password,
  }) async {
    await Future<void>.delayed(const Duration(milliseconds: 100));
    if (linkToken.isEmpty || password != 'password123') {
      throw const AuthException(AuthErrorCode.invalidCredentials);
    }
  }

  @override
  Future<List<String>> linkSocialAccount({
    required String provider,
    String? accessToken,
    String? idToken,
  }) async => [provider];

  @override
  Future<List<String>> unlinkSocialAccount(String provider) async => const [];

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
