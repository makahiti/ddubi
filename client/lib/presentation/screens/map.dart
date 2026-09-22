import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:kakao_maps_flutter/kakao_maps_flutter.dart';

import '../../core/maps/kakao_map_config.dart';
import '../../core/theme.dart';
import '../../domain/models.dart';
import '../../domain/route_evaluated_numbers.dart';
import '../../domain/route_map_lines.dart';
import '../../services/recommendation_api.dart';
import '../schedule_evaluation_card.dart' show durationSourceLabel;
import '../widgets.dart';
import '../../core/diagnostics/app_log.dart';
import 'journey.dart';
import 'shared.dart';

class RouteMapScreen extends StatefulWidget {
  const RouteMapScreen({
    super.key,
    required this.repository,
    this.api,
    this.savedRoute,
    this.selectedRoute,
    this.condition,
    this.departureLabel,
    this.destinationLabel,
    this.initialRouteDeviation = false,
    this.displayRank,
    this.evaluated = RouteEvaluatedNumbers.empty,
    this.scheduleId,
    this.departedAlready = false,
    this.estimatedArrivalAt,
  });

  final DdubiRepository repository;
  final RecommendationApi? api;
  final SavedRouteSummary? savedRoute;

  /// 이번 평가가 계산한 도착 예정. 이동 안내 화면의 "예상 도착" 에 그대로 넘긴다.
  final DateTime? estimatedArrivalAt;
  final RouteOption? selectedRoute;
  final RouteSearchCondition? condition;
  final String? departureLabel;
  final String? destinationLabel;
  final bool initialRouteDeviation;

  /// 목록에서 보이던 순번(1부터). 서버가 준 rankNo 와 다르다 — 서버 값은 소요시간 기준
  /// 캐시라 선택된 추천이 20 번일 수도 있다. 목록은 "1순위" 인데 상세가 "20순위" 로
  /// 보이던 문제를 막으려고 목록에서 본 번호를 그대로 받아 쓴다.
  final int? displayRank;

  /// 목록에서 쓴 것과 같은 평가 결과. 전체 시간과 구간별 시간·출처가 함께 들어 있다.
  /// 목록·상세·지도가 한 곳에서 온 같은 숫자를 보여주게 하려고 통째로 넘겨받는다.
  final RouteEvaluatedNumbers evaluated;

  /// 이 경로가 속한 일정. "출발했어요" 를 서버에 알릴 때 필요하다. 경로 검색처럼 일정과
  /// 이어지지 않은 화면에서는 없다.
  final String? scheduleId;

  /// 서버에 이미 출발이 기록된 일정인지. 이동 안내를 열자마자 "이동 중" 으로 시작한다.
  final bool departedAlready;

  @override
  State<RouteMapScreen> createState() => _RouteMapScreenState();
}

class _RouteMapScreenState extends State<RouteMapScreen> {
  late final Future<List<RouteOption>> _routes;
  late RouteKind _selectedKind;
  late bool _showDeviationBanner;

  @override
  void initState() {
    super.initState();
    _selectedKind = widget.selectedRoute?.kind ?? RouteKind.recommended;
    _showDeviationBanner = widget.initialRouteDeviation;
    _routes = widget.savedRoute != null
        ? Future.value(const [])
        : widget.selectedRoute != null
        ? Future.value([widget.selectedRoute!])
        : widget.repository.routes(condition: widget.condition).then((routes) {
            final primary = primaryRoute(routes);
            return primary == null ? <RouteOption>[] : [primary];
          });
  }

  @override
  Widget build(BuildContext context) =>
      widget.api != null && widget.savedRoute != null
      ? _SavedRouteApiScreen(
          api: widget.api!,
          summary: widget.savedRoute!,
          displayRank: widget.displayRank,
          evaluated: widget.evaluated,
          scheduleId: widget.scheduleId,
          departedAlready: widget.departedAlready,
          estimatedArrivalAt: widget.estimatedArrivalAt,
          route:
              widget.selectedRoute ??
              widget.savedRoute!.toRouteOption(
                Schedule(
                  id: '',
                  title: '',
                  origin: const PlaceLocation(name: '', lat: 0, lng: 0),
                  destination: const PlaceLocation(name: '', lat: 0, lng: 0),
                  arriveBy: DateTime.now().add(
                    Duration(
                      minutes:
                          widget.evaluated.totalMinutes ??
                          widget.savedRoute!.baseDurationMin,
                    ),
                  ),
                ),
              ),
          repository: widget.repository,
          departureLabel:
              widget.departureLabel ?? widget.savedRoute!.fromName ?? '출발지',
          destinationLabel:
              widget.destinationLabel ?? widget.savedRoute!.toName ?? '도착지',
        )
      : AppPage(
          title: '경로 상세',
          webTitle: '경로 지도',
          webSubtitle: '추천 경로와 이동 단계, 현재 위치를 한 화면에서 확인하세요.',
          child: FutureBuilder<List<RouteOption>>(
            future: _routes,
            builder: (context, snapshot) {
              if (snapshot.hasError) {
                return const DataLoadError(message: '지도 경로를 불러오지 못했어요.');
              }
              if (!snapshot.hasData) {
                return const Center(child: CircularProgressIndicator());
              }
              final routes = snapshot.data!;
              if (routes.isEmpty) {
                return const Center(child: Text('표시할 추천 경로가 없어요.'));
              }
              final selected = routes.firstWhere(
                (route) => route.kind == _selectedKind,
                orElse: () => routes.first,
              );
              return LayoutBuilder(
                builder: (context, constraints) {
                  final isWide = constraints.maxWidth >= 760;
                  final map = _RouteMapView(
                    routes: routes,
                    selectedKind: selected.kind,
                    departureLabel:
                        widget.departureLabel ??
                        widget.condition?.departurePlace ??
                        '출발지',
                    destinationLabel:
                        widget.destinationLabel ??
                        widget.condition?.destinationPlace ??
                        '도착지',
                    onSelected: (kind) => setState(() => _selectedKind = kind),
                    showRouteSelector: false,
                  );
                  final detail = _RouteMapDetail(
                    route: selected,
                    repository: widget.repository,
                    departureLabel:
                        widget.departureLabel ??
                        widget.condition?.departurePlace ??
                        '출발지',
                    destinationLabel:
                        widget.destinationLabel ??
                        widget.condition?.destinationPlace ??
                        '도착지',
                    scheduleId: widget.scheduleId,
                    departedAlready: widget.departedAlready,
                  );
                  if (isWide) {
                    final layout = Padding(
                      key: const Key('webRouteMapLayout'),
                      padding: const EdgeInsets.all(16),
                      child: Row(
                        children: [
                          Expanded(flex: 3, child: map),
                          const SizedBox(width: 16),
                          SizedBox(
                            width: 360,
                            child: SingleChildScrollView(child: detail),
                          ),
                        ],
                      ),
                    );
                    return Column(
                      children: [
                        if (_showDeviationBanner)
                          _RouteDeviationBanner(
                            onDismiss: () =>
                                setState(() => _showDeviationBanner = false),
                          ),
                        Expanded(child: layout),
                      ],
                    );
                  }
                  return Column(
                    key: const Key('mobileRouteMapLayout'),
                    children: [
                      if (_showDeviationBanner)
                        _RouteDeviationBanner(
                          onDismiss: () =>
                              setState(() => _showDeviationBanner = false),
                        ),
                      Expanded(flex: 6, child: map),
                      Flexible(
                        flex: 4,
                        child: SingleChildScrollView(
                          padding: const EdgeInsets.fromLTRB(12, 8, 12, 16),
                          child: detail,
                        ),
                      ),
                    ],
                  );
                },
              );
            },
          ),
        );
}

