import 'package:dio/dio.dart';

import '../../domain/auth.dart';
import '../constants/api_paths.dart';

/// Coalesces refreshes; late responses cannot resurrect a cleared session.
class SessionRefresher {
  SessionRefresher(this.store, this.client);
  final AuthSessionStore store;
  final Dio client;
  Future<void>? _pending;
  int? _pendingRevision;

  Future<void> refresh() {
    final revision = store.revision;
    if (_pending != null && _pendingRevision == revision) return _pending!;
    _pendingRevision = revision;
    final operation = _refresh(revision);
    _pending = operation;
    return operation.whenComplete(() {
      if (identical(_pending, operation)) {
        _pending = null;
        _pendingRevision = null;
      }
    });
  }

  Future<void> _refresh(int revision) async {
    final session = store.session;
    if (session == null) return;
    try {
      final response = await client.post<Map<String, dynamic>>(
        ApiPaths.refresh,
        data: {
          'refreshToken': session.refreshToken,
          'deviceId': session.deviceId,
        },
      );
      final data = response.data?['data'];
      if (response.data?['success'] != true || data is! Map<String, dynamic>) {
        throw const FormatException('토큰 갱신 응답을 확인할 수 없습니다.');
      }
      final refreshed = session.withRefreshedTokens(data);
      if (refreshed.accessToken.isEmpty || refreshed.refreshToken.isEmpty) {
        throw const FormatException('비어 있는 토큰');
      }
      if (store.revision == revision) await store.save(refreshed);
    } on DioException catch (error) {
      // Timeouts and 5xx do not prove that the refresh token was revoked.
      if (store.revision == revision &&
          (error.response?.statusCode == 401 ||
              error.response?.statusCode == 403)) {
        await store.clear();
      }
      rethrow;
    }
  }
}
