import 'package:ddubi/presentation/schedule_evaluation_card.dart';
import 'package:ddubi/services/recommendation_api.dart';
import 'package:flutter_test/flutter_test.dart';

/// 2026-09-21 운영 응답에서 실제로 관찰한 출처·사유 이름을 고정한다.
///
/// 서버는 ML 을 TEST_APPLY 로 돌리고 있어 step 마다 ML_TEST_APPLIED 가 붙고, 실패하면
/// 과거 평균(BUS_HISTORICAL_REFERENCE)으로 내려간다. 이름을 하나라도 못 알아보면 과거
/// 평균 결과가 "최신 추천" 으로 잘못 표시된다.
void main() {
  RecommendationOverview overview(String source) =>
      RecommendationOverview(state: 'READY', source: source);

  test('과거 평균 출처는 모두 참고 추정으로 본다', () {
    expect(overview('HISTORICAL_REFERENCE').historical, isTrue);
    expect(overview('MIXED_HISTORICAL_REFERENCE').historical, isTrue);
    expect(overview('BUS_REFERENCE').historical, isTrue);
    // 운영에서 실제로 내려오는 이름. 예전에는 빠져 있어 "최신 추천" 으로 보였다.
    expect(overview('BUS_HISTORICAL_REFERENCE').historical, isTrue);
  });

  test('예측이 적용된 결과는 참고 추정이 아니다', () {
    expect(overview('ML_PREVIEW').historical, isFalse);
    expect(overview('API_ONLY').historical, isFalse);
  });

  test('ML 실패 사유를 실시간 부족과 구분해 말한다', () {
    expect(
      evaluationMissingInputLabel('step-1:ML_HISTORY_INSUFFICIENT'),
      '이 구간은 예측에 쓸 기록이 아직 적어요',
    );
    expect(
      evaluationMissingInputLabel('step-1:ML_BUDGET_EXHAUSTED'),
      '예측 횟수를 다 써서 과거 평균으로 계산했어요',
    );
  });
}