class _SavedRouteApiScreen extends StatefulWidget {
  const _SavedRouteApiScreen({
    required this.api,
    required this.summary,
    required this.route,
    required this.repository,
    required this.departureLabel,
    required this.destinationLabel,
    this.displayRank,
    this.evaluated = RouteEvaluatedNumbers.empty,
    this.scheduleId,
    this.departedAlready = false,
    this.estimatedArrivalAt,
  });

  final RecommendationApi api;
  final SavedRouteSummary summary;
  final RouteOption route;
  final DdubiRepository repository;
  final String departureLabel;
  final String destinationLabel;
  final int? displayRank;
  final RouteEvaluatedNumbers evaluated;
  final String? scheduleId;
  final bool departedAlready;
  final DateTime? estimatedArrivalAt;

  @override
  State<_SavedRouteApiScreen> createState() => _SavedRouteApiScreenState();
}

class _SavedRouteApiScreenState extends State<_SavedRouteApiScreen> {
  late Future<_SavedRouteViewData> _data = _load();

  Future<_SavedRouteViewData> _load() async {
    final results = await Future.wait<Object>([
      widget.api.getSavedRouteDetail(widget.summary.routeId),
      widget.api.getSavedRouteMap(widget.summary.routeId),
    ]);
    return _SavedRouteViewData(
      detail: results[0] as SavedRouteDetail,
      map: results[1] as SavedRouteMap,
    );
  }

  @override
  Widget build(BuildContext context) => AppPage(
    title: '경로 상세',
    child: FutureBuilder<_SavedRouteViewData>(
      future: _data,
      builder: (context, snapshot) {
        if (snapshot.hasError) {
          // 재계산 뒤 정리된 후보(404)는 오류가 아니다. 목록이 오래됐다고 알리고 돌아가게 한다.
          final gone = snapshot.error is SavedRouteGoneException;
          return Center(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                DataLoadError(
                  message: gone ? '이 경로는 새로 계산되면서 정리됐어요.' : '경로 상세를 불러오지 못했어요.',
                ),
                if (gone)
                  const Padding(
                    padding: EdgeInsets.fromLTRB(24, 0, 24, 8),
                    child: Text(
                      '일정 상세로 돌아가면 새 후보 목록을 볼 수 있어요.',
                      textAlign: TextAlign.center,
                      style: TextStyle(fontSize: 13, color: Color(0xFF52677B)),
                    ),
                  ),
                if (gone)
                  FilledButton.tonal(
                    key: const Key('savedRouteGoneBack'),
                    onPressed: () => Navigator.of(context).maybePop(true),
                    child: const Text('일정 상세로 돌아가기'),
                  )
                else
                  TextButton(
                    onPressed: () => setState(() => _data = _load()),
                    child: const Text('다시 시도'),
                  ),
              ],
            ),
          );
        }
        if (!snapshot.hasData) {
          return const Center(child: CircularProgressIndicator());
        }
        final data = snapshot.data!;
        return LayoutBuilder(
          builder: (context, constraints) {
            final map = _SavedRouteMapView(
              routeMap: data.map,
              departureLabel: widget.departureLabel,
              destinationLabel: widget.destinationLabel,
            );
            final detail = _SavedRouteDetailPanel(
              summary: widget.summary,
              detail: data.detail,
              route: widget.route,
              repository: widget.repository,
              displayRank: widget.displayRank,
              evaluated: widget.evaluated,
              scheduleId: widget.scheduleId,
              departedAlready: widget.departedAlready,
              estimatedArrivalAt: widget.estimatedArrivalAt,
            );
            if (constraints.maxWidth >= 760) {
              return Padding(
                key: const Key('webSavedRouteLayout'),
                padding: const EdgeInsets.all(16),
                child: Row(
                  children: [
                    Expanded(flex: 3, child: map),
                    const SizedBox(width: 16),
                    SizedBox(
                      width: 370,
                      child: SingleChildScrollView(child: detail),
                    ),
                  ],
                ),
              );
            }
            return Column(
              key: const Key('mobileSavedRouteLayout'),
              children: [
                Expanded(flex: 5, child: map),
                Flexible(
                  flex: 5,
                  child: SingleChildScrollView(
                    padding: const EdgeInsets.fromLTRB(12, 8, 12, 18),
                    child: detail,
                  ),
                ),
              ],
            );
          },
        );
      },
    ),
  );
}

class _SavedRouteViewData {
  const _SavedRouteViewData({required this.detail, required this.map});

  final SavedRouteDetail detail;
  final SavedRouteMap map;
}

