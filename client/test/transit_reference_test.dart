import 'package:ddubi/domain/transit_reference.dart';
import 'package:ddubi/presentation/transit_reference_panel.dart';
import 'package:ddubi/services/transit_reference_api.dart';
import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

const payload = <String, dynamic>{
  'busReference': {
    'status': 'STAGED_REFERENCE_CONFIGURED',
    'productionReady': false,
    'realtimeApplied': false,
    'limitations': ['WEATHER_NOT_JOINED'],
  },
  'subwayReference': {
    'status': 'STAGED_REFERENCE_AVAILABLE',
    'sampleReferenceIds': ['verified-reference-1'],
    'counts': {'patterns': 568},
    'limitations': ['CURRENT_TIMETABLE_VALIDITY_UNVERIFIED'],
  },
  'roadReference': {'status': 'REFERENCE_PACKAGE_INVALID'},
};

const detailPayload = <String, dynamic>{
  'status': 'STAGED_REFERENCE_NOT_APPROVED',
  'referenceId': 'verified-reference-1',
  'sourceVersion': 'reference-v1',
  'referenceOnly': true,
  'productionReady': false,
  'realtimeApplied': false,
  'detail': {
    'referenceId': 'verified-reference-1',
    'kind': 'SUBWAY',
    'recordType': 'SUBWAY_SEGMENT',
    'label': '원천 구간 예시',
    'data': {'scheduledMeanSeconds': 123.5},
    'limitations': ['NOT_REALTIME_OR_OBSERVED_DATA'],
  },
};

class FakeRepository implements TransitReferenceRepository {
  int calls = 0;
  bool fail = false;
  final detailRequests = <String>[];
  @override
  Future<TransitReferenceDetail> transitReferenceDetail(
    String kind,
    String referenceId,
  ) async {
    detailRequests.add('$kind/$referenceId');
    return TransitReferenceDetail.fromJson(detailPayload, referenceId);
  }

  @override
  Future<TransitReferenceStatus> transitReferenceStatus() async {
    calls++;
    if (fail) throw const TransitReferenceFailure('연결을 확인해 주세요.');
    return TransitReferenceStatus.fromJson(payload);
  }
}

Widget app(TransitReferenceRepository? repository) => MaterialApp(
  home: Scaffold(
    body: SingleChildScrollView(
      child: TransitReferencePanel(repository: repository),
    ),
  ),
);

