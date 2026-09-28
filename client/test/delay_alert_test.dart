import 'package:ddubi/data/mock_repository.dart';
import 'package:ddubi/domain/models.dart';
import 'package:ddubi/domain/schedule_evaluation.dart';
import 'package:ddubi/presentation/screens.dart';
import 'package:ddubi/providers/app_providers.dart';
import 'package:ddubi/services/recommendation_api.dart';
import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

/// 이동 변수 조회에 늘 실패하는 가짜 서버. 이 검사의 관심은 어떤 카드를 그리느냐이고,
/// 카드는 조회에 실패해도 제목과 네 칸("확인 안 됨")을 그린다.
RecommendationApi _failingConditions() => RecommendationApi(
  Dio(BaseOptions(baseUrl: 'https://example.test/api/v1'))
    ..interceptors.add(
      InterceptorsWrapper(
        onRequest: (options, handler) => handler.reject(
          DioException(
            requestOptions: options,
            response: Response<dynamic>(
              requestOptions: options,
              statusCode: 503,
            ),
            type: DioExceptionType.badResponse,
          ),
        ),
      ),
    ),
);

class _UpcomingAndDone extends _EmptySchedules {
  @override
  Future<List<Schedule>> schedules() async => [
    Schedule(
      id: 'next-1',
      title: '내일 회의',
      origin: const PlaceLocation(name: '출발', lat: 37.5, lng: 127),
      destination: const PlaceLocation(name: '도착', lat: 37.6, lng: 127.1),
      arriveBy: DateTime.now().add(const Duration(days: 1)),
    ),
    // 도착 시각은 아직 안 지났지만 이미 완료된 일정. 지금 상황을 붙일 자리가 아니다.
    Schedule(
      id: 'done-1',
      title: '끝난 일정',
      origin: const PlaceLocation(name: '출발', lat: 37.5, lng: 127),
      destination: const PlaceLocation(name: '도착', lat: 37.6, lng: 127.1),
      arriveBy: DateTime.now().add(const Duration(hours: 2)),
      status: ScheduleStatus.done,
    ),
  ];
}

class _EmptySchedules extends MockDdubiRepository
    implements ScheduleEvaluationRepository {
  @override
  Future<List<Schedule>> schedules() async => [];
  @override
  Future<ScheduleEvaluationV2> latestEvaluation(String id) async =>
      ScheduleEvaluationV2.fromLegacyJson({'state': 'PENDING'});
  @override
  Future<void> refreshEvaluation(String id) async {}
  @override
  Future<void> acceptEvaluation(String id, String evaluationId) async {}
  @override
  Future<void> confirmDeparture(String id) async {}
}

class _PastSchedule extends _EmptySchedules {
  @override
  Future<List<Schedule>> schedules() async => [
    Schedule(
      id: 'past-1',
      title: '지난 약속',
      origin: const PlaceLocation(name: '출발', lat: 37.5, lng: 127),
      destination: const PlaceLocation(name: '도착', lat: 37.6, lng: 127.1),
      arriveBy: DateTime.now().subtract(const Duration(days: 1)),
    ),
  ];
}

void main() {
  testWidgets('실제 일정이 없으면 가상 지연이나 대체 노선을 표시하지 않는다', (tester) async {
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          scheduleRepositoryProvider.overrideWithValue(_EmptySchedules()),
        ],
        child: MaterialApp(
          home: DelayAlertScreen(repository: MockDdubiRepository()),
        ),
      ),
    );
    await tester.pumpAndSettle();
    // 지난 일정만 남았을 때도 같은 문구가 맞도록 "앞으로 예정된" 으로 말한다.
    expect(find.text('앞으로 예정된 일정이 없습니다.'), findsOneWidget);
    expect(find.textContaining('8분'), findsNothing);
  });

  testWidgets('이미 지난 일정은 평가 카드를 만들지 않는다', (tester) async {
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          scheduleRepositoryProvider.overrideWithValue(_PastSchedule()),
        ],
        child: MaterialApp(
          home: DelayAlertScreen(repository: MockDdubiRepository()),
        ),
      ),
    );
    await tester.pumpAndSettle();
    expect(find.text('앞으로 예정된 일정이 없습니다.'), findsOneWidget);
    expect(find.text('지난 약속'), findsNothing);
  });

  /// 예전에는 일정 상세의 "이동 예측" 카드를 그대로 넣어 이 화면만의 쓸모가 없었다(2026-09-22 제보).
  /// 이제 일정마다 "이 일정의 이동 변수" 칸을 세운다. 완료된 일정은 도착 시각이 남았어도 빼는데,
  /// 지금 상황을 다녀온 일정 옆에 붙이면 그때의 상황으로 오해하기 때문이다.
  testWidgets('앞으로 갈 일정마다 그 일정의 이동 변수 칸을 세우고, 끝난 일정은 뺀다', (tester) async {
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          scheduleRepositoryProvider.overrideWithValue(_UpcomingAndDone()),
          recommendationApiProvider.overrideWithValue(_failingConditions()),
        ],
        child: MaterialApp(
          home: DelayAlertScreen(repository: MockDdubiRepository()),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('내일 회의'), findsOneWidget);
    expect(find.text('이 일정의 이동 변수'), findsOneWidget);
    expect(find.byKey(const Key('travelCondition_날씨')), findsOneWidget);
    expect(find.byKey(const Key('travelCondition_도로 통제')), findsOneWidget);
    // 끝난 일정은 이름조차 나오지 않는다.
    expect(find.text('끝난 일정'), findsNothing);
    // 이동 예측 카드의 흔적이 없어야 한다.
    expect(find.text('다시 계산'), findsNothing);
  });
}
