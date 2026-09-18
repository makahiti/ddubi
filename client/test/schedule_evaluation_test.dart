import 'dart:convert';
import 'dart:io';

import 'package:ddubi/domain/schedule_evaluation.dart';
import 'package:flutter_test/flutter_test.dart';

Future<ScheduleEvaluationV2> _fixture(String name) async {
  final raw = await File('test/fixtures/schedule_evaluation_$name.json')
      .readAsString();
  return ScheduleEvaluationV2.fromJson(jsonDecode(raw) as Map<String, dynamic>);
}

void main() {
  test('공통 fixture의 상태와 결정을 typed enum으로 파싱한다', () async {
    final cases = <String, DepartureDecision>{
      'keep': DepartureDecision.keepDeparture,
      'advance': DepartureDecision.advanceDeparture,
      'margin': DepartureDecision.marginUnavailable,
      'late': DepartureDecision.lateExpected,
      'data': DepartureDecision.dataInsufficient,
      'search': DepartureDecision.searchIncomplete,
    };

    for (final entry in cases.entries) {
      final evaluation = await _fixture(entry.key);
      expect(evaluation.state, EvaluationState.ready, reason: entry.key);
      expect(evaluation.decision, entry.value, reason: entry.key);
    }

    final running = await _fixture('running');
    expect(running.state, EvaluationState.running);
    expect(running.decision, DepartureDecision.unknown);

    final states = <String, EvaluationState>{
      'pending': EvaluationState.pending,
      'failed': EvaluationState.failed,
      'stopped': EvaluationState.stopped,
      'departure_required': EvaluationState.departureRequired,
      'stale': EvaluationState.stale,
    };
    for (final entry in states.entries) {
      expect((await _fixture(entry.key)).state, entry.value, reason: entry.key);
    }
  });

  test('알 수 없는 enum은 예외 없이 원문을 보존하고 수락을 차단한다', () async {
    final evaluation = await _fixture('unknown');

    expect(evaluation.state, EvaluationState.unknown);
    expect(evaluation.stateRaw, 'QUEUED_BY_NEW_SERVER');
    expect(evaluation.decision, DepartureDecision.unknown);
    expect(evaluation.decisionRaw, 'NEW_DECISION');
    expect(evaluation.legs.single.role, RouteLegRole.unknown);
    expect(evaluation.legs.single.roleRaw, 'NEW_WALK_ROLE');
    expect(evaluation.legs.single.durationSource, DurationSource.unknown);
    expect(evaluation.legs.single.durationSourceRaw, 'NEW_SOURCE');
    expect(evaluation.canAcceptAt(DateTime(2026, 9, 18)), isFalse);
  });

  test('상태 응답과 읽기 전용 응답의 누락 또는 null ID를 허용한다', () {
    final pending = ScheduleEvaluationV2.fromJson({
      'schemaVersion': 'schedule-evaluation-v2',
      'state': 'PENDING',
    });
    final readOnly = ScheduleEvaluationV2.fromJson({
      'schemaVersion': 'schedule-evaluation-v2',
      'evaluationId': 'synthetic-read-only',
      'scheduleId': null,
      'state': 'READY',
      'decision': 'ROUTE_UNAVAILABLE',
    });

    expect(pending.evaluationId, isNull);
    expect(pending.scheduleId, isNull);
    expect(pending.state, EvaluationState.pending);
    expect(readOnly.evaluationId, 'synthetic-read-only');
    expect(readOnly.scheduleId, isNull);
  });

  test('Z와 +09:00 시각 및 날짜 경계를 같은 절대시각으로 보존한다', () async {
    final evaluation = await _fixture('keep');

    expect(evaluation.plannedDepartAt, DateTime.utc(2026, 9, 17, 14, 50));
    expect(evaluation.arriveBy, DateTime.utc(2026, 9, 17, 15, 40));
    expect(evaluation.computedAt, DateTime.utc(2026, 9, 17, 14, 45));
  });

  test('nullable ETA, 만료, PREVIEW와 production gate를 구분한다', () async {
    final data = await _fixture('data');
    final keep = await _fixture('keep');

    expect(data.estimatedArrivalAt, isNull);
    expect(data.quality.preview, isTrue);
    expect(data.canAcceptAt(DateTime(2026, 9, 18)), isFalse);
    expect(keep.canAcceptAt(DateTime(2026, 9, 18)), isTrue);
    expect(keep.isExpiredAt(DateTime.utc(2100)), isTrue);
    expect(keep.canAcceptAt(DateTime.utc(2100)), isFalse);
    expect(
      (await _fixture('departed')).canAcceptAt(DateTime(2026, 9, 18)),
      isFalse,
    );
  });

  test('도보 역할, 계산 출처와 endpoint 검증 근거를 보존한다', () async {
    final evaluation = await _fixture('keep');

    expect(evaluation.legs.map((leg) => leg.role), [
      RouteLegRole.accessWalk,
      RouteLegRole.ride,
      RouteLegRole.transferWalk,
      RouteLegRole.egressWalk,
    ]);
    expect(
      evaluation.legs.first.durationSource,
      DurationSource.kakaoWalkingApi,
    );
    expect(evaluation.legs.first.endpointEvidence?.verified, isTrue);
    expect(evaluation.quality.walkingApiCoverage, 'BOTH');
    expect(evaluation.legs.first.durationSeconds, 305);
    expect(evaluation.durationSeconds, 2100);
  });

  test('endpoint 미검증 도보는 원본 경로 fallback 근거를 보존한다', () async {
    final evaluation = await _fixture('walking_fallback');
    final egress = evaluation.legs.last;

    expect(evaluation.quality.walkingApiCoverage, 'ACCESS_ONLY');
    expect(evaluation.quality.costCoverage, 'PARTIAL');
    expect(egress.role, RouteLegRole.egressWalk);
    expect(egress.durationSource, DurationSource.publicTransitStep);
    expect(egress.endpointEvidence?.verified, isFalse);
    expect(egress.endpointEvidence?.reason, 'ENDPOINT_UNVERIFIED');
  });

  test('합의된 BE 원인과 endpoint 근거 필드를 손실 없이 파싱한다', () async {
    final evaluation = await _fixture('be_contract');
    final cause = evaluation.causes.single;
    final evidence = evaluation.legs.single.endpointEvidence!;

    expect(cause.incidentKey, 'incident-42');
    expect(cause.revision, 3);
    expect(cause.source, 'OFFICIAL_NOTICE');
    expect(cause.officialNoticeAt, DateTime.utc(2026, 9, 17, 14, 20));
    expect(cause.affectedSegmentIds, ['segment-1', 'segment-2']);
    expect(cause.evidenceLevel, 'FULL');
    expect(cause.reasonCode, 'ROAD_CONTROL');
    expect(cause.code, 'ROAD_CONTROL');
    expect(cause.observedAt, DateTime.utc(2026, 9, 17, 14, 21));

    expect(evidence.mappingVersion, 'bus-master-20260917');
    expect(evidence.policyVersion, 'endpoint-policy-v2');
    expect(evidence.distanceMeters, 18);
    expect(evidence.judgment, 'MATCHED');
    expect(evidence.values['providerCoordinate'], {
      'latitude': 35.1,
      'longitude': 129.1,
    });
  });

  test('전환 중인 기존 BE 원인 필드도 합의 모델로 호환 파싱한다', () {
    final cause = EvaluationCause.fromJson({
      'eventId': 'legacy-event',
      'source': 'COLLECTOR',
      'segmentIds': ['segment-7'],
      'effect': 'POTENTIAL_IMPACT',
      'confirmed': false,
      'code': 'DELAY_SUSPECTED',
    });

    expect(cause.incidentKey, 'legacy-event');
    expect(cause.affectedSegmentIds, ['segment-7']);
    expect(cause.reasonCode, 'DELAY_SUSPECTED');
    expect(cause.effect, 'POTENTIAL_IMPACT');
    expect(cause.confirmed, isFalse);
  });

  test('구버전 응답은 검증용 비운영 결과로 안전하게 변환한다', () async {
    final evaluation = await _fixture('legacy');

    expect(evaluation.isLegacy, isTrue);
    expect(evaluation.state, EvaluationState.ready);
    expect(evaluation.quality.preview, isTrue);
    expect(evaluation.quality.productionEligible, isFalse);
    expect(evaluation.canAcceptAt(DateTime(2026, 9, 18)), isFalse);
  });
}
