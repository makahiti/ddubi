import 'dart:async';

import 'package:ddubi/domain/auth.dart';
import 'package:ddubi/core/network/session_refresh.dart';
import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';

class MemoryStorage implements SessionStorage {
  String? value;
  @override
  Future<String?> read() async => value;
  @override
  Future<void> write(String? next) async {
    value = next;
  }
}

const session = AuthSession(
  accessToken: 'old',
  refreshToken: 'refresh',
  tokenType: 'Bearer',
  deviceId: 'device',
  user: AuthUser(id: 1, provider: 'LOCAL', email: null, nickname: 'test'),
);

void main() {
  test('새 저장소 인스턴스에서 보안 저장 세션 복구', () async {
    final disk = MemoryStorage();
    await AuthSessionStore(storage: disk).save(session);
    final restarted = AuthSessionStore(storage: disk);
    await restarted.restore();
    expect(restarted.accessToken, 'old');
    expect(restarted.session?.user.id, 1);
  });
  test('유지 미선택은 메모리에서만 로그인하고 디스크를 삭제', () async {
    final disk = MemoryStorage();
    final store = AuthSessionStore(storage: disk);
    await store.save(session);
    store.keepSignedIn = false;
    await store.save(session);
    expect(store.accessToken, 'old');
    final restarted = AuthSessionStore(storage: disk);
    await restarted.restore();
    expect(restarted.session, isNull);
  });
  test('연속 저장 후 로그아웃은 영구 저장까지 삭제', () async {
    final disk = MemoryStorage();
    final store = AuthSessionStore(storage: disk);
    final saved = store.save(session);
    final cleared = store.clear();
    await Future.wait([saved, cleared]);
    expect(store.session, isNull);
    expect(disk.value, isNull);
  });
  test('손상된 저장값은 복구하지 않는다', () async {
    final disk = MemoryStorage()..value = '{broken';
    final store = AuthSessionStore(storage: disk);
    await store.restore();
    expect(store.session, isNull);
    expect(disk.value, isNull);
  });
  for (final status in [401, 403, 500, 503]) {
    test('갱신 응답 $status 에서 세션 삭제 범위', () async {
      final disk = MemoryStorage();
      final store = AuthSessionStore(storage: disk);
      await store.save(session);
      final dio = Dio()
        ..interceptors.add(
          InterceptorsWrapper(
            onRequest: (request, handler) {
              handler.reject(
                DioException(
                  requestOptions: request,
                  response: Response(
                    requestOptions: request,
                    statusCode: status,
                  ),
                ),
              );
            },
          ),
        );
      await expectLater(
        SessionRefresher(store, dio).refresh(),
        throwsA(isA<DioException>()),
      );
      expect(store.session == null, status == 401 || status == 403);
      expect(disk.value == null, status == 401 || status == 403);
    });
  }
  test('동시 갱신은 한 번만 전송하고 회전 토큰을 저장', () async {
    final disk = MemoryStorage();
    final store = AuthSessionStore(storage: disk);
    await store.save(session);
    var calls = 0;
    final release = Completer<void>();
    final dio = Dio()
      ..interceptors.add(
        InterceptorsWrapper(
          onRequest: (request, handler) async {
            calls++;
            await release.future;
            handler.resolve(
              Response(
                requestOptions: request,
                data: {
                  'success': true,
                  'data': {'accessToken': 'new', 'refreshToken': 'rotated'},
                },
              ),
            );
          },
        ),
      );
    final refresh = SessionRefresher(store, dio);
    final requests = List.generate(10, (_) => refresh.refresh());
    release.complete();
    await Future.wait(requests);
    expect(calls, 1);
    expect(store.accessToken, 'new');
    final restarted = AuthSessionStore(storage: disk);
    await restarted.restore();
    expect(restarted.session?.refreshToken, 'rotated');
  });
  test('로그아웃 뒤 늦게 도착한 갱신 응답은 무시', () async {
    final store = AuthSessionStore();
    await store.save(session);
    final release = Completer<void>();
    final dio = Dio()
      ..interceptors.add(
        InterceptorsWrapper(
          onRequest: (request, handler) async {
            await release.future;
            handler.resolve(
              Response(
                requestOptions: request,
                data: {
                  'success': true,
                  'data': {'accessToken': 'new', 'refreshToken': 'rotated'},
                },
              ),
            );
          },
        ),
      );
    final pending = SessionRefresher(store, dio).refresh();
    await store.clear();
    release.complete();
    await pending;
    expect(store.session, isNull);
  });
}