class _SavedRouteDetailPanel extends StatelessWidget {
  const _SavedRouteDetailPanel({
    required this.summary,
    required this.detail,
    required this.route,
    required this.repository,
    this.displayRank,
    this.evaluated = RouteEvaluatedNumbers.empty,
    this.scheduleId,
    this.departedAlready = false,
    this.estimatedArrivalAt,
  });

  final SavedRouteSummary summary;
  final SavedRouteDetail detail;
  final RouteOption route;
  final DdubiRepository repository;

  /// 이번 평가가 계산한 도착 예정. 이동 안내 화면에 그대로 넘긴다.
  final DateTime? estimatedArrivalAt;

  /// 목록에서 보이던 순번. 목록과 상세가 같은 번호를 쓰게 하려고 그대로 받아 온다.
  /// 없으면 상세 응답의 displayRank 를 쓰고, 그것도 없으면 순위 칸을 그리지 않는다 —
  /// 내부 순위(rankNo)는 소요시간 기준 캐시라 사용자에게 보여줄 번호가 아니다.
  final int? displayRank;

  /// 화면에 보여줄 순위. 목록에서 온 값이 먼저다.
  int? get _visibleRank => displayRank ?? detail.displayRank;

  /// 목록에서 쓴 것과 같은 평가 결과. 없을 때만 경로 API 원본을 쓴다.
  final RouteEvaluatedNumbers evaluated;

  /// 이동 안내에서 출발을 알릴 일정.
  final String? scheduleId;

  /// 서버에 이미 출발이 기록됐는지.
  final bool departedAlready;

  /// 목록 카드와 같은 숫자를 보여준다.
  String get _durationLabel =>
      '약 ${evaluated.totalMinutes ?? detail.baseDurationMin}분';

  /// 이 숫자가 어디서 왔는지 한 줄. 사용자가 "왜 앱마다 시간이 다르지"를 묻지 않게 한다.
  String get _durationOrigin =>
      evaluated.totalMinutes == null ? '원본 경로 기준' : '이동 예측 기준';

  @override
  Widget build(BuildContext context) => InfoCard(
    key: const Key('savedRouteDetail'),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Row(
          children: [
            Expanded(
              child: _metric('예상 시간', _durationLabel, origin: _durationOrigin),
            ),
            if (_visibleRank != null)
              Expanded(child: _metric('경로 순위', '$_visibleRank순위')),
            Expanded(child: _metric('이동 구간', '${detail.segments.length}개')),
          ],
        ),
        const Divider(height: 28),
        if (detail.selected)
          const Align(
            alignment: Alignment.centerLeft,
            child: StatusBadge('선택된 경로', tone: DdubiColors.green),
          ),
        if (detail.selected) const SizedBox(height: 12),
        if (detail.segments.isEmpty)
          const Text('표시할 이동 구간이 없어요.')
        else
          for (final segment in detail.segments)
            _SavedRouteSegmentTile(segment: segment, evaluated: evaluated),
        const SizedBox(height: 12),
        PrimaryButton(
          departedAlready ? '이동 안내 이어보기' : '이동 안내 시작',
          onPressed: () => openPage(
            context,
            MovingScreen(
              repository: repository,
              route: route,
              segments: detail.segments,
              evaluated: evaluated,
              scheduleId: scheduleId,
              departedAlready: departedAlready,
              estimatedArrivalAt: estimatedArrivalAt,
            ),
          ),
        ),
      ],
    ),
  );

  Widget _metric(String label, String value, {String? origin}) => Column(
    children: [
      Text(
        label,
        style: const TextStyle(fontSize: 11, color: Color(0xFF687B8D)),
      ),
      const SizedBox(height: 3),
      Text(
        value,
        style: const TextStyle(
          fontSize: 15,
          fontWeight: FontWeight.w900,
          color: DdubiColors.navy,
        ),
      ),
      if (origin != null) ...[
        const SizedBox(height: 2),
        Text(
          origin,
          key: const Key('savedRouteDurationOrigin'),
          textAlign: TextAlign.center,
          style: const TextStyle(fontSize: 10, color: Color(0xFF7A8B9B)),
        ),
      ],
    ],
  );
}

class _SavedRouteSegmentTile extends StatelessWidget {
  const _SavedRouteSegmentTile({
    required this.segment,
    this.evaluated = RouteEvaluatedNumbers.empty,
  });

  final SavedRouteSegment segment;

  /// 이번 평가가 이 구간에 실제로 적용한 시간과 출처. 없으면 경로 API 원본을 쓴다.
  final RouteEvaluatedNumbers evaluated;

  /// 구간 한 줄에 쓰는 "약 N분 · 출처" 문구.
  ///
  /// 평가값과 원본을 섞지 않는다. 한 구간의 시간과 그 옆의 출처는 반드시 같은 곳에서 온다.
  String get _durationLine {
    final minutes = evaluated.minutesOf(segment.seq);
    if (minutes == null) return '약 ${segment.baseDurationMin}분 · 원본 경로 기준';
    final source = evaluated.sourceOf(segment.seq);
    return source == null
        ? '약 $minutes분'
        : '약 $minutes분 · ${durationSourceLabel(source)}';
  }

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.only(bottom: 10),
    child: Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Container(
          width: 36,
          height: 36,
          decoration: BoxDecoration(
            color: _modeColor(segment.mode).withValues(alpha: .12),
            shape: BoxShape.circle,
          ),
          child: Icon(
            _modeIcon(segment.mode),
            size: 20,
            color: _modeColor(segment.mode),
          ),
        ),
        const SizedBox(width: 10),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                '${segment.seq}. ${routeModeLabel(segment.mode)} '
                        '${segment.displayRouteName ?? ''}'
                    .trim(),
                style: const TextStyle(
                  fontWeight: FontWeight.w900,
                  color: DdubiColors.navy,
                ),
              ),
              const SizedBox(height: 3),
              Text(
                '${segment.fromName ?? '출발'} → ${segment.toName ?? '도착'}',
                style: const TextStyle(color: Color(0xFF52677B)),
              ),
              Text(
                _durationLine,
                style: const TextStyle(fontSize: 12, color: Color(0xFF7A8B9B)),
              ),
            ],
          ),
        ),
      ],
    ),
  );
}

