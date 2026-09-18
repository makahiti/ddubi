import 'package:flutter_secure_storage/flutter_secure_storage.dart';

import '../domain/auth.dart';

class SecureSessionStorage implements SessionStorage {
  const SecureSessionStorage();
  static const _storage = FlutterSecureStorage();
  static const _key = 'ddubi.auth.session.v1';
  Future<bool> hasLaunched() async =>
      await _storage.read(key: 'ddubi.launched') == 'true';
  Future<void> markLaunched() =>
      _storage.write(key: 'ddubi.launched', value: 'true');
  @override
  Future<String?> read() => _storage.read(key: _key);
  @override
  Future<void> write(String? value) => value == null
      ? _storage.delete(key: _key)
      : _storage.write(key: _key, value: value);
}
