import '../services/recommendation_api.dart';

/// 저장된 추천 경로를 사용자 화면에 보여줄 만큼만 추린 결과.
///
/// 서버는 진단·재평가를 위해 후보를 전부 저장하고 전부 내려준다(오늘 실측 33개).
/// 화면에는 제일 추천하는 경로 <b>하나만</b> 보여준다. 여러 개를 늘어놓으면 사용자가 무엇을
/// 골라야 하는지 알 수 없고, 후보끼리 도착 시각이 같은 경우가 많아 고를 근거도 없다.
///
/// 예외는 하나다. 서버가 지금 선택된 경로 대신 <b>다른 경로를 제안</b>하면 그 하나를 함께 둔다.
/// 바꾸라는 안내는 바꾸기 전과 후를 나란히 봐야 판단할 수 있다.
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

  /// 평소에 보여 주는 카드 수. 제안이 있을 때만 여기에 한 장이 더 붙는다.
  static const int maxVisible = 1;

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
  ///
  /// [proposedRouteId] 는 이번 평가에서 서버가 "이 경로로 바꾸라"고 제안한 경로다.
  /// 제안된 경로는 중복 묶기에서도, 표시 상한에서도 빠지지 않는다 — 접히면 사용자가
  /// 무엇으로 바꾸라는 것인지 볼 수 없고, 누를 카드도 사라진다.
  factory SavedRouteDisplay.from(
    List<SavedRouteSummary> routes, {
    String? proposedRouteId,
  }) {
    final ordered = [...routes]..sort(_compare);
    final seen = <String>{};
    final distinct = <SavedRouteSummary>[];
    for (final route in ordered) {
      // 선택된 추천이 맨 앞이므로 그 묶음 키가 먼저 등록된다. 뒤에 오는 같은 묶음은 접힌다.
      final fresh = seen.add(groupKey(route));
      final kept = route.selected || route.routeId == proposedRouteId;
      if (kept || fresh) distinct.add(route);
    }
    final visible = [...distinct.take(maxVisible)];
    if (proposedRouteId != null &&
        !visible.any((route) => route.routeId == proposedRouteId)) {
      for (final route in distinct) {
        if (route.routeId != proposedRouteId) continue;
        visible.add(route);
        break;
      }
    }
    return SavedRouteDisplay(
      visible: List.unmodifiable(visible),
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
