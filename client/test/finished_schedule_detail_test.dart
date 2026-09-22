import 'package:ddubi/domain/models.dart';
import 'package:ddubi/presentation/widgets.dart';
import 'package:flutter_test/flutter_test.dart';

/// 끝난 일정의 상세 화면.
///
/// 이미 다녀왔거나 취소한 일정에 앞으로의 안내가 남아 있으면 아직 할 일이 있는 것처럼
/// 읽힌다. 실제로 이런 문구들이 남아 있었다(2026-09-22).
///
///   "추천 안내를 아직 불러오지 못했어요"
///   "오늘의 이동 변수" — 어제 다녀온 일정 옆의 오늘 날씨
///   "이 일정은 서버 예측에 연결되지 않았습니다" — 고장으로 읽힘
///
/// 화면 전체를 띄우려면 서버 통로 여럿이 필요해 위젯 검사로는 무겁다. 여기서는 숨김
/// 여부를 가르는 판단 하나를 고정한다. 이 값이 참인 동안에는 위 카드들이 그려지지 않는다.
void main() {
  group('끝난 일정 판정', () {
    test('완료와 취소만 끝난 일정이다', () {
      expect(ScheduleStatusBadge.shows(ScheduleStatus.done), isTrue);
      expect(ScheduleStatusBadge.shows(ScheduleStatus.canceled), isTrue);
    });

    test('진행 예정은 끝난 일정이 아니다 — 안내를 계속 보여준다', () {
      expect(ScheduleStatusBadge.shows(ScheduleStatus.upcoming), isFalse);
    });

    test('모르는 상태는 끝났다고 단정하지 않는다', () {
      // 서버가 새 상태를 추가했을 때 화면을 조용히 비우면 안 된다.
      expect(ScheduleStatusBadge.shows(ScheduleStatus.unknown), isFalse);
    });
  });
}
