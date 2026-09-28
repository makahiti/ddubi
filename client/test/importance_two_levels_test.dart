import 'package:ddubi/domain/models.dart';
import 'package:flutter_test/flutter_test.dart';

/// 중요도는 화면에서 두 단계다(2026-09-21 결정: 일반 / 중요).
///
/// 서버는 CRITICAL 을 더 이상 내려주지 않지만, 예전 앱이 보낸 값이나 남은 캐시를
/// 읽다 깨지지 않게 파싱은 계속 받아 준다.
void main() {
  test('선택지는 일반과 중요 두 개뿐이다', () {
    expect(ScheduleImportance.choices, [
      ScheduleImportance.normal,
      ScheduleImportance.high,
    ]);
    expect(ScheduleImportance.choices.map((c) => c.label), ['일반', '중요']);
  });

  test('예전 CRITICAL 값도 읽히고 화면에는 중요로 보인다', () {
    expect(ScheduleImportance.fromApi('CRITICAL'), ScheduleImportance.critical);
    expect(ScheduleImportance.critical.label, '중요');
  });

  test('고르지 않은 일정의 기본값은 일반이다', () {
    final schedule = Schedule(
      id: '1',
      title: '회의',
      origin: const PlaceLocation(name: '집', lat: 37.5, lng: 127.0),
      destination: const PlaceLocation(name: '회사', lat: 37.6, lng: 127.1),
      arriveBy: DateTime(2026, 9, 22, 9),
    );
    expect(schedule.importance, ScheduleImportance.normal);
    expect(schedule.toCreateRequestJson()['importance'], 'NORMAL');
  });
}