IconData _modeIcon(String mode) => switch (mode) {
  'BUS' => Icons.directions_bus_rounded,
  'SUBWAY' => Icons.directions_subway_rounded,
  'TAXI' => Icons.local_taxi_rounded,
  _ => Icons.directions_walk_rounded,
};

Color _modeColor(String mode) => switch (mode) {
  'BUS' => const Color(0xFF2E70E8),
  'SUBWAY' => const Color(0xFF00A56F),
  'TAXI' => const Color(0xFFF3A91B),
  _ => const Color(0xFF55697D),
};

class _SavedRouteMapView extends StatefulWidget {
  const _SavedRouteMapView({
    required this.routeMap,
    required this.departureLabel,
    required this.destinationLabel,
  });

  final SavedRouteMap routeMap;
  final String departureLabel;
  final String destinationLabel;

  @override
  State<_SavedRouteMapView> createState() => _SavedRouteMapViewState();
}

class _SavedRouteMapViewState extends State<_SavedRouteMapView> {
  KakaoMapController? _controller;
  StreamSubscription<CameraMoveEndEvent>? _cameraMoveSubscription;

  /// 지도가 선을 직접 그렸는지. 못 그렸으면 예전 방식(지도 위에 얹어 그리기)으로 돌아간다.
  ///
  /// 지도가 그리는 쪽이 낫다 — 카메라를 따라가니 깜빡이지 않는다. 다만 기기에서 안 그려지는
  /// 경우가 있었다. 선이 아예 안 보이는 것보다는 깜빡이더라도 보이는 편이 낫다.
  bool _nativeLinesDrawn = false;
  List<_ProjectedRouteSegment> _projectedSegments = const [];
  var _projectionGeneration = 0;

  LatLng get _center {
    final bounds = widget.routeMap.bounds;
    if (bounds != null) {
      return LatLng(
        latitude: (bounds.minLat + bounds.maxLat) / 2,
        longitude: (bounds.minLng + bounds.maxLng) / 2,
      );
    }
    final coordinates = widget.routeMap.coordinates;
    if (coordinates.isNotEmpty) {
      return LatLng(
        latitude: coordinates.first.latitude,
        longitude: coordinates.first.longitude,
      );
    }
    return const LatLng(latitude: 37.5665, longitude: 126.9780);
  }

  Future<void> _onMapCreated(KakaoMapController controller) async {
    _controller = controller;
    final points = widget.routeMap.coordinates;
    try {
      if (!kIsWeb) {
        await controller.addMarkerLayer(
          layerId: KakaoMapController.defaultLabelLayerId,
          zOrder: 1000,
          clickable: false,
        );
      }
      if (points.isNotEmpty) {
        await controller.addMarkers(
          markerOptions: [
            MarkerOption(
              id: 'saved-route-departure',
              latLng: LatLng(
                latitude: points.first.latitude,
                longitude: points.first.longitude,
              ),
              text: '출발',
            ),
            MarkerOption(
              id: 'saved-route-destination',
              latLng: LatLng(
                latitude: points.last.latitude,
                longitude: points.last.longitude,
              ),
              text: '도착',
            ),
          ],
        );
      }
      // 선은 카메라와 상관없이 먼저 그린다.
      //
      // 예전에는 카메라 이동이 끝난 뒤에 그렸는데, moveCamera 를 기다리는 부분이
      // 끝나지 않아 그 뒤가 아예 실행되지 않았다. 지도 쪽 onCameraMoveEnd 는 제대로
      // 오는데도 그랬다(2026-09-22 기기 로그로 확인). 지도가 직접 그리는 선은 카메라가
      // 어디를 보고 있든 상관없으므로 기다릴 이유가 없다.
      await _drawRouteLines();
      unawaited(_showEntireRoute());
    } catch (error) {
      AppLog.debug('Saved route map setup failed: $error');
    }
  }

  Future<void> _showEntireRoute() async {
    final controller = _controller;
    if (controller == null) return;
    final bounds = widget.routeMap.bounds;
    final update = bounds == null
        ? CameraUpdate.fromLatLng(_center)
        : CameraUpdate.fromBounds(
            LatLngBounds(
              southwest: LatLng(
                latitude: bounds.minLat,
                longitude: bounds.minLng,
              ),
              northeast: LatLng(
                latitude: bounds.maxLat,
                longitude: bounds.maxLng,
              ),
            ),
            padding: 48,
          );
    try {
      await controller.moveCamera(
        cameraUpdate: update,
        animation: const CameraAnimation(
          duration: 450,
          autoElevation: true,
          isConsecutive: false,
        ),
      );
    } catch (error) {
      AppLog.debug('Saved route camera move failed: $error');
    }
  }

  /// 지도 좌표를 화면 좌표로 바꿔 경로선을 그린다.
  ///
  /// 지도가 돌려주는 값은 기기 물리 픽셀이고 캔버스는 논리 픽셀을 쓴다. 화면 배율로 나누지
  /// 않으면 선이 실제 위치에서 배율만큼 밀려 강 위 같은 엉뚱한 곳에 그려진다.
  /// 지도를 움직이는 동안 먼저 시작한 계산이 뒤늦게 반영되면 선이 끊겨 보이므로, 세대 번호로
  /// 가장 최근 계산만 반영한다.
  Future<void> _refreshProjectedSegments() async {
    final controller = _controller;
    if (controller == null || kIsWeb) return;
    final generation = ++_projectionGeneration;
    final pixelRatio = View.of(context).devicePixelRatio;
    final projected = <_ProjectedRouteSegment>[];
    try {
      // 좌표가 없는 구간(대개 도보)은 앞뒤를 잇는 직선으로 채워 선이 끊기지 않게 한다.
      for (final line in routeMapLines(widget.routeMap)) {
        final rawPoints = await Future.wait(
          line.points.map(
            (coordinate) => controller.toScreenPoint(
              position: LatLng(
                latitude: coordinate.latitude,
                longitude: coordinate.longitude,
              ),
            ),
          ),
        );
        final points = rawPoints
            .whereType<Offset>()
            .map((point) => point / pixelRatio)
            .toList(growable: false);
        projected.add(
          _ProjectedRouteSegment(
            mode: line.mode,
            points: points,
            approximate: line.approximate,
          ),
        );
      }
      if (mounted && generation == _projectionGeneration) {
        setState(() => _projectedSegments = projected);
      }
    } catch (error) {
      AppLog.debug('Saved route coordinate projection failed: $error');
    }
  }

