import 'package:ddubi/domain/schedule_evaluation.dart';
import 'package:ddubi/presentation/schedule_evaluation_card.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

Map<String, dynamic> response() => {
  'schemaVersion': 'schedule-evaluation-v2',
  'state': 'READY',
  'decision': 'ADVANCE_DEPARTURE',
  'estimatedArrivalAt': DateTime.now()
      .add(const Duration(hours: 1))
      .toIso8601String(),
  'proposedDepartAt': DateTime.now()
      .add(const Duration(minutes: 10))
      .toIso8601String(),
  'validUntil': DateTime.now()
      .add(const Duration(minutes: 1))
      .toIso8601String(),
  'quality': <String, dynamic>{
    'preview': false,
    'productionEligible': true,
    'costCoverage': 'COMPLETE',
    'realtimeApplied': false,
  },
};

void main() {
  for (final blocker in [
    'preview',
    'productionEligible',
    'costCoverage',
    'SEARCH_INCOMPLETE',
    'DATA_INSUFFICIENT',
    'eta',
    'expired',
    'ttl',
  ]) {
    testWidgets('$blocker 결과에서 추천시각과 수락 차단', (tester) async {
      final json = response();
      final quality = json['quality'] as Map<String, dynamic>;
      switch (blocker) {
        case 'preview':
          quality['preview'] = true;
        case 'productionEligible':
          quality['productionEligible'] = false;
        case 'costCoverage':
          quality['costCoverage'] = 'PARTIAL';
        case 'eta':
          json.remove('estimatedArrivalAt');
        case 'ttl':
          json.remove('validUntil');
        case 'expired':
          json['validUntil'] = DateTime.now()
              .subtract(const Duration(seconds: 1))
              .toIso8601String();
        default:
          json['decision'] = blocker;
      }
      final evaluation = ScheduleEvaluationV2.fromJson(json);
      expect(evaluation.canAcceptAt(DateTime.now()), isFalse);
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: ScheduleEvaluationSummary(
              evaluation: evaluation,
              compact: true,
            ),
          ),
        ),
      );
      expect(find.textContaining('제안 출발'), findsNothing);
      expect(find.textContaining('예상 도착'), findsNothing);
      expect(find.textContaining('제안 시각에 출발하면'), findsNothing);
      expect(find.textContaining('일부 구간에 실시간 정보 반영'), findsNothing);
    });
  }
  testWidgets('검증용 결과는 제안 출발 대신 참고 출발로만 시각을 보여주고 적용은 막는다', (tester) async {
    final json = response();
    (json['quality'] as Map<String, dynamic>)['preview'] = true;
    final evaluation = ScheduleEvaluationV2.fromJson(json);
    expect(evaluation.canAcceptAt(DateTime.now()), isFalse);

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: ScheduleEvaluationSummary(
            evaluation: evaluation,
            compact: true,
          ),
        ),
      ),
    );

    // 숫자는 보이되 확정을 뜻하는 라벨·문장은 쓰지 않는다.
    expect(find.textContaining('참고 출발'), findsOneWidget);
    expect(find.textContaining('참고 도착'), findsOneWidget);
    expect(find.textContaining('제안 출발'), findsNothing);
    expect(find.textContaining('예상 도착'), findsNothing);
    expect(
      find.text('참고로 계산한 결과예요. 아직 확정 전이라 일정에 자동 적용은 안 해요.'),
      findsOneWidget,
    );
    expect(find.textContaining('제안 시각에 출발하면'), findsNothing);
  });

  testWidgets('50개 missing과 알 수 없는 내부 코드를 사용자 문장으로 집계', (tester) async {
    final json = response();
    (json['quality'] as Map)['missingInputs'] = [
      for (var i = 0; i < 25; i++) 'BUS:secret-$i:MISSING',
      for (var i = 0; i < 25; i++) 'SUB:secret-$i:MISSING',
      'PRIVATE_NEW_CODE',
    ];
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: ScheduleEvaluationSummary(
            evaluation: ScheduleEvaluationV2.fromJson(json),
            compact: true,
          ),
        ),
      ),
    );
    expect(find.textContaining('secret-'), findsNothing);
    expect(find.textContaining('PRIVATE_NEW_CODE'), findsNothing);
    expect(find.textContaining('일부 버스 구간의 실시간 정보가 부족해요'), findsOneWidget);
    expect(find.textContaining('일부 지하철 구간의 운행정보가 부족해요'), findsOneWidget);
  });
}
