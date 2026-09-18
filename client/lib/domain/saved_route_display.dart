import '../services/recommendation_api.dart';

/// 저장된 추천 경로를 사용자 화면에 보여줄 만큼만 추린 결과.
///
/// 서버는 진단·재평가를 위해 후보를 전부 저장하고 전부 내려준다(오늘 실측 33개).
/// 화면에는 사용자가 실제로 구분해 고를 수 있는 경로만, 선택된 추천을 포함해 최대 5개를 보여준다.
class SavedRouteDisplay {
  const SavedRouteDisplay({
    required this.visible,
    required this.total,
    required this.distinct,
  });

  /// 화면에 보여줄 목록. 선택된 추천이 항상 첫 번째다.
  final List<SavedRouteSummary> visible;

  /// 서버가 내려준 전체 후보 수.
  final int total;

  /// 중복을 묶은 뒤 남은 서로 다른 경로 수.
  final int distinct;

  /// 개발자 진단용 한 줄. 일반 사용자 화면에는 쓰지 않는다.
  /// 화면 순번은 1부터 다시 매기므로 서버가 준 순위를 여기에 남겨 원본과 대조할 수 있게 한다.
  String get diagnosticsLine =>
      '전체 후보 $total · 중복 제거 후 $distinct · 표시 ${visible.length}'
      ' · 서버 순위 ${visible.map((r) => r.rankNo).join(',')}';

  static const int maxVisible = 5;

  /// 사용자가 카드에서 구분할 수 있는 정보만으로 만든 동일성 기준.
  ///
  /// 카드에 보이는 것은 출발·도착 지점, 이동수단 흐름, 구간 수, 소요시간이다. 이 네 가지가
  /// 모두 같으면 화면에서는 완전히 같은 카드라 사용자가 고를 근거가 없다. 노선·환승·정류장이
  /// 다르면 이동수단 흐름이나 지점, 구간 수가 달라지므로 따로 남는다.
  /// 순위(rankNo)는 서버가 같은 값을 여러 후보에 주기도 하므로 기준에서 뺀다.
  static String groupKey(SavedRouteSummary route) => [
    route.fromName ?? '',
    route.toName ?? '',
    route.modes.join('>'),
    route.segmentCount,
    route.baseDurationMin,
  ].join('|');

  /// 원본 목록을 바꾸지 않고 표시용 목록을 만든다.
  factory SavedRouteDisplay.from(List<SavedRouteSummary> routes) {
    final ordered = [...routes]..sort(_compare);
    final seen = <String>{};
    final distinct = <SavedRouteSummary>[];
    for (final route in ordered) {
      // 선택된 추천이 맨 앞이므로 그 묶음 키가 먼저 등록된다. 뒤에 오는 같은 묶음은 접힌다.
      final fresh = seen.add(groupKey(route));
      if (route.selected || fresh) distinct.add(route);
    }
    return SavedRouteDisplay(
      visible: List.unmodifiable(distinct.take(maxVisible)),
      total: routes.length,
      distinct: distinct.length,
    );
  }

  /// 선택된 추천 우선, 다음은 서버 순위, 같은 순위면 도착이 빠른 순, 마지막은 경로 ID로 고정한다.
  /// 같은 입력이면 항상 같은 순서가 나오도록 마지막 기준까지 둔다.
  static int _compare(SavedRouteSummary a, SavedRouteSummary b) {
    if (a.selected != b.selected) return a.selected ? -1 : 1;
    final rankA = a.rankNo <= 0 ? 1 << 20 : a.rankNo;
    final rankB = b.rankNo <= 0 ? 1 << 20 : b.rankNo;
    if (rankA != rankB) return rankA.compareTo(rankB);
    if (a.baseDurationMin != b.baseDurationMin) {
      return a.baseDurationMin.compareTo(b.baseDurationMin);
    }
    return a.routeId.compareTo(b.routeId);
  }
}