  /// 지도를 만지는 순간 이전 화면 좌표는 더 이상 맞지 않는다. 지우고 이동이 끝나면 다시 그린다.
  void _hideProjectedRoute(PointerDownEvent _) {
    _projectionGeneration++;
    if (_projectedSegments.isNotEmpty) {
      setState(() => _projectedSegments = const []);
    }
  }

  /// 경로선을 지도에 그린다.
  ///
  /// 지도가 직접 그리므로 카메라가 어떻게 움직여도 선이 따라간다. 예전에는 좌표를 화면
  /// 위치로 바꿔 지도 위에 얹어 그렸는데, 지도를 만지는 순간 그 위치가 맞지 않아 선을
  /// 지웠다가 이동이 끝난 뒤 다시 그렸다. 사용자에게는 만질 때마다 깜빡이는 것으로
  /// 보였다(2026-09-22 제보).
  ///
  /// 선 그리기는 저장소 안 사본 패키지에 더한 기능이다.
  /// 사유: client/packages/kakao_maps_flutter/README.md
  Future<void> _drawRouteLines() async {
    final controller = _controller;
    if (controller == null || kIsWeb) return;
    try {
      await controller.clearRouteLines();
      var index = 0;
      var drawn = 0;
      // 좌표가 없는 구간(대개 도보)은 앞뒤를 잇는 직선으로 채워 선이 끊기지 않게 한다.
      for (final line in routeMapLines(widget.routeMap)) {
        if (line.points.length < 2) {
          index++;
          continue;
        }
        final color = _modeColor(line.mode);
        await controller.addRouteLine(
          id: 'segment-$index',
          points: line.points
              .map(
                (coordinate) => LatLng(
                  latitude: coordinate.latitude,
                  longitude: coordinate.longitude,
                ),
              )
              .toList(growable: false),
          // 좌표를 모르는 구간은 조금 연하게 그려 실제 경로와 구분한다. 추정임을 숨기지
          // 않되, 너무 연하면 지도 배경에 묻혀 아예 안 보인다(2026-09-22 기기 확인).
          color: line.approximate || line.mode == 'WALK'
              ? color.withValues(alpha: 0.8).toARGB32()
              : color.toARGB32(),
          // 굵기는 기기에서 보고 정했다. 7 은 지도 위에서 실처럼 보여 있는지도 몰랐다.
          width: line.mode == 'WALK' ? 12 : 16,
          // 흰 테두리를 둘러 밝은 지도 배경에서도 선이 떠 보이게 한다.
          strokeWidth: 3,
        );
        index++;
        drawn++;
      }
      AppLog.debug('Saved route lines drawn: $drawn');
      if (drawn > 0) {
        if (mounted) setState(() => _nativeLinesDrawn = true);
        return;
      }
      // 한 줄도 못 그렸다. 지도가 왜 거절했는지는 기기 로그로만 알 수 있다. 여기서는
      // 원인을 따지지 않고, 사용자에게 선은 보이도록 예전 방식으로 돌아간다.
      AppLog.debug('Saved route lines: falling back to projected overlay');
      await _enableProjectedFallback();
    } on Object catch (error) {
      // 선을 못 그려도 지도와 마커는 쓸 수 있다. 화면 전체를 실패로 만들지 않는다.
      AppLog.debug('Saved route line drawing failed: $error');
      await _enableProjectedFallback();
    }
  }

  /// 예전 방식으로 돌아간다. 카메라가 멈출 때마다 화면 좌표를 다시 계산해 그린다.
  Future<void> _enableProjectedFallback() async {
    final controller = _controller;
    if (controller == null || kIsWeb) return;
    _cameraMoveSubscription ??= controller.onCameraMoveEndStream.listen(
      (_) => _refreshProjectedSegments(),
    );
    await _refreshProjectedSegments();
  }

