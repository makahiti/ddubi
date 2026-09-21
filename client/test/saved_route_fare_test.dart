import 'package:ddubi/domain/models.dart';
import 'package:ddubi/services/recommendation_api.dart';
import 'package:flutter_test/flutter_test.dart';

/// 경로 목록의 요금 칸.
///
/// 지금은 서버가 요금을 보내지 않는다(route 테이블에 칸이 없다). 그동안 요약 카드는
/// 늘 "요금 정보 없음" 을 보여줬고, 바로 아래 안내 카드는 1,650원을 보여줬다. 한 화면에
/// 두 말이 같이 있었다(2026-09-22 확인).
///
/// 다른 경로의 요금을 끌어다 쓰면 "숫자가 섞이는" 문제를 되풀이하므로, 이 경로의 요금이
/// 올 때까지는 비워 둔다. 이 검사는 서버가 보내기 시작하면 앱이 바로 보여주는지를 지킨다.
void main() {
  Map<String, dynamic> base() => {
    'routeId': 1,
    'rankNo': 1,
    'selected': true,
    'baseDurationMin': 43,
    'segmentCount': 7,
    'modes': ['WALK', 'SUBWAY', 'WALK'],
  };

  final schedule = Schedule(
    id: 's1',
    title: '출근',
    origin: const PlaceLocation(name: '집', lat: 37.5, lng: 127.0),
    destination: const PlaceLocation(name: '회사', lat: 37.51, lng: 127.03),
    arriveBy: DateTime(2026, 9, 22, 9),
  );

  test('서버가 요금을 주면 그대로 보여준다', () {
    final route = SavedRouteSummary.fromJson({
      ...base(),
      'fare': {'minimumWon': 1650, 'maximumWon': 1650},
    });

    expect(route.minimumFareWon, 1650);
    expect(route.toRouteOption(schedule).fare, '1650원');
  });

  test('요금에 범위가 있으면 범위로 보여준다', () {
    final route = SavedRouteSummary.fromJson({
      ...base(),
      'fare': {'minimumWon': 1650, 'maximumWon': 2150},
    });

    expect(route.toRouteOption(schedule).fare, '1650~2150원');
  });

  test('서버가 요금을 안 주면 비워 둔다 — 다른 경로 값을 끌어오지 않는다', () {
    final route = SavedRouteSummary.fromJson(base());

    expect(route.minimumFareWon, isNull);
    expect(route.toRouteOption(schedule).fare, '요금 정보 없음');
  });

  test('평가 소요시간이 있으면 경로 API 원본 대신 그 값을 쓴다', () {
    // 같은 경로가 요약 카드에서는 43분, 추천 목록에서는 51분으로 보이던 문제를 막는다.
    final route = SavedRouteSummary.fromJson(base());

    expect(route.toRouteOption(schedule).detail, '약 43분');
    expect(route.toRouteOption(schedule, evaluatedMinutes: 51).detail, '약 51분');
  });
}
