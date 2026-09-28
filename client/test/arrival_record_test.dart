import 'package:ddubi/domain/auth.dart';
import 'package:ddubi/domain/models.dart';
import 'package:ddubi/presentation/arrival_flow.dart';
import 'package:ddubi/services/app_api.dart';
import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

/// 도착 보고가 서버에 무엇을 보내는지 고정한다.
///
/// 방어하려는 회귀: 앱이 일정 번호만 보내던 시절에는 서버가 실제 도착 시각을 받지 못해
/// 정시·빠듯·지연을 판정할 수 없었다. 그래서 모든 이동 기록이 "판정 없음" 으로 쌓이고
/// 리포트의 정시율이 늘 0% 로 보였다. 요청 본문에 시각과 동의가 함께 실리는지 확인한다.
Dio _dio(List<RequestOptions> requests) =>
    Dio(BaseOptions(baseUrl: 'https://example.test/api/v1'))
      ..interceptors.add(
        InterceptorsWrapper(
          onRequest: (options, handler) {
            requests.add(options);
            handler.resolve(
              Response<Map<String, dynamic>>(
                requestOptions: options,
                statusCode: 200,
                data: {
                  'success': true,
                  'data': options.path.endsWith('/arrival')
                      ? {
                          'scheduleId': 12,
                          'status': 'DONE',
                          'reportDetailId': 5,
                          'actualArrival': '2026-09-22T13:55:00',
                          'result': 'ON_TIME',
                          'plannedDepart': '2026-09-22T13:00:00',
                        }
                      : {
                          'id': 1,
                          'nickname': '뚜비',
                          'notifyLeadMin': 20,
                          'alarmEnabled': true,
                          'historyConsentedAt': '2026-09-22T13:55:00',
                        },
                },
              ),
            );
          },
        ),
      );

/// 확인 창을 띄우고 버튼을 눌러 주는 껍데기 화면.
Future<void> _tapComplete(
  WidgetTester tester, {
  required ArrivalApi arrivalApi,
  required UserApi userApi,
  bool keepRecord = true,
}) async {
  ArrivalRecord? received;
  await tester.pumpWidget(
    MaterialApp(
      home: Scaffold(
        body: Builder(
          builder: (context) => TextButton(
            onPressed: () async {
              received = await recordArrivalWithConsent(
                context,
                scheduleId: '12',
                arrivalApi: arrivalApi,
                userApi: userApi,
              );
            },
            child: const Text('도착 완료'),
          ),
        ),
      ),
    ),
  );
  await tester.tap(find.text('도착 완료'));
  await tester.pumpAndSettle();
  if (!keepRecord) {
    await tester.tap(find.byKey(const Key('keepArrivalRecordCheckbox')));
    await tester.pumpAndSettle();
  }
  await tester.tap(find.text('완료'));
  await tester.pumpAndSettle();
  expect(received, isNotNull);
}

void main() {
  testWidgets('도착 완료는 도착 시각과 동의를 함께 보낸다', (tester) async {
    final requests = <RequestOptions>[];
    final dio = _dio(requests);
    await _tapComplete(
      tester,
      arrivalApi: ArrivalApi(dio),
      userApi: UserApi(dio, AuthSessionStore()),
    );

    final arrival = requests.firstWhere((r) => r.path.endsWith('/arrival'));
    final body = arrival.data! as Map<String, dynamic>;
    expect(body['locationConsent'], isTrue);
    expect(body['actualArrival'], isNotNull);
  });

  testWidgets('도착 시각 기록을 끄면 시각도 동의도 보내지 않는다', (tester) async {
    final requests = <RequestOptions>[];
    final dio = _dio(requests);
    await _tapComplete(
      tester,
      arrivalApi: ArrivalApi(dio),
      userApi: UserApi(dio, AuthSessionStore()),
      keepRecord: false,
    );

    final arrival = requests.firstWhere((r) => r.path.endsWith('/arrival'));
    final body = arrival.data! as Map<String, dynamic>;
    expect(body['locationConsent'], isFalse);
    expect(body['actualArrival'], isNull);
    // 동의를 켜 달라고 요청하지도 않는다.
    expect(requests.any((r) => r.method == 'PATCH'), isFalse);
  });

  test('판정이 없는 도착 응답은 result 를 비운 채로 읽는다', () {
    final record = ArrivalRecord.fromJson({
      'scheduleId': 12,
      'status': 'DONE',
      'reportDetailId': 5,
      'actualArrival': null,
      'result': null,
    });
    expect(record.result, isNull);
    expect(record.actualArrival, isNull);
  });
}