  @override
  void dispose() {
    _cameraMoveSubscription?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final hasCoordinates = widget.routeMap.coordinates.isNotEmpty;
    return ClipRRect(
      // 경로선은 이제 지도가 직접 그린다. 위젯 검사에서는 실제 지도가 뜨지 않으므로
      // 선 자체를 확인할 수 없다. 대신 지도 자리가 붙었는지를 이 키로 확인한다.
      key: const Key('savedRouteMap'),
      borderRadius: BorderRadius.circular(16),
      child: Stack(
        children: [
          Positioned.fill(
            // 지도가 직접 그릴 때는 손가락을 가로챌 이유가 없다. 대체 방식일 때만
            // 지도를 만지는 순간 선을 지운다 — 그때는 화면 좌표가 더 이상 맞지 않는다.
            child: Listener(
              onPointerDown: _nativeLinesDrawn ? null : _hideProjectedRoute,
              child: KakaoMapConfig.isReady
                  ? KakaoMap(
                      initialPosition: _center,
                      initialLevel: kIsWeb ? 5 : 13,
                      onMapCreated: _onMapCreated,
                    )
                  : const ColoredBox(color: Color(0xFFEAF2EC)),
            ),
          ),
          if (hasCoordinates && !_nativeLinesDrawn)
            Positioned.fill(
              child: IgnorePointer(
                child: CustomPaint(
                  key: const Key('savedRoutePolyline'),
                  painter: _SavedRoutePolylinePainter(
                    widget.routeMap,
                    projectedSegments: _projectedSegments,
                  ),
                ),
              ),
            )
          else if (!hasCoordinates)
            const Center(
              child: Material(
                color: Colors.white,
                borderRadius: BorderRadius.all(Radius.circular(14)),
                child: Padding(
                  padding: EdgeInsets.symmetric(horizontal: 16, vertical: 12),
                  child: Text('이 경로는 표시할 지도 좌표가 없어요.'),
                ),
              ),
            ),
          if (!KakaoMapConfig.isReady && hasCoordinates) ...[
            Positioned(
              left: 12,
              bottom: 14,
              child: _MapMarker(
                label: '출발 · ${widget.departureLabel}',
                icon: Icons.trip_origin,
                tone: DdubiColors.green,
              ),
            ),
            Positioned(
              right: 12,
              top: 14,
              child: _MapMarker(
                label: '도착 · ${widget.destinationLabel}',
                icon: Icons.location_on,
                tone: DdubiColors.red,
              ),
            ),
          ],
          Positioned(
            right: 12,
            bottom: 12,
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.end,
              children: [
                if (KakaoMapConfig.isReady && hasCoordinates) ...[
                  FloatingActionButton.small(
                    heroTag: 'saved-route-entire',
                    tooltip: '전체 경로 보기',
                    onPressed: _showEntireRoute,
                    backgroundColor: Colors.white,
                    foregroundColor: DdubiColors.blue,
                    child: const Icon(Icons.route_rounded),
                  ),
                  const SizedBox(height: 8),
                ],
                Container(
                  key: const Key('savedRouteMapModeBadge'),
                  padding: const EdgeInsets.symmetric(
                    horizontal: 9,
                    vertical: 6,
                  ),
                  decoration: BoxDecoration(
                    color: Colors.white.withValues(alpha: .94),
                    borderRadius: BorderRadius.circular(12),
                  ),
                  child: Text(
                    hasCoordinates
                        ? '서버 경로 좌표 · ${widget.routeMap.coordinates.length}개 점'
                        : '지도 좌표 없음',
                    style: const TextStyle(
                      fontSize: 10,
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _SavedRoutePolylinePainter extends CustomPainter {
  const _SavedRoutePolylinePainter(
    this.routeMap, {
    this.projectedSegments = const [],
  });

  final SavedRouteMap routeMap;
  final List<_ProjectedRouteSegment> projectedSegments;

  @override
  void paint(Canvas canvas, Size size) {
    // 지도 투영으로 얻은 화면 좌표만 그린다. 예전에는 좌표 범위를 위젯 크기에 비례 배치하는
    // 임시 방식으로도 그렸는데, 실제 지도 타일과 맞지 않아 경로가 강 위 같은 엉뚱한 곳에
    // 그려졌다. 투영 전에는 아무것도 그리지 않고 지도만 보여준다.
    for (final segment in projectedSegments) {
      _drawSegment(canvas, segment.mode, segment.points, segment.approximate);
    }
  }

  /// 실제 좌표가 없어 앞뒤를 이은 선은 점선으로 그린다. 도보도 늘 점선이다 —
  /// 실선은 "이 길로 간다", 점선은 "이쯤을 지난다" 는 뜻으로 구별한다.
  void _drawSegment(
    Canvas canvas,
    String mode,
    List<Offset> points,
    bool approximate,
  ) {
    if (points.length < 2) return;
    final paint = Paint()
      ..color = _modeColor(mode)
      ..strokeWidth = mode == 'WALK' ? 5 : 7
      ..strokeCap = StrokeCap.round
      ..strokeJoin = StrokeJoin.round
      ..style = PaintingStyle.stroke;
    if (mode == 'WALK' || approximate) {
      for (var index = 0; index < points.length - 1; index++) {
        final start = points[index];
        final end = points[index + 1];
        final distance = (end - start).distance;
        if (distance == 0) continue;
        final direction = (end - start) / distance;
        for (var offset = 0.0; offset < distance; offset += 12) {
          canvas.drawLine(
            start + direction * offset,
            start + direction * (offset + 6).clamp(0, distance),
            paint,
          );
        }
      }
    } else {
      final path = Path()..moveTo(points.first.dx, points.first.dy);
      for (final coordinate in points.skip(1)) {
        path.lineTo(coordinate.dx, coordinate.dy);
      }
      canvas.drawPath(path, paint);
    }
  }

  @override
  bool shouldRepaint(covariant _SavedRoutePolylinePainter oldDelegate) =>
      oldDelegate.routeMap != routeMap ||
      oldDelegate.projectedSegments != projectedSegments;
}

class _ProjectedRouteSegment {
  const _ProjectedRouteSegment({
    required this.mode,
    required this.points,
    this.approximate = false,
  });

  final String mode;
  final List<Offset> points;

  /// 저장된 좌표가 아니라 앞뒤 구간을 이어 만든 직선인가.
  final bool approximate;
}

class _RouteMapView extends StatefulWidget {
  const _RouteMapView({
    required this.routes,
    required this.selectedKind,
    required this.departureLabel,
    required this.destinationLabel,
    required this.onSelected,
    this.showRouteSelector = true,
  });

  final List<RouteOption> routes;
  final RouteKind selectedKind;
  final String departureLabel;
  final String destinationLabel;
  final ValueChanged<RouteKind> onSelected;
  final bool showRouteSelector;

  @override
  State<_RouteMapView> createState() => _RouteMapViewState();
}

class _RouteMapViewState extends State<_RouteMapView> {
  KakaoMapController? _mapController;

  static const _departure = LatLng(latitude: 37.5089, longitude: 127.0631);
  static const _current = LatLng(latitude: 37.5045, longitude: 127.0490);
  static const _destination = LatLng(latitude: 37.5006, longitude: 127.0364);
  static const _center = LatLng(latitude: 37.5048, longitude: 127.0498);
  static const _cameraAnimation = CameraAnimation(
    duration: 450,
    autoElevation: true,
    isConsecutive: false,
  );

  int get _entireRouteLevel => kIsWeb ? 5 : 13;

  @override
  void didUpdateWidget(covariant _RouteMapView oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.selectedKind != widget.selectedKind) {
      _showEntireRoute();
    }
  }

  Future<void> _onMapCreated(KakaoMapController controller) async {
    _mapController = controller;
    try {
      if (!kIsWeb) {
        await controller.addMarkerLayer(
          layerId: KakaoMapController.defaultLabelLayerId,
          zOrder: 1000,
          clickable: false,
        );
      }
      await controller.addMarkers(
        markerOptions: [
          MarkerOption(id: 'departure', latLng: _departure, text: '출발'),
          MarkerOption(id: 'current', latLng: _current, text: '현재 위치'),
          MarkerOption(id: 'destination', latLng: _destination, text: '도착'),
        ],
      );
      await _showEntireRoute();
    } catch (error) {
      AppLog.debug('Kakao Maps marker setup failed: $error');
    }
  }

  Future<void> _showEntireRoute() =>
      _moveCamera(position: _center, zoomLevel: _entireRouteLevel);

  Future<void> _showCurrentLocation() async {
    final controller = _mapController;
    if (controller == null) return;
    try {
      final currentZoomLevel = await controller.getZoomLevel();
      if (currentZoomLevel == null) return;
      await _moveCamera(position: _current, zoomLevel: currentZoomLevel);
    } catch (error) {
      AppLog.debug('Kakao Maps zoom level lookup failed: $error');
    }
  }

  Future<void> _moveCamera({
    required LatLng position,
    required int zoomLevel,
  }) async {
    final controller = _mapController;
    if (controller == null) return;
    try {
      await controller.moveCamera(
        cameraUpdate: CameraUpdate(position: position, zoomLevel: zoomLevel),
        animation: _cameraAnimation,
      );
    } catch (error) {
      AppLog.debug('Kakao Maps camera move failed: $error');
    }
  }

  void _handleRouteSelected(RouteKind kind) {
    widget.onSelected(kind);
    if (kind == widget.selectedKind) {
      _showEntireRoute();
    }
  }

  @override
  Widget build(BuildContext context) => ClipRRect(
    borderRadius: BorderRadius.circular(16),
    child: LayoutBuilder(
      builder: (context, constraints) => Stack(
        children: [
          Positioned.fill(
            child: KakaoMapConfig.isReady
                ? KakaoMap(
                    initialPosition: _center,
                    initialLevel: _entireRouteLevel,
                    onMapCreated: _onMapCreated,
                  )
                : ColoredBox(
                    color: const Color(0xFFEAF2EC),
                    child: CustomPaint(
                      painter: _MapPainter(selectedKind: widget.selectedKind),
                    ),
                  ),
          ),
          if (widget.showRouteSelector)
            Positioned(
              left: 12,
              right: 12,
              top: 12,
              child: Material(
                color: Colors.white.withValues(alpha: .94),
                borderRadius: BorderRadius.circular(12),
                child: Padding(
                  padding: const EdgeInsets.all(8),
                  child: Wrap(
                    spacing: 6,
                    runSpacing: 6,
                    children: widget.routes
                        .map(
                          (route) => ChoiceChip(
                            label: Text(route.label),
                            selected: route.kind == widget.selectedKind,
                            avatar: CircleAvatar(
                              backgroundColor: _routeTone(route.kind),
                              radius: 5,
                            ),
                            onSelected: (_) => _handleRouteSelected(route.kind),
                          ),
                        )
                        .toList(),
                  ),
                ),
              ),
            ),
          if (!KakaoMapConfig.isReady) ...[
            Positioned(
              key: const Key('mapCurrentMarker'),
              left: constraints.maxWidth * .42,
              top: constraints.maxHeight * .54,
              child: const _MapMarker(
                label: '현재 위치 (Mock)',
                icon: Icons.my_location,
                tone: DdubiColors.blue,
              ),
            ),
            Positioned(
              key: const Key('mapDepartureMarker'),
              left: 12,
              bottom: 14,
              child: _MapMarker(
                label: '출발 · ${widget.departureLabel}',
                icon: Icons.trip_origin,
                tone: DdubiColors.green,
              ),
            ),
            Positioned(
              key: const Key('mapDestinationMarker'),
              right: 12,
              top: constraints.maxHeight * .22,
              child: _MapMarker(
                label: '도착 · ${widget.destinationLabel}',
                icon: Icons.location_on,
                tone: DdubiColors.red,
              ),
            ),
          ],
          Positioned(
            right: 12,
            bottom: 12,
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.end,
              children: [
                FloatingActionButton.small(
                  key: const Key('mapEntireRouteButton'),
                  heroTag: 'map-entire-route',
                  tooltip: '전체 경로 보기',
                  onPressed: KakaoMapConfig.isReady ? _showEntireRoute : null,
                  backgroundColor: Colors.white,
                  foregroundColor: DdubiColors.blue,
                  child: const Icon(Icons.route),
                ),
                const SizedBox(height: 8),
                FloatingActionButton.small(
                  key: const Key('mapCurrentLocationButton'),
                  heroTag: 'map-current-location',
                  tooltip: '현재 위치로 이동',
                  onPressed: KakaoMapConfig.isReady
                      ? _showCurrentLocation
                      : null,
                  backgroundColor: Colors.white,
                  foregroundColor: DdubiColors.blue,
                  child: const Icon(Icons.my_location),
                ),
                const SizedBox(height: 8),
                _MapModeBadge(isKakaoMap: KakaoMapConfig.isReady),
              ],
            ),
          ),
        ],
      ),
    ),
  );
}

class _MapModeBadge extends StatelessWidget {
  const _MapModeBadge({required this.isKakaoMap});

  final bool isKakaoMap;

  @override
  Widget build(BuildContext context) => Container(
    key: const Key('mapModeBadge'),
    padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 6),
    decoration: BoxDecoration(
      color: Colors.white.withValues(alpha: .92),
      borderRadius: BorderRadius.circular(12),
    ),
    child: Text(
      isKakaoMap ? 'Kakao Map · Mock 좌표' : 'Mock 지도 · Kakao 키 미설정',
      style: const TextStyle(fontSize: 10, fontWeight: FontWeight.w700),
    ),
  );
}

class _MapMarker extends StatelessWidget {
  const _MapMarker({
    required this.label,
    required this.icon,
    required this.tone,
  });

  final String label;
  final IconData icon;
  final Color tone;

  @override
  Widget build(BuildContext context) => Container(
    constraints: const BoxConstraints(maxWidth: 170),
    padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 7),
    decoration: BoxDecoration(
      color: Colors.white,
      borderRadius: BorderRadius.circular(18),
      boxShadow: const [
        BoxShadow(
          color: Color(0x24000000),
          blurRadius: 8,
          offset: Offset(0, 3),
        ),
      ],
    ),
    child: Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Icon(icon, color: tone, size: 18),
        const SizedBox(width: 5),
        Flexible(
          child: Text(
            label,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: const TextStyle(fontSize: 11, fontWeight: FontWeight.w700),
          ),
        ),
      ],
    ),
  );
}

class _RouteMapDetail extends StatelessWidget {
  const _RouteMapDetail({
    required this.route,
    required this.repository,
    required this.departureLabel,
    required this.destinationLabel,
    this.scheduleId,
    this.departedAlready = false,
  });

  final RouteOption route;
  final DdubiRepository repository;
  final String departureLabel;
  final String destinationLabel;

  /// 이동 안내에서 출발을 알릴 일정. 경로 검색 결과에는 없다.
  final String? scheduleId;
  final bool departedAlready;

  @override
  Widget build(BuildContext context) => InfoCard(
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Row(
          children: [
            Expanded(child: _liveMetric('도착까지', route.detail)),
            Container(width: 1, height: 40, color: const Color(0xFFE0E8EE)),
            // 남은 거리는 서버에서 받지 않는다. 예전에는 '약 6.2km' 가 박혀 있어
            // 어떤 경로를 봐도 같은 거리가 나왔다. 칸을 지운다.
            Expanded(child: _liveMetric('도착 예정', route.arrival)),
          ],
        ),
        const Divider(height: 25),
        Text(route.transport, style: Theme.of(context).textTheme.titleSmall),
        Text('${route.detail} · ${route.fare}'),
        const SizedBox(height: 10),
        ...[
          '${route.departure}  출발 · $departureLabel',
          '이동 중  ${route.transport}',
          '${route.arrival}  도착 · $destinationLabel',
        ].map(
          (text) => ListTile(
            dense: true,
            contentPadding: EdgeInsets.zero,
            leading: Icon(
              Icons.circle,
              size: 12,
              color: _routeTone(route.kind),
            ),
            title: Text(text),
          ),
        ),
        PrimaryButton(
          departedAlready ? '이동 안내 이어보기' : '이동 안내 시작',
          onPressed: () => openPage(
            context,
            MovingScreen(
              repository: repository,
              route: route,
              scheduleId: scheduleId,
              departedAlready: departedAlready,
            ),
          ),
        ),
      ],
    ),
  );

  static Widget _liveMetric(String label, String value) => Column(
    children: [
      Text(
        label,
        style: const TextStyle(fontSize: 11, color: Color(0xFF62758A)),
      ),
      const SizedBox(height: 3),
      Text(
        value,
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
        style: const TextStyle(
          fontSize: 14,
          fontWeight: FontWeight.w900,
          color: DdubiColors.navy,
        ),
      ),
    ],
  );
}

class _RouteDeviationBanner extends StatelessWidget {
  const _RouteDeviationBanner({required this.onDismiss});
  final VoidCallback onDismiss;

  @override
  Widget build(BuildContext context) => Container(
    key: const Key('routeDeviationBanner'),
    margin: const EdgeInsets.fromLTRB(12, 4, 12, 8),
    padding: const EdgeInsets.fromLTRB(12, 10, 4, 10),
    decoration: BoxDecoration(
      color: const Color(0xFFFFECEE),
      borderRadius: BorderRadius.circular(15),
      border: Border.all(color: const Color(0xFFFFB8BD)),
    ),
    child: Row(
      children: [
        Container(
          width: 34,
          height: 34,
          decoration: const BoxDecoration(
            color: Color(0xFFFF5964),
            shape: BoxShape.circle,
          ),
          child: const Icon(
            Icons.priority_high_rounded,
            color: Colors.white,
            size: 22,
          ),
        ),
        const SizedBox(width: 10),
        const Expanded(
          child: Text(
            '추천 경로에서 벗어났어요.\n현재 위치를 확인해 주세요.',
            style: TextStyle(
              fontSize: 13,
              height: 1.35,
              fontWeight: FontWeight.w800,
              color: Color(0xFF9D2731),
            ),
          ),
        ),
        IconButton(
          key: const Key('dismissRouteDeviationBanner'),
          onPressed: onDismiss,
          icon: const Icon(Icons.close_rounded, color: Color(0xFF9D2731)),
        ),
      ],
    ),
  );
}

Color _routeTone(RouteKind kind) => switch (kind) {
  RouteKind.candidate => DdubiColors.blue,
  RouteKind.recommended => DdubiColors.green,
  RouteKind.fastest => DdubiColors.blue,
  RouteKind.safest => const Color(0xFF7257E8),
};

class _MapPainter extends CustomPainter {
  const _MapPainter({required this.selectedKind});

  final RouteKind selectedKind;

  @override
  void paint(Canvas canvas, Size size) {
    final road = Paint()
      ..color = Colors.white
      ..strokeWidth = 18
      ..style = PaintingStyle.stroke;
    final basePath = Path()
      ..moveTo(size.width * .2, size.height * .84)
      ..cubicTo(
        size.width * .55,
        size.height * .72,
        size.width * .18,
        size.height * .5,
        size.width * .58,
        size.height * .4,
      )
      ..cubicTo(
        size.width * .82,
        size.height * .32,
        size.width * .55,
        size.height * .16,
        size.width * .75,
        size.height * .08,
      );
    canvas.drawPath(basePath, road);

    _drawRoute(canvas, size, selectedKind, selected: true);
  }

  void _drawRoute(
    Canvas canvas,
    Size size,
    RouteKind kind, {
    required bool selected,
  }) {
    final offset = switch (kind) {
      RouteKind.candidate => 0.0,
      RouteKind.recommended => 0.0,
      RouteKind.fastest => -18.0,
      RouteKind.safest => 18.0,
    };
    final path = Path()
      ..moveTo(size.width * .2, size.height * .84)
      ..cubicTo(
        size.width * .55 + offset,
        size.height * .72,
        size.width * .18 + offset,
        size.height * .5,
        size.width * .58,
        size.height * .4,
      )
      ..cubicTo(
        size.width * .82 + offset,
        size.height * .32,
        size.width * .55 + offset,
        size.height * .16,
        size.width * .75,
        size.height * .08,
      );
    final paint = Paint()
      ..color = _routeTone(kind).withValues(alpha: selected ? 1 : .32)
      ..strokeWidth = selected ? 8 : 5
      ..strokeCap = StrokeCap.round
      ..style = PaintingStyle.stroke;
    canvas.drawPath(path, paint);
  }

  @override
  bool shouldRepaint(covariant _MapPainter oldDelegate) =>
      oldDelegate.selectedKind != selectedKind;
}
