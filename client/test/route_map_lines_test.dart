import 'package:ddubi/domain/route_map_lines.dart';
import 'package:ddubi/services/recommendation_api.dart';
import 'package:flutter_test/flutter_test.dart';

RouteCoordinate at(double lat, double lng) =>
    RouteCoordinate(latitude: lat, longitude: lng);

/// RouteCoordinate 는 값 비교를 하지 않는 응답 모델이라 좌표값으로 견준다.
List<List<double>> pairs(List<RouteCoordinate> points) =>
    points.map((p) => [p.latitude, p.longitude]).toList();

RouteMapSegment segment(
  int seq,
  String mode, {
  List<RouteCoordinate> coordinates = const [],
}) => RouteMapSegment(
  seq: seq,
  mode: mode,
  fromName: '출발',
  toName: '도착',
  coordinates: coordinates,
);

SavedRouteMap map(List<RouteMapSegment> segments) =>
    SavedRouteMap(routeId: '1', segments: segments);

void main() {
  test('도보 좌표가 있으면 그 좌표를 그대로 그린다', () {
    final walk = [at(37.50, 127.00), at(37.501, 127.001), at(37.502, 127.002)];
    final lines = routeMapLines(
      map([
        segment(1, 'WALK', coordinates: walk),
        segment(2, 'BUS', coordinates: [at(37.502, 127.002), at(37.51, 127.01)]),
      ]),
    );

    expect(lines, hasLength(2));
    expect(pairs(lines.first.points), pairs(walk));
    expect(lines.first.approximate, isFalse);
  });

  test('도보 좌표가 없으면 앞뒤 구간을 잇는 직선으로 채운다', () {
    final lines = routeMapLines(
      map([
        segment(1, 'BUS', coordinates: [at(37.50, 127.00), at(37.51, 127.01)]),
        segment(2, 'WALK'),
        segment(3, 'SUBWAY', coordinates: [at(37.52, 127.02), at(37.53, 127.03)]),
      ]),
    );

    expect(lines, hasLength(3));
    final walk = lines[1];
    expect(walk.mode, 'WALK');
    expect(walk.approximate, isTrue);
    // 앞 구간의 끝점에서 뒤 구간의 시작점까지 잇는다.
    expect(pairs(walk.points), [
      [37.51, 127.01],
      [37.52, 127.02],
    ]);
  });

  test('맨 앞·맨 뒤 도보는 이을 상대가 없으면 그리지 않는다', () {
    final lines = routeMapLines(
      map([
        segment(1, 'WALK'),
        segment(2, 'BUS', coordinates: [at(37.50, 127.00), at(37.51, 127.01)]),
        segment(3, 'WALK'),
      ]),
    );

    // 없는 좌표를 지어내지 않는다. 그릴 수 있는 선만 남는다.
    expect(lines, hasLength(1));
    expect(lines.single.mode, 'BUS');
  });

  test('좌표가 한 점뿐인 구간도 앞뒤를 이어 선으로 만든다', () {
    final lines = routeMapLines(
      map([
        segment(1, 'BUS', coordinates: [at(37.50, 127.00), at(37.51, 127.01)]),
        segment(2, 'WALK', coordinates: [at(37.515, 127.015)]),
        segment(3, 'BUS', coordinates: [at(37.52, 127.02), at(37.53, 127.03)]),
      ]),
    );

    expect(pairs(lines[1].points), [
      [37.51, 127.01],
      [37.52, 127.02],
    ]);
    expect(lines[1].approximate, isTrue);
  });

  test('앞뒤 끝점이 같은 자리면 선을 만들지 않는다', () {
    final lines = routeMapLines(
      map([
        segment(1, 'BUS', coordinates: [at(37.50, 127.00), at(37.51, 127.01)]),
        segment(2, 'WALK'),
        segment(3, 'BUS', coordinates: [at(37.51, 127.01), at(37.53, 127.03)]),
      ]),
    );

    expect(lines.map((line) => line.mode), ['BUS', 'BUS']);
  });
}
