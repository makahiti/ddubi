import 'package:flutter/material.dart';

import '../core/theme.dart';
import '../domain/travel_conditions.dart';
import 'widgets.dart';

/// 일정 상세의 "오늘의 이동 변수" 칸.
///
/// 예전에는 한 줄이었다.
///
/// ```
/// 오늘의 이동 변수(혼잡도·도로 통제·지하철 지연)는 아직 확인되지 않았어요.
/// ```
///
/// 한 줄이라 **무엇이 되고 무엇이 안 되는지 알 수 없었다.** 날씨만 들어와도 이 문구는
/// 그대로였다. 그래서 칸을 넷으로 나눠 각각의 상태를 따로 보여준다.
///
/// 조회 API 는 아직 없다(2026-09-22 기준, 백엔드 작업 중). [service] 가 null 이면
/// 네 칸 모두 "확인 안 됨"으로 그린다 — 지금 실제 상태가 그렇다.
class TravelConditionsCard extends StatefulWidget {
  const TravelConditionsCard({
    super.key,
    required this.scheduleId,
    this.service,
  });

  final String scheduleId;

  /// 서버에서 값을 가져오는 통로. 없으면 조회하지 않고 "확인 안 됨"만 보여준다.
  final TravelConditionsService? service;

  /// 값이 없을 때 칸에 적는 말. 0 이나 "맑음" 같은 그럴듯한 값으로 채우지 않는다.
  static const String unavailableText = '확인 안 됨';

  @override
  State<TravelConditionsCard> createState() => _TravelConditionsCardState();
}

class _TravelConditionsCardState extends State<TravelConditionsCard> {
  TravelConditions conditions = TravelConditions.unknown;
  bool loading = false;

  /// 조회가 실패해도 카드를 숨기지 않는다. 네 칸이 "확인 안 됨"으로 남을 뿐이다.
  /// 이동 변수는 참고 정보라, 못 가져왔다고 해서 오류 화면을 띄울 일은 아니다.
  bool failed = false;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final service = widget.service;
    if (service == null) return;
    setState(() => loading = true);
    try {
      final result = await service.conditionsFor(widget.scheduleId);
      if (!mounted) return;
      setState(() {
        conditions = result;
        failed = false;
        loading = false;
      });
    } catch (_) {
      if (!mounted) return;
      setState(() {
        failed = true;
        loading = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) => InfoCard(
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            const Expanded(
              child: Text(
                '오늘의 이동 변수',
                style: TextStyle(fontWeight: FontWeight.w700, fontSize: 15),
              ),
            ),
            if (loading)
              const SizedBox(
                width: 14,
                height: 14,
                child: CircularProgressIndicator(strokeWidth: 2),
              )
            else if (conditions.observedAt != null)
              Text(
                '${_two(conditions.observedAt!.hour)}:${_two(conditions.observedAt!.minute)} 기준',
                style: const TextStyle(fontSize: 12, color: Color(0xFF667085)),
              ),
          ],
        ),
        const SizedBox(height: 12),
        _row(Icons.wb_sunny_outlined, '날씨', conditions.weather),
        _row(Icons.groups_outlined, '혼잡도', conditions.congestion),
        _row(Icons.construction_outlined, '도로 통제', conditions.roadBlock),
        _row(Icons.subway_outlined, '지하철', conditions.subwayDelay),
        if (!conditions.hasAny) ...[
          const SizedBox(height: 10),
          Text(
            failed
                ? '이동 변수를 불러오지 못했어요. 계산 결과에는 영향이 없어요.'
                : '아직 이동 변수를 받아오지 않아요. 계산 결과에는 영향이 없어요.',
            style: const TextStyle(fontSize: 12, color: Color(0xFF667085)),
          ),
        ],
      ],
    ),
  );

  Widget _row(IconData icon, String label, TravelConditionItem item) => Padding(
    key: Key('travelCondition_$label'),
    padding: const EdgeInsets.symmetric(vertical: 5),
    child: Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Icon(
          icon,
          size: 18,
          color: item.available ? DdubiColors.navy : const Color(0xFFB0BACB),
        ),
        const SizedBox(width: 10),
        SizedBox(
          width: 62,
          child: Text(
            label,
            style: const TextStyle(fontSize: 13, color: Color(0xFF5A6B85)),
          ),
        ),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                item.text ?? TravelConditionsCard.unavailableText,
                style: TextStyle(
                  fontSize: 13,
                  // 값이 없는 칸은 흐리게. 있는 값과 없는 값이 같은 무게로 보이면
                  // 사용자가 "확인 안 됨"을 하나의 상태값으로 읽는다.
                  color: item.available
                      ? const Color(0xFF344054)
                      : const Color(0xFF98A2B3),
                  fontWeight: item.available
                      ? FontWeight.w600
                      : FontWeight.w400,
                ),
              ),
              if (item.note != null)
                Text(
                  item.note!,
                  style: const TextStyle(
                    fontSize: 11,
                    color: Color(0xFF98A2B3),
                  ),
                ),
            ],
          ),
        ),
      ],
    ),
  );

  static String _two(int value) => value.toString().padLeft(2, '0');
}
