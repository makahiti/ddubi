import 'package:ddubi/domain/saved_route_display.dart';
import 'package:ddubi/services/recommendation_api.dart';
import 'package:flutter_test/flutter_test.dart';

SavedRouteSummary route({
  required String id,
  required int rank,
  bool selected = false,
  String from = '서울역',
  String to = '대한사회복지회',
  List<String> modes = const ['WALK', 'SUBWAY', 'WALK', 'BUS'],
  int segments = 5,
  int minutes = 45,
}) => SavedRouteSummary(
  routeId: id,
  rankNo: rank,
  selected: selected,
  baseDurationMin: minutes,
  fromName: from,
  toName: to,
  segmentCount: segments,
  modes: modes,
);

/// 오늘 서버에 저장된 실제 모양을 본뜬 후보 33개.
/// 선택 1 + 같은 카드로 보이는 중복 19 + 사용자가 구분할 수 있는 경로 13.
List<SavedRouteSummary> fixture33() {
  final routes = <SavedRouteSummary>[
    route(id: 'sel', rank: 1, selected: true),
    for (var i = 0; i < 5; i++) route(id: 'dupA$i', rank: 2 + i),
    for (var i = 0; i < 8; i++)
      route(
        id: 'dupB$i',
        rank: 13,
        from: '서울역서부교차로',
        modes: const ['WALK', 'BUS', 'WALK', 'BUS'],
        segments: 4,
        minutes: 53,
      ),
    for (var i = 0; i < 6; i++)
      route(
        id: 'dupC$i',
        rank: 16,
        to: '역삼역',
        modes: const ['WALK', 'SUBWAY'],
        segments: 2,
        minutes: 57,
      ),
  ];
  // 사용자가 구분할 수 있는 경로: 승하차 지점·이동수단 흐름·구간 수·소요시간이 서로 다르다.
  for (var i = 0; i < 13; i++) {
    routes.add(
      route(
        id: 'uniq$i',
        rank: 3 + i,
        from: '정류장$i',
        to: '도착지$i',
        modes: i.isEven
            ? const ['WALK', 'BUS']
            : const ['WALK', 'SUBWAY', 'WALK'],
        segments: 2 + (i % 4),
        minutes: 40 + i,
      ),
    );
  }
  return routes;
}

void main() {
  test('후보 33개여도 화면에는 제일 추천하는 경로 하나만 남는다', () {
    final all = fixture33();
    final display = SavedRouteDisplay.from(all);

    expect(all, hasLength(33));
    expect(display.total, 33);
    expect(display.visible, hasLength(1));
    expect(display.visible.single.selected, isTrue);
    expect(display.visible.single.routeId, 'sel');
  });

  test('서버가 제안한 경로가 있으면 선택된 경로와 둘만 보여준다', () {
    final all = [
      ...fixture33(),
      route(id: 'proposed', rank: 20, from: '제안출발', to: '제안도착', minutes: 38),
    ];
    final display = SavedRouteDisplay.from(all, proposedRouteId: 'proposed');

    expect(display.visible, hasLength(2));
    expect(display.visible.first.routeId, 'sel');
    expect(display.visible.last.routeId, 'proposed');
  });

  test('제안된 경로는 중복 묶음에 속해도 접히지 않는다', () {
    // 선택된 경로와 카드 내용이 똑같이 보이는 후보가 제안으로 올라온 상황.
    final all = [
      route(id: 'sel', rank: 1, selected: true),
      route(id: 'same', rank: 2),
    ];
    final display = SavedRouteDisplay.from(all, proposedRouteId: 'same');

    expect(display.visible.map((r) => r.routeId), ['sel', 'same']);
  });

  test('구분할 수 없는 중복만 묶고 의미가 다른 경로는 진단에 남는다', () {
    final display = SavedRouteDisplay.from(fixture33());

    // 중복 묶음 3개(각각 1개만 남음) + 서로 다른 경로 13개 = 16개.
    expect(display.distinct, 16);
    // 묶은 결과는 진단에만 남고 화면에는 한 장만 나간다.
    expect(display.visible, hasLength(1));
  });

  test('선택 추천은 중복 묶음에 속해도 반드시 남는다', () {
    // 선택 추천과 같은 카드로 보이는 후보가 5개 더 있는 상황.
    final all = [
      route(id: 'sel', rank: 9, selected: true),
      for (var i = 0; i < 5; i++) route(id: 'same$i', rank: 1 + i),
    ];
    final display = SavedRouteDisplay.from(all);

    expect(display.visible.first.routeId, 'sel');
    expect(display.distinct, 1);
    expect(display.visible, hasLength(1));
  });

  test('후보가 적고 서로 달라도 화면에는 첫 번째 하나만 보여준다', () {
    final all = [
      route(id: 'a', rank: 1, selected: true),
      route(id: 'b', rank: 2, to: '다른곳', minutes: 50),
      route(id: 'c', rank: 3, modes: const ['WALK'], segments: 1, minutes: 20),
      route(id: 'd', rank: 4, from: '다른출발', minutes: 33),
    ];
    final display = SavedRouteDisplay.from(all);

    expect(display.visible.map((r) => r.routeId), ['a']);
    expect(display.distinct, 4);
  });

  test('서버 응답 원본 목록은 바뀌지 않고 정렬은 안정적이다', () {
    final all = fixture33();
    final before = all.map((r) => r.routeId).toList();

    final first = SavedRouteDisplay.from(all);
    final second = SavedRouteDisplay.from(all);

    expect(all.map((r) => r.routeId), before); // 원본 순서 유지
    expect(all, hasLength(33));
    expect(
      first.visible.map((r) => r.routeId),
      second.visible.map((r) => r.routeId),
    ); // 같은 입력이면 같은 결과
  });

  test('진단 문구는 전체·중복 제거 후·표시 수와 서버 순위를 담는다', () {
    final display = SavedRouteDisplay.from(fixture33());
    final ranks = display.visible.map((r) => r.rankNo).join(',');
    expect(
      display.diagnosticsLine,
      '전체 후보 33 · 중복 제거 후 16 · 표시 1 · 서버 순위 $ranks',
    );
  });
}
