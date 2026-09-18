import 'package:flutter/material.dart';

import '../core/config/app_environment.dart';

class StampMissionUiModel {
  const StampMissionUiModel({
    required this.label,
    required this.description,
    required this.icon,
    required this.completed,
  });
  final String label;
  final String description;
  final IconData icon;
  final bool completed;
}

class MorePageDemoData {
  const MorePageDemoData._();

  /// 실제 미션은 개발 환경에서만 데모로 채운다. 운영에서는 비어 있고, 화면은 빈 상태를 그린다.
  static const List<StampMissionUiModel> missions = AppEnvironment.allowMockData
      ? _demoMissions
      : <StampMissionUiModel>[];

  static const _demoMissions = [
    StampMissionUiModel(
      label: '걷기',
      description: '한 걸음 더 건강한 하루',
      icon: Icons.directions_walk_rounded,
      completed: true,
    ),
    StampMissionUiModel(
      label: '버스 타기',
      description: '대중교통으로 더 가까운 세상',
      icon: Icons.directions_bus_filled_rounded,
      completed: true,
    ),
    StampMissionUiModel(
      label: '지하철 타기',
      description: '지하철로 빠르고 편리하게',
      icon: Icons.train_rounded,
      completed: true,
    ),
    StampMissionUiModel(
      label: '정시 도착',
      description: '약속한 시간에 도착해요',
      icon: Icons.schedule_rounded,
      completed: true,
    ),
    StampMissionUiModel(
      label: '즐겨찾기 추가',
      description: '자주 가는 곳을 더 편리하게',
      icon: Icons.star_rounded,
      completed: true,
    ),
    StampMissionUiModel(
      label: '새 장소 저장',
      description: '새로운 곳을 기록해요',
      icon: Icons.add_location_alt_rounded,
      completed: false,
    ),
    StampMissionUiModel(
      label: '경로 이탈 없이 도착',
      description: '계획한 경로로 안전하게',
      icon: Icons.route_rounded,
      completed: false,
    ),
    StampMissionUiModel(
      label: '일정 등록',
      description: '일정을 등록하고 더 스마트하게',
      icon: Icons.event_available_rounded,
      completed: false,
    ),
    StampMissionUiModel(
      label: '알림 확인',
      description: '중요한 알림을 놓치지 않아요',
      icon: Icons.notifications_active_rounded,
      completed: false,
    ),
    StampMissionUiModel(
      label: '비 오는 날 이동',
      description: '날씨에도 멈추지 않아요',
      icon: Icons.water_drop_rounded,
      completed: false,
    ),
  ];
}

class WeeklyStampBoardCard extends StatelessWidget {
  const WeeklyStampBoardCard({
    super.key,
    required this.onInfo,
    required this.onViewAll,
    this.missions = MorePageDemoData.missions,
  });
  final VoidCallback onInfo;
  final VoidCallback onViewAll;
  final List<StampMissionUiModel> missions;

