import 'package:ddubi/domain/models.dart';
import 'package:flutter_test/flutter_test.dart';

/// 반복 일정 출발 예정시각(departTime) 계약을 고정한다.
///
/// 서버는 초 없는 "09:00" 을 그대로 받는다(@JsonFormat 미적용, 2026-09-21 확인).
/// 값이 없는 구버전 규칙에는 서버가 아무 값도 채우지 않으므로, 앱이 지어내면 안 된다.
void main() {
  RepeatScheduleRule rule({String? departTime}) => RepeatScheduleRule(
    id: '1',
    title: '출근',
    origin: const PlaceLocation(name: '집', lat: 37.5, lng: 127.0),
    destination: const PlaceLocation(name: '회사', lat: 37.6, lng: 127.1),
    time: '09:00',
    departTime: departTime,
    startDate: DateTime(2026, 9, 21),
    weekdays: const {1, 2, 3, 4, 5},
  );

  test('출발시각을 초 없이 그대로 보낸다', () {
    final json = rule(departTime: '08:10').toCreateRequestJson();
    expect(json['departTime'], '08:10');
    expect(json['localTime'], '09:00');
  });

  test('출발시각이 없으면 값을 지어내지 않고 아예 보내지 않는다', () {
    final json = rule().toCreateRequestJson();
    expect(json.containsKey('departTime'), isFalse);
  });

  test('응답의 departTime 을 읽고, 없으면 보완이 필요하다고 본다', () {
    final withTime = RepeatScheduleRule.fromJson({
      'id': 7,
      'title': '출근',
      'origin': {'name': '집', 'lat': 37.5, 'lng': 127.0},
      'destination': {'name': '회사', 'lat': 37.6, 'lng': 127.1},
      'localTime': '09:00:00',
      'departTime': '08:10:00',
      'startDate': '2026-09-21',
      'rrule': 'FREQ=WEEKLY;BYDAY=MO,TU',
    });
    expect(withTime.departTime, '08:10');
    expect(withTime.needsDepartTime, isFalse);

    final legacy = RepeatScheduleRule.fromJson({
      'id': 8,
      'title': '수업',
      'origin': {'name': '집', 'lat': 37.5, 'lng': 127.0},
      'destination': {'name': '학교', 'lat': 37.6, 'lng': 127.1},
      'localTime': '10:00:00',
      'startDate': '2026-09-21',
      'rrule': 'FREQ=WEEKLY;BYDAY=WE',
    });
    expect(legacy.departTime, isNull);
    expect(legacy.needsDepartTime, isTrue);
  });
}
