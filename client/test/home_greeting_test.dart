import 'package:ddubi/presentation/home_greeting.dart';
import 'package:flutter_test/flutter_test.dart';

/// 홈 인사는 시간대에 따라 말과 색이 다르다(2026-09-22 제보). 경계 시각을 고정한다.
void main() {
  HomeGreeting at(int hour) => homeGreeting(DateTime(2026, 9, 22, hour, 30));

  test('새벽(0~5시)은 막 일어난 사람에게 말한다', () {
    expect(at(0).period, 'dawn');
    expect(at(5).period, 'dawn');
    expect(at(5).message, contains('시작'));
  });

  test('오전(6~11시)은 지금까지 쓰던 인사 그대로다', () {
    expect(at(6).period, 'morning');
    expect(at(11).message, '오늘도\n뚜비와 함께\n좋은 하루 되세요!');
  });

  test('오후(12~17시)는 남은 하루를 말한다', () {
    expect(at(12).period, 'afternoon');
    expect(at(17).message, contains('남은 하루'));
  });

  test('밤(18~23시)은 하루를 마무리하고 내일을 말한다', () {
    expect(at(18).period, 'night');
    expect(at(23).message, contains('내일'));
  });

  test('네 시간대의 색은 서로 다르다 — 시계를 보는 앱처럼 보이려면 색도 달라야 한다', () {
    final colors = {
      for (final hour in [3, 9, 15, 21])
        homeGreeting(DateTime(2026, 9, 22, hour)).colors.first,
    };
    expect(colors, hasLength(4));
  });

  test('말은 늘 세 줄이다 — 카드 높이가 고정이라 줄 수가 달라지면 그림과 어긋난다', () {
    for (final hour in [3, 9, 15, 21]) {
      expect(at(hour).message.split('\n'), hasLength(3), reason: '$hour시');
    }
  });
}