  @override
  Widget build(BuildContext context) {
    final completed = missions.where((mission) => mission.completed).length;
    if (missions.isEmpty) {
      return Container(
        key: const Key('weeklyStampBoardCard'),
        padding: const EdgeInsets.fromLTRB(15, 13, 15, 12),
        decoration: BoxDecoration(
          color: Colors.white,
          borderRadius: BorderRadius.circular(18),
          border: Border.all(color: const Color(0xFFDCE6EC)),
        ),
        child: const Text(
          '이번 주 미션이 아직 준비되지 않았어요.',
          style: TextStyle(fontSize: 13, color: Color(0xFF52677B)),
        ),
      );
    }
    return Container(
      key: const Key('weeklyStampBoardCard'),
      padding: const EdgeInsets.fromLTRB(15, 13, 15, 12),
      decoration: BoxDecoration(
        gradient: const LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: [Color(0xFFF7FFFE), Color(0xFFE9FAF8)],
        ),
        borderRadius: BorderRadius.circular(24),
        border: Border.all(color: const Color(0xFFC8EEE9)),
        boxShadow: const [
          BoxShadow(
            color: Color(0x101A6275),
            blurRadius: 18,
            offset: Offset(0, 7),
          ),
        ],
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Row(
            children: [
              Transform.rotate(
                angle: -.13,
                child: const Icon(
                  Icons.confirmation_number_outlined,
                  color: Color(0xFF0CB3A2),
                  size: 28,
                ),
              ),
              const SizedBox(width: 8),
              const Expanded(
                child: Text(
                  '이번 주 뚜비 도장판 🌱',
                  style: TextStyle(
                    color: Color(0xFF102454),
                    fontSize: 18,
                    fontWeight: FontWeight.w900,
                    letterSpacing: -.4,
                  ),
                ),
              ),
              IconButton(
                key: const Key('stampBoardInfoButton'),
                tooltip: '뚜비 도장판 안내',
                onPressed: onInfo,
                visualDensity: VisualDensity.compact,
                icon: const Icon(
                  Icons.info_outline_rounded,
                  color: Color(0xFF60708F),
                ),
              ),
            ],
          ),
          SizedBox(
            height: 76,
            child: Stack(
              children: [
                Positioned(
                  left: 0,
                  top: 0,
                  right: 100,
                  child: Text(
                    '일상 속 이동을 더 특별하게!\n'
                    '10개의 미션을 달성하고 이번 주 도장을 모두 모아보세요.',
                    style: TextStyle(
                      color: Color(0xFF5D6B89),
                      fontSize: 12,
                      height: 1.5,
                    ),
                  ),
                ),
                Positioned(
                  right: 0,
                  bottom: -7,
                  child: Image.asset(
                    'assets/images/ttubee/stamp-board.png',
                    width: 98,
                    height: 88,
                    fit: BoxFit.contain,
                    filterQuality: FilterQuality.high,
                  ),
                ),
                Positioned(
                  right: 63,
                  top: 0,
                  child: Transform(
                    alignment: Alignment.center,
                    transform: Matrix4.rotationZ(-.12),
                    child: const Text(
                      '하나씩\n도장 모아요! ♥',
                      textAlign: TextAlign.center,
                      style: TextStyle(
                        color: Color(0xFF405072),
                        fontSize: 7.5,
                        height: 1.2,
                        fontWeight: FontWeight.w800,
                      ),
                    ),
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(height: 4),
          Row(
            key: const Key('stampMissionPreviewRow'),
            children: missions
                .map(
                  (mission) => Expanded(child: _PreviewStamp(mission: mission)),
                )
                .toList(),
          ),
          const SizedBox(height: 10),
          Material(
            color: Colors.transparent,
            child: InkWell(
              key: const Key('stampBoardViewAllButton'),
              onTap: onViewAll,
              borderRadius: BorderRadius.circular(14),
              child: Padding(
                padding: const EdgeInsets.symmetric(vertical: 3),
                child: Row(
                  children: [
                    Expanded(
                      child: ClipRRect(
                        borderRadius: BorderRadius.circular(99),
                        child: LinearProgressIndicator(
                          key: const Key('stampBoardPreviewProgress'),
                          value: missions.isEmpty
                              ? 0
                              : completed / missions.length,
                          minHeight: 10,
                          backgroundColor: const Color(0xFFD8F2EF),
                          valueColor: const AlwaysStoppedAnimation(
                            Color(0xFF36C7B5),
                          ),
                        ),
                      ),
                    ),
                    const SizedBox(width: 9),
                    Text(
                      '$completed/${missions.length}개 달성했어요!',
                      key: const Key('stampBoardProgressLabel'),
                      style: const TextStyle(
                        color: Color(0xFF405072),
                        fontSize: 10,
                        fontWeight: FontWeight.w800,
                      ),
                    ),
                    const Icon(
                      Icons.chevron_right_rounded,
                      color: Color(0xFF0CB3A2),
                      size: 17,
                    ),
                  ],
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class StampBoardGuideSheet extends StatelessWidget {
  const StampBoardGuideSheet({
    super.key,
    this.missions = MorePageDemoData.missions,
  });

  final List<StampMissionUiModel> missions;

  static const _missionColors = [
    Color(0xFF16BCA8),
    Color(0xFF438FDC),
    Color(0xFF00A88F),
    Color(0xFF119A9B),
    Color(0xFFFFBE2E),
    Color(0xFFFF6472),
    Color(0xFF20A986),
    Color(0xFFFF7185),
    Color(0xFFFFB526),
    Color(0xFF4B9DE3),
  ];

  @override
  Widget build(BuildContext context) => DraggableScrollableSheet(
    initialChildSize: .95,
    minChildSize: .75,
    maxChildSize: .98,
    expand: false,
    builder: (context, controller) => Material(
      key: const Key('stampBoardGuideSheet'),
      color: const Color(0xFFF0FFFD),
      borderRadius: const BorderRadius.vertical(top: Radius.circular(30)),
      clipBehavior: Clip.antiAlias,
      child: ListView(
        controller: controller,
        padding: const EdgeInsets.fromLTRB(16, 9, 16, 28),
        children: [
          Center(
            child: Container(
              width: 42,
              height: 4,
              decoration: BoxDecoration(
                color: const Color(0xFFC5D5D8),
                borderRadius: BorderRadius.circular(99),
              ),
            ),
          ),
          const SizedBox(height: 12),
          const _GuideHeader(),
          Container(
            padding: const EdgeInsets.fromLTRB(10, 15, 10, 12),
            decoration: BoxDecoration(
              color: Colors.white,
              borderRadius: BorderRadius.circular(24),
              border: Border.all(color: const Color(0xFFD8ECE9)),
              boxShadow: const [
                BoxShadow(
                  color: Color(0x0D1A6275),
                  blurRadius: 16,
                  offset: Offset(0, 6),
                ),
              ],
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                const Row(
                  children: [
                    CircleAvatar(
                      radius: 18,
                      backgroundColor: Color(0xFFDDF8F3),
                      child: Icon(
                        Icons.adjust_rounded,
                        color: Color(0xFF0CB3A2),
                      ),
                    ),
                    SizedBox(width: 9),
                    Expanded(
                      child: Text(
                        '이번 주 미션 10가지',
                        style: TextStyle(
                          color: Color(0xFF102454),
                          fontSize: 19,
                          fontWeight: FontWeight.w900,
                        ),
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 12),
                GridView.builder(
                  key: const Key('stampBoardGuideMissionGrid'),
                  shrinkWrap: true,
                  physics: const NeverScrollableScrollPhysics(),
                  itemCount: missions.length,
                  gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
                    crossAxisCount: 5,
                    mainAxisSpacing: 7,
                    crossAxisSpacing: 7,
                    childAspectRatio: .58,
                  ),
                  itemBuilder: (context, index) => _GuideMissionCard(
                    mission: missions[index],
                    color: _missionColors[index % _missionColors.length],
                  ),
                ),
                const SizedBox(height: 13),
                Container(
                  height: 78,
                  padding: const EdgeInsets.only(left: 90, right: 52),
                  alignment: Alignment.center,
                  decoration: BoxDecoration(
                    color: const Color(0xFFE9FAF7),
                    borderRadius: BorderRadius.circular(20),
                  ),
                  child: Stack(
                    clipBehavior: Clip.none,
                    children: [
                      const Align(
                        child: Text(
                          '미션을 달성할 때마다\n뚜비 도장이 쏙쏙 쌓여요! ♥',
                          textAlign: TextAlign.center,
                          style: TextStyle(
                            color: Color(0xFF405072),
                            fontSize: 11,
                            height: 1.4,
                            fontWeight: FontWeight.w800,
                          ),
                        ),
                      ),
                      Positioned(
                        left: -98,
                        bottom: -10,
                        child: Image.asset(
                          'assets/images/ttubee/celebrate.png',
                          width: 96,
                          height: 96,
                          fit: BoxFit.contain,
                        ),
                      ),
                      const Positioned(
                        right: -46,
                        top: 18,
                        child: Icon(
                          Icons.verified_rounded,
                          size: 40,
                          color: Color(0xFF87DDD2),
                        ),
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    ),
  );
}

class _GuideHeader extends StatelessWidget {
  const _GuideHeader();

  @override
  Widget build(BuildContext context) => SizedBox(
    height: 150,
    child: Stack(
      children: [
        const Positioned(
          left: 2,
          top: 10,
          right: 120,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                '뚜비와 함께하는 🌱',
                style: TextStyle(
                  color: Color(0xFF0CB3A2),
                  fontSize: 14,
                  fontWeight: FontWeight.w900,
                ),
              ),
              SizedBox(height: 5),
              Text(
                '이번 주 뚜비 도장판',
                style: TextStyle(
                  color: Color(0xFF102454),
                  fontSize: 23,
                  height: 1.12,
                  fontWeight: FontWeight.w900,
                  letterSpacing: -.7,
                ),
              ),
              SizedBox(height: 10),
              Text(
                '일상 속 이동을 더 특별하게!\n'
                '10개의 미션을 달성하고 도장을 모아보세요.',
                style: TextStyle(
                  color: Color(0xFF64708B),
                  fontSize: 11.5,
                  height: 1.45,
                ),
              ),
            ],
          ),
        ),
        Positioned(
          right: -5,
          bottom: -4,
          child: Image.asset(
            'assets/images/ttubee/celebrate.png',
            width: 130,
            height: 130,
            fit: BoxFit.contain,
          ),
        ),
        Positioned(
          right: 75,
          top: 0,
          child: Container(
            padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 6),
            decoration: BoxDecoration(
              color: Colors.white.withValues(alpha: .94),
              borderRadius: BorderRadius.circular(16),
            ),
            child: const Text(
              '오늘도 뚜비와\n함께 이동해요! ♥',
              textAlign: TextAlign.center,
              style: TextStyle(
                color: Color(0xFF405072),
                fontSize: 7.5,
                height: 1.25,
                fontWeight: FontWeight.w800,
              ),
            ),
          ),
        ),
      ],
    ),
  );
}

class _GuideMissionCard extends StatelessWidget {
  const _GuideMissionCard({required this.mission, required this.color});

  final StampMissionUiModel mission;
  final Color color;

  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.symmetric(horizontal: 3, vertical: 7),
    decoration: BoxDecoration(
      color: Colors.white,
      borderRadius: BorderRadius.circular(15),
      border: Border.all(color: const Color(0xFFDCEBEA)),
    ),
    child: Column(
      mainAxisAlignment: MainAxisAlignment.center,
      children: [
        Container(
          width: 42,
          height: 42,
          decoration: const BoxDecoration(
            color: Color(0xFFE1F8F4),
            shape: BoxShape.circle,
          ),
          child: Icon(mission.icon, color: color, size: 25),
        ),
        const SizedBox(height: 6),
        Text(
          mission.label,
          maxLines: 2,
          overflow: TextOverflow.ellipsis,
          textAlign: TextAlign.center,
          style: const TextStyle(
            color: Color(0xFF142650),
            fontSize: 8.5,
            height: 1.12,
            fontWeight: FontWeight.w900,
          ),
        ),
        const SizedBox(height: 4),
        Text(
          mission.description,
          maxLines: 3,
          overflow: TextOverflow.ellipsis,
          textAlign: TextAlign.center,
          style: const TextStyle(
            color: Color(0xFF7A869A),
            fontSize: 6.4,
            height: 1.16,
          ),
        ),
      ],
    ),
  );
}

class StampBoardDetailSheet extends StatelessWidget {
  const StampBoardDetailSheet({
    super.key,
    this.missions = MorePageDemoData.missions,
  });
  final List<StampMissionUiModel> missions;

  @override
  Widget build(BuildContext context) {
    final completed = missions.where((mission) => mission.completed).length;
    return DraggableScrollableSheet(
      initialChildSize: .94,
      minChildSize: .72,
      maxChildSize: .98,
      expand: false,
      builder: (context, controller) => Material(
        key: const Key('stampBoardDetailSheet'),
        color: const Color(0xFFF1FFFD),
        borderRadius: const BorderRadius.vertical(top: Radius.circular(30)),
        clipBehavior: Clip.antiAlias,
        child: ListView(
          controller: controller,
          padding: const EdgeInsets.fromLTRB(16, 9, 16, 30),
          children: [
            Center(
              child: Container(
                width: 42,
                height: 4,
                decoration: BoxDecoration(
                  color: const Color(0xFFC5D5D8),
                  borderRadius: BorderRadius.circular(99),
                ),
              ),
            ),
            const SizedBox(height: 12),
            _DetailHeader(),
            Container(
              padding: const EdgeInsets.fromLTRB(12, 16, 12, 16),
              decoration: BoxDecoration(
                color: Colors.white,
                borderRadius: BorderRadius.circular(24),
                border: Border.all(color: const Color(0xFFD8ECE9)),
                boxShadow: const [
                  BoxShadow(
                    color: Color(0x0D1A6275),
                    blurRadius: 16,
                    offset: Offset(0, 6),
                  ),
                ],
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  const Row(
                    children: [
                      CircleAvatar(
                        radius: 19,
                        backgroundColor: Color(0xFFDDF8F3),
                        child: Icon(
                          Icons.adjust_rounded,
                          color: Color(0xFF0CB3A2),
                        ),
                      ),
                      SizedBox(width: 9),
                      Expanded(
                        child: Text(
                          '이번 주 미션 10가지',
                          style: TextStyle(
                            color: Color(0xFF102454),
                            fontSize: 19,
                            fontWeight: FontWeight.w900,
                          ),
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: 13),
                  LayoutBuilder(
                    builder: (context, constraints) {
                      final columns = constraints.maxWidth < 285 ? 3 : 4;
                      return GridView.builder(
                        key: const Key('stampMissionGrid'),
                        shrinkWrap: true,
                        physics: const NeverScrollableScrollPhysics(),
                        itemCount: missions.length,
                        gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
                          crossAxisCount: columns,
                          mainAxisSpacing: 9,
                          crossAxisSpacing: 9,
                          childAspectRatio: .78,
                        ),
                        itemBuilder: (context, index) =>
                            _DetailedStamp(mission: missions[index]),
                      );
                    },
                  ),
                  const SizedBox(height: 15),
                  ClipRRect(
                    borderRadius: BorderRadius.circular(99),
                    child: LinearProgressIndicator(
                      key: const Key('stampBoardProgress'),
                      value: missions.isEmpty ? 0 : completed / missions.length,
                      minHeight: 11,
                      backgroundColor: const Color(0xFFD9F2EF),
                      valueColor: const AlwaysStoppedAnimation(
                        Color(0xFF20C5B3),
                      ),
                    ),
                  ),
                  const SizedBox(height: 9),
                  Center(
                    child: Text(
                      '$completed/${missions.length}개 달성했어요!',
                      style: const TextStyle(
                        color: Color(0xFF0A8F82),
                        fontSize: 16,
                        fontWeight: FontWeight.w900,
                      ),
                    ),
                  ),
                  const SizedBox(height: 14),
                  Container(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 12,
                      vertical: 11,
                    ),
                    decoration: BoxDecoration(
                      color: const Color(0xFFE8FAF7),
                      borderRadius: BorderRadius.circular(18),
                    ),
                    child: const Text(
                      '하나씩 미션을 달성할 때마다 도장이 쏙쏙!\n'
                      '이번 주도 뚜비와 함께해요. ♥',
                      textAlign: TextAlign.center,
                      style: TextStyle(
                        color: Color(0xFF405072),
                        fontSize: 12,
                        height: 1.45,
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _DetailHeader extends StatelessWidget {
  const _DetailHeader();

  @override
  Widget build(BuildContext context) => SizedBox(
    height: 142,
    child: Stack(
      children: [
        const Positioned(
          left: 2,
          top: 10,
          right: 118,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                '뚜비와 함께하는 🌱',
                style: TextStyle(
                  color: Color(0xFF0CB3A2),
                  fontSize: 14,
                  fontWeight: FontWeight.w900,
                ),
              ),
              SizedBox(height: 5),
              Text(
                '이번 주 뚜비 도장판',
                style: TextStyle(
                  color: Color(0xFF102454),
                  fontSize: 23,
                  height: 1.12,
                  fontWeight: FontWeight.w900,
                  letterSpacing: -.7,
                ),
              ),
              SizedBox(height: 10),
              Text(
                '일상 속 이동을 더 특별하게!\n'
                '10개의 미션을 달성하고 도장을 모아보세요.',
                style: TextStyle(
                  color: Color(0xFF64708B),
                  fontSize: 11.5,
                  height: 1.45,
                ),
              ),
            ],
          ),
        ),
        Positioned(
          right: -5,
          bottom: -7,
          child: Image.asset(
            'assets/images/ttubee/celebrate.png',
            width: 125,
            height: 125,
            fit: BoxFit.contain,
          ),
        ),
        Positioned(
          right: 77,
          top: 1,
          child: Container(
            padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 6),
            decoration: BoxDecoration(
              color: Colors.white.withValues(alpha: .92),
              borderRadius: BorderRadius.circular(16),
            ),
            child: const Text(
              '오늘도 뚜비와\n함께 이동해요! ♥',
              textAlign: TextAlign.center,
              style: TextStyle(
                color: Color(0xFF405072),
                fontSize: 7.5,
                height: 1.25,
                fontWeight: FontWeight.w800,
              ),
            ),
          ),
        ),
      ],
    ),
  );
}

class _PreviewStamp extends StatelessWidget {
  const _PreviewStamp({required this.mission});
  final StampMissionUiModel mission;

  @override
  Widget build(BuildContext context) => Column(
    mainAxisSize: MainAxisSize.min,
    children: [
      Container(
        width: 27,
        height: 27,
        decoration: BoxDecoration(
          color: mission.completed
              ? const Color(0xFFD9F8F3)
              : const Color(0xFFF0F4F6),
          shape: BoxShape.circle,
          border: Border.all(
            color: mission.completed
                ? const Color(0xFF9BE4DA)
                : const Color(0xFFDDE5E9),
          ),
        ),
        child: Icon(
          mission.completed ? mission.icon : Icons.lock_outline_rounded,
          color: mission.completed
              ? const Color(0xFF0AB3A1)
              : const Color(0xFFA7B2BD),
          size: 16,
        ),
      ),
      const SizedBox(height: 3),
      Text(
        _shortLabel(mission.label),
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
        textAlign: TextAlign.center,
        style: TextStyle(
          color: mission.completed
              ? const Color(0xFF142650)
              : const Color(0xFF7D8998),
          fontSize: 6.5,
          fontWeight: FontWeight.w800,
        ),
      ),
    ],
  );

  String _shortLabel(String label) => switch (label) {
    '버스 타기' => '버스',
    '지하철 타기' => '지하철',
    '즐겨찾기 추가' => '즐겨찾기',
    '새 장소 저장' => '새 장소',
    '경로 이탈 없이 도착' => '경로 도착',
    '비 오는 날 이동' => '비 오는 날',
    _ => label,
  };
}

class _DetailedStamp extends StatelessWidget {
  const _DetailedStamp({required this.mission});
  final StampMissionUiModel mission;

  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 5),
    decoration: BoxDecoration(
      color: mission.completed
          ? const Color(0xFFF1FCFA)
          : const Color(0xFFF5F7F8),
      borderRadius: BorderRadius.circular(17),
      border: Border.all(
        color: mission.completed
            ? const Color(0xFFC8EEE8)
            : const Color(0xFFE2E8EB),
      ),
    ),
    child: Column(
      mainAxisAlignment: MainAxisAlignment.center,
      children: [
        Container(
          width: 36,
          height: 36,
          decoration: BoxDecoration(
            color: mission.completed
                ? const Color(0xFFD9F8F3)
                : const Color(0xFFE9EEF0),
            shape: BoxShape.circle,
          ),
          child: Icon(
            mission.completed ? mission.icon : Icons.lock_outline_rounded,
            color: mission.completed
                ? const Color(0xFF0AB3A1)
                : const Color(0xFFA7B2BD),
            size: 21,
          ),
        ),
        const SizedBox(height: 4),
        Text(
          mission.label,
          maxLines: 2,
          textAlign: TextAlign.center,
          overflow: TextOverflow.ellipsis,
          style: const TextStyle(
            color: Color(0xFF142650),
            fontSize: 9,
            height: 1.15,
            fontWeight: FontWeight.w900,
          ),
        ),
        const SizedBox(height: 3),
        Text(
          mission.description,
          maxLines: 2,
          textAlign: TextAlign.center,
          overflow: TextOverflow.ellipsis,
          style: const TextStyle(
            color: Color(0xFF7A869A),
            fontSize: 7,
            height: 1.2,
          ),
        ),
      ],
    ),
  );
}
