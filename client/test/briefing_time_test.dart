import 'package:ddubi/domain/models.dart';
import 'package:flutter_test/flutter_test.dart';

/// 아침 브리핑 시각 계약.
///
/// 서버는 briefing_time 이 비어 있으면 "브리핑을 받지 않겠다" 는 뜻으로 보고 대상에서
/// 뺀다. 앱에 정하는 곳이 없어 지금까지 모든 사용자가 비어 있었다(2026-09-21 확인).
void main() {
  UserProfile parse(Map<String, dynamic> extra) => UserProfile.fromJson({
    'id': 1,
    'nickname': '뚜비',
    'notifyLeadMin': 20,
    'alarmEnabled': true,
    ...extra,
  });

  test('초가 붙어 와도 HH:mm 으로 읽는다', () {
    expect(parse({'briefingTime': '07:00:00'}).briefingTime, '07:00');
    expect(parse({'briefingTime': '18:30'}).briefingTime, '18:30');
  });

  test('값이 없으면 지어내지 않고 null 로 둔다', () {
    expect(parse({}).briefingTime, isNull);
    expect(parse({'briefingTime': null}).briefingTime, isNull);
  });
}
