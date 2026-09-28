import '../services/recommendation_api.dart';

/// 지도에 그릴 구간 선. 저장된 좌표가 없는 구간은 앞뒤 구간을 잇는 직선으로 채운다.
///
/// <h3>왜 채우나</h3>
/// 도보 구간은 좌표가 비어 있을 때가 있다. 경로 API 가 도보 좌표를 주지 않거나, 도보 API
/// 호출이 막혀(호출 제한 등) 실제 걷는 길을 못 받은 경우다. 그대로 두면 지도에서 버스·지하철
/// 선만 뜨고 그 사이가 끊겨, 사용자는 "경로가 잘못 나왔다"고 읽는다.
///
/// 채운 선은 <b>실제 걷는 길이 아니라 두 지점을 잇는 직선</b>이다. 그래서 도보는 항상 점선으로
/// 그린다 — 점선은 "대략 이쪽" 이라는 뜻이고, 실선은 실제 경로라는 뜻이다. 도보 API 가 좌표를
/// 주기 시작하면 그 좌표가 그대로 들어와 같은 자리에 진짜 길이 그려진다.
class RouteMapLine {
  const RouteMapLine({
    required this.seq,
    required this.mode,
    required this.points,
    required this.approximate,
  });

  final int seq;
  final String mode;

  /// 그릴 좌표. 두 점 미만이면 선을 만들 수 없어 목록에서 빠진다.
  final List<RouteCoordinate> points;

  /// 앞뒤 구간을 이어 만든 직선인가. 저장된 좌표를 그대로 쓴 선은 false 다.
  final bool approximate;
}

/// 저장된 경로 지도를 "그릴 수 있는 선"으로 바꾼다. 원본 좌표는 바꾸지 않는다.
List<RouteMapLine> routeMapLines(SavedRouteMap map) {
  final segments = map.segments;
  final lines = <RouteMapLine>[];
  for (var i = 0; i < segments.length; i++) {
    final segment = segments[i];
    if (segment.coordinates.length >= 2) {
      lines.add(
        RouteMapLine(
          seq: segment.seq,
          mode: segment.mode,
          points: segment.coordinates,
          approximate: false,
        ),
      );
      continue;
    }
    // 좌표가 없거나 한 점뿐이면 앞뒤에서 가장 가까운 실제 좌표를 찾아 잇는다.
    // 이웃 구간의 끝점이 실제 승·하차 지점이므로, 이 구간이 가진 한 점보다 이웃을 먼저 쓴다.
    final own = segment.coordinates.isEmpty ? null : segment.coordinates.first;
    final start = _lastBefore(segments, i) ?? own;
    final end = _firstAfter(segments, i) ?? own;
    if (start == null || end == null) continue;
    if (start.latitude == end.latitude && start.longitude == end.longitude) {
      continue;
    }
    lines.add(
      RouteMapLine(
        seq: segment.seq,
        mode: segment.mode,
        points: [start, end],
        approximate: true,
      ),
    );
  }
  return List.unmodifiable(lines);
}

RouteCoordinate? _lastBefore(List<RouteMapSegment> segments, int index) {
  for (var i = index - 1; i >= 0; i--) {
    if (segments[i].coordinates.isNotEmpty) return segments[i].coordinates.last;
  }
  return null;
}

RouteCoordinate? _firstAfter(List<RouteMapSegment> segments, int index) {
  for (var i = index + 1; i < segments.length; i++) {
    if (segments[i].coordinates.isNotEmpty) {
      return segments[i].coordinates.first;
    }
  }
  return null;
}