void main() {
  test('detail contract rejects mismatched IDs and production promotion', () {
    expect(
      () => TransitReferenceDetail.fromJson(detailPayload, 'invented'),
      throwsA(isA<TransitReferenceFailure>()),
    );
    expect(
      () => TransitReferenceDetail.fromJson({
        ...detailPayload,
        'productionReady': true,
      }, 'verified-reference-1'),
      throwsA(isA<TransitReferenceFailure>()),
    );
  });

  test(
    'detail API requests only explicit supplied ID without internal key',
    () async {
      late RequestOptions captured;
      final dio = Dio()
        ..interceptors.add(
          InterceptorsWrapper(
            onRequest: (options, handler) {
              captured = options;
              handler.resolve(
                Response(
                  requestOptions: options,
                  data: {'success': true, 'data': detailPayload},
                ),
              );
            },
          ),
        );
      final detail = await TransitReferenceApi(dio)
          .fetchDetail('subway', 'verified-reference-1');
      expect(
        captured.path,
        '/routes/reference-packages/subway/verified-reference-1',
      );
      expect(detail.data['scheduledMeanSeconds'], 123.5);
      await expectLater(
        TransitReferenceApi(dio).fetchDetail('bus', 'invented'),
        throwsA(isA<TransitReferenceFailure>()),
      );
    },
  );
  test('missing and malformed component state remain unknown', () {
    final result = TransitReferenceStatus.fromJson({
      'checkedAt': 'not-a-date',
      'busReference': {'status': 4, 'limitations': 'not-a-list'},
    });
    expect(result.bus.status, 'UNKNOWN');
    expect(result.subway.status, 'UNKNOWN');
    expect(result.road.status, 'UNKNOWN');
    expect(result.checkedAt, isNull);
  });

  test(
    'status API reuses bearer client and never supplies internal keys',
    () async {
      late RequestOptions request;
      final dio =
          Dio(BaseOptions(headers: {'Authorization': 'Bearer user-session'}))
            ..interceptors.add(
              InterceptorsWrapper(
                onRequest: (options, handler) {
                  request = options;
                  handler.resolve(
                    Response(
                      requestOptions: options,
                      data: {'success': true, 'data': payload},
                    ),
                  );
                },
              ),
            );
      final result = await TransitReferenceApi(dio).fetchStatus();
      expect(request.path, '/routes/data-status');
      expect(request.method, 'GET');
      expect(request.headers['Authorization'], 'Bearer user-session');
      expect(
        request.headers.keys.where((x) => x.toLowerCase().contains('api-key')),
        isEmpty,
      );
      expect(request.queryParameters, isEmpty);
      expect(result.bus.status, 'STAGED_REFERENCE_CONFIGURED');
    },
  );

  for (final code in [401, 403, 404, 500]) {
    test('HTTP $code remains a failure, never an empty success', () async {
      final dio = Dio()
        ..interceptors.add(
          InterceptorsWrapper(
            onRequest: (options, handler) {
              handler.reject(
                DioException(
                  requestOptions: options,
                  response: Response(requestOptions: options, statusCode: code),
                ),
              );
            },
          ),
        );
      await expectLater(
        TransitReferenceApi(dio).fetchStatus(),
        throwsA(isA<TransitReferenceFailure>()),
      );
    });
  }

  test('unsuccessful envelope is rejected', () async {
    final dio = Dio()
      ..interceptors.add(
        InterceptorsWrapper(
          onRequest: (options, handler) {
            handler.resolve(
              Response(
                requestOptions: options,
                data: {'success': false, 'data': payload},
              ),
            );
          },
        ),
      );
    await expectLater(
      TransitReferenceApi(dio).fetchStatus(),
      throwsA(isA<TransitReferenceFailure>()),
    );
  });

  testWidgets(
    'lazy global inventory never claims current route use or displays global counts',
    (tester) async {
      final repository = FakeRepository();
      await tester.pumpWidget(app(repository));
      expect(repository.calls, 0);
      await tester.tap(find.text('참고자료 준비 상태'));
      await tester.pumpAndSettle();
      expect(repository.calls, 1);
      expect(find.textContaining('현재 경로 적용 여부와 별개'), findsOneWidget);
      expect(find.textContaining('전체 적재와 경로 적용은 별도'), findsOneWidget);
      expect(find.textContaining('현재 운행 시간표와의 일치 여부'), findsOneWidget);
      expect(find.textContaining('검증에 실패'), findsOneWidget);
      expect(find.textContaining('568'), findsNothing);
      await tester.tap(find.text('참고자료 준비 상태'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('참고자료 준비 상태'));
      await tester.pumpAndSettle();
      expect(repository.calls, 1);
    },
  );

  testWidgets('refresh failure clears stale status without hiding error', (
    tester,
  ) async {
    final repository = FakeRepository();
    await tester.pumpWidget(app(repository));
    await tester.tap(find.text('참고자료 준비 상태'));
    await tester.pumpAndSettle();
    repository.fail = true;
    await tester.ensureVisible(
      find.byKey(const Key('refreshTransitReference')),
    );
    await tester.tap(find.byKey(const Key('refreshTransitReference')));
    await tester.pumpAndSettle();
    expect(find.text('연결을 확인해 주세요.'), findsOneWidget);
    expect(find.textContaining('버스 과거 기록:'), findsNothing);
  });

  testWidgets(
    'unconnected repository does not make requests or imply empty data',
    (tester) async {
      await tester.pumpWidget(app(null));
      await tester.tap(find.text('참고자료 준비 상태'));
      await tester.pumpAndSettle();
      expect(find.text('현재 화면은 서버 참고자료 조회에 연결되지 않았습니다.'), findsOneWidget);
      expect(find.byKey(const Key('refreshTransitReference')), findsNothing);
    },
  );

  testWidgets(
    'reference example uses server ID and states it is unrelated to current route',
    (tester) async {
      final repository = FakeRepository();
      await tester.pumpWidget(app(repository));
      await tester.tap(find.text('참고자료 준비 상태'));
      await tester.pumpAndSettle();
      expect(repository.detailRequests, isEmpty);
      final button = find.byKey(const ValueKey('reference-subway-0'));
      await tester.ensureVisible(button);
      await tester.tap(button);
      await tester.pumpAndSettle();
      expect(repository.detailRequests, ['subway/verified-reference-1']);
      expect(find.textContaining('현재 일정·경로와 연결된 자료가 아닙니다.'), findsOneWidget);
      expect(
        find.textContaining('123.5초 · 실제 관측 또는 현재 경로 예상시간이 아닙니다.'),
        findsOneWidget,
      );
      expect(find.text('자료 적용 기간: 현재 유효성 미확인'), findsOneWidget);
    },
  );
}
