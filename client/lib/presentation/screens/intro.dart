import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import '../../core/theme.dart';

class IntroScreen extends StatefulWidget {
  const IntroScreen({super.key});

  @override
  State<IntroScreen> createState() => _IntroScreenState();
}

class _IntroScreenState extends State<IntroScreen> {
  final _pages = const [
    {
      'bg': 'intro-bg-1.png',
      'title': '뚜비',
      'body': '변하는 길 위에서,\n가장 좋은 출발의 순간을 알려드려요.',
      'character': 'welcome.png',
    },
    {
      'bg': 'intro-bg-2.png',
      'title': '중요한 일정,\n놓치지 않게 도와드려요',
      'body': '일정과 실시간 이동 변수를 바탕으로\n추천 출발 시간을 안내해요.',
      'character': 'intro-calendar-clock.png',
    },
    {
      'bg': 'intro-bg-3.png',
      'title': '실시간 이동 변수를\n한눈에 확인해요',
      'body': '날씨, 혼잡도, 지하철 지연,\n도로 통제까지 함께 반영해요.',
      'character': 'empty-schedule.png',
    },
    {
      'bg': 'intro-bg-4.png',
      'title': '여러 변수를 반영해\n출발 시간을 추천해요',
      'body': '일정 중요도와 예상 지연을 반영해\n가장 알맞은 출발 시간을 알려드려요.',
      'character': 'celebrate.png',
    },
  ];
  final _controller = PageController();
  var _page = 0;

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final last = _page == _pages.length - 1;
    return Scaffold(
      backgroundColor: Colors.white,
      body: SafeArea(
        child: Stack(
          children: [
            Positioned.fill(
              child: Image.asset(
                'assets/images/ttubee/${_page == 1
                    ? 'intro-bg-bus-stop.png'
                    : _page == 2
                    ? 'intro-bg-plaza.png'
                    : _page == 3
                    ? 'intro-bg-skyline.png'
                    : 'intro-bg-4.png'}',
                fit: BoxFit.cover,
              ),
            ),
            const Positioned.fill(
              child: DecoratedBox(
                decoration: BoxDecoration(color: Color(0x66FFFDF7)),
              ),
            ),
            Column(
              children: [
                Align(
                  alignment: Alignment.centerRight,
                  child: TextButton(
                    onPressed: () => context.go('/login'),
                    child: const Text(
                      '건너뛰기',
                      style: TextStyle(
                        color: Color(0xFF078C83),
                        fontWeight: FontWeight.w800,
                      ),
                    ),
                  ),
                ),
                Expanded(
                  child: PageView.builder(
                    controller: _controller,
                    itemCount: _pages.length,
                    onPageChanged: (value) => setState(() => _page = value),
                    itemBuilder: (context, index) {
                      final page = _pages[index];
                      if (index == 0) return const _IntroStartPage();
                      return Padding(
                        padding: const EdgeInsets.fromLTRB(16, 22, 16, 8),
                        child: Column(
                          children: [
                            _IntroProgressBadge(_progressLabel(index)),
                            const SizedBox(height: 12),
                            Text(
                              page['title']!,
                              textAlign: TextAlign.center,
                              style: const TextStyle(
                                fontSize: 30,
                                height: 1.2,
                                fontWeight: FontWeight.w900,
                                color: Color(0xFF101A32),
                              ),
                            ),
                            const SizedBox(height: 10),
                            Text(
                              page['body']!,
                              textAlign: TextAlign.center,
                              style: const TextStyle(
                                fontSize: 16,
                                height: 1.5,
                                color: Color(0xFF52647A),
                              ),
                            ),
                            const SizedBox(height: 12),
                            Expanded(
                              child: Stack(
                                fit: StackFit.expand,
                                children: [
                                  if (index == 2)
                                    const Align(
                                      alignment: Alignment.bottomCenter,
                                      child: _IntroBench(),
                                    ),
                                  Align(
                                    alignment: Alignment.bottomCenter,
                                    child: Padding(
                                      padding: EdgeInsets.only(
                                        bottom: index == 2
                                            ? 8
                                            : index == 3
                                            ? 128
                                            : 18,
                                      ),
                                      child: Image.asset(
                                        'assets/images/ttubee/${page['character']}',
                                        height: index == 1
                                            ? 355
                                            : index == 2
                                            ? 220
                                            : 305,
                                        fit: BoxFit.contain,
                                      ),
                                    ),
                                  ),
                                  if (index == 1)
                                    const Positioned(
                                      right: 12,
                                      top: 30,
                                      child: _SpeechBubble('제시간에\n도착해요!'),
                                    ),
                                  if (index == 2)
                                    const Positioned(
                                      top: 18,
                                      left: 0,
                                      right: 0,
                                      child: _MobilityCards(),
                                    ),
                                  if (index == 3)
                                    const Positioned(
                                      bottom: 22,
                                      left: 0,
                                      right: 0,
                                      child: _DepartureCards(),
                                    ),
                                ],
                              ),
                            ),
                          ],
                        ),
                      );
                    },
                  ),
                ),
                Padding(
                  padding: const EdgeInsets.fromLTRB(24, 4, 24, 18),
                  child: SizedBox(
                    height: 48,
                    child: Stack(
                      children: [
                        Align(
                          alignment: Alignment.center,
                          child: Row(
                            mainAxisSize: MainAxisSize.min,
                            children: List.generate(
                              4,
                              (i) => AnimatedContainer(
                                duration: const Duration(milliseconds: 200),
                                margin: const EdgeInsets.symmetric(
                                  horizontal: 3,
                                ),
                                width: i == _page ? 10 : 8,
                                height: 8,
                                decoration: BoxDecoration(
                                  color: i == _page
                                      ? const Color(0xFF08A79D)
                                      : const Color(0xFFD4DAD8),
                                  borderRadius: BorderRadius.circular(8),
                                ),
                              ),
                            ),
                          ),
                        ),
                        if (_page > 0)
                          Align(
                            alignment: Alignment.centerLeft,
                            child: _roundNav(
                              Icons.arrow_back,
                              () => _controller.previousPage(
                                duration: const Duration(milliseconds: 260),
                                curve: Curves.easeOut,
                              ),
                            ),
                          ),
                        Align(
                          alignment: Alignment.centerRight,
                          child: _roundNav(Icons.arrow_forward, () {
                            if (last) {
                              context.go('/login');
                            } else {
                              _controller.nextPage(
                                duration: const Duration(milliseconds: 260),
                                curve: Curves.easeOut,
                              );
                            }
                          }),
                        ),
                      ],
                    ),
                  ),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  Widget _roundNav(IconData icon, VoidCallback onPressed) => SizedBox(
    width: 48,
    height: 48,
    child: FilledButton(
      onPressed: onPressed,
      style: FilledButton.styleFrom(
        padding: EdgeInsets.zero,
        shape: const CircleBorder(),
      ),
      child: Icon(icon),
    ),
  );

  String _progressLabel(int index) => switch (index) {
    0 => '1 / 4',
    1 => '2 / 4',
    2 => '3 / 4',
    _ => '4 / 4',
  };
}

class _IntroStartPage extends StatelessWidget {
  const _IntroStartPage();

  @override
  Widget build(BuildContext context) => Stack(
    fit: StackFit.expand,
    children: [
      const Positioned(
        top: 18,
        left: 0,
        right: 0,
        child: Center(child: _IntroProgressBadge('1 / 4')),
      ),
      const Positioned(
        top: 78,
        left: 0,
        right: 0,
        child: Column(
          children: [
            Text(
              '뚜비',
              style: TextStyle(
                fontSize: 50,
                height: .9,
                fontWeight: FontWeight.w900,
                color: Color(0xFF087A76),
                letterSpacing: 2,
              ),
            ),
            SizedBox(height: 7),
            Text(
              '뚜벅이의 비서',
              style: TextStyle(
                fontSize: 16,
                fontWeight: FontWeight.w900,
                color: Color(0xFF08A79D),
              ),
            ),
            SizedBox(height: 24),
            Text(
              '변하는 길 위에서,\n가장 좋은 출발의 순간을 알려주는 비서',
              textAlign: TextAlign.center,
              style: TextStyle(
                fontSize: 18,
                height: 1.35,
                fontWeight: FontWeight.w800,
                color: Color(0xFF181818),
              ),
            ),
          ],
        ),
      ),
      Positioned(
        bottom: 92,
        left: 18,
        right: 18,
        child: Image.asset(
          'assets/images/ttubee/welcome.png',
          height: 315,
          fit: BoxFit.contain,
        ),
      ),
      const Positioned(
        bottom: 42,
        left: 0,
        right: 0,
        child: Text(
          '좋은 출발이 좋은 하루를 만들어요.',
          textAlign: TextAlign.center,
          style: TextStyle(
            fontSize: 15,
            fontWeight: FontWeight.w900,
            color: Color(0xFF078C83),
          ),
        ),
      ),
    ],
  );
}

class _IntroProgressBadge extends StatelessWidget {
  const _IntroProgressBadge(this.label);

  final String label;

  @override
  Widget build(BuildContext context) => Container(
    width: 70,
    height: 30,
    decoration: BoxDecoration(
      color: const Color(0xFFDDF2EA),
      borderRadius: BorderRadius.circular(18),
    ),
    alignment: Alignment.center,
    child: Text(
      label,
      style: const TextStyle(
        color: Color(0xFF078C83),
        fontWeight: FontWeight.w800,
      ),
    ),
  );
}

class _SpeechBubble extends StatelessWidget {
  const _SpeechBubble(this.text);
  final String text;
  @override
  Widget build(BuildContext context) => Stack(
    clipBehavior: Clip.none,
    children: [
      Container(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 13),
        decoration: BoxDecoration(
          color: Colors.white,
          border: Border.all(color: const Color(0xFFD0D7D7)),
          borderRadius: BorderRadius.circular(30),
        ),
        child: Text(
          text,
          textAlign: TextAlign.center,
          style: const TextStyle(
            fontSize: 16,
            height: 1.2,
            fontWeight: FontWeight.w900,
            color: DdubiColors.navy,
          ),
        ),
      ),
      Positioned(
        bottom: -9,
        left: 22,
        child: Transform.rotate(
          angle: .65,
          child: Container(
            width: 18,
            height: 18,
            decoration: BoxDecoration(
              color: Colors.white,
              border: Border(
                right: BorderSide(color: Color(0xFFD0D7D7)),
                bottom: BorderSide(color: Color(0xFFD0D7D7)),
              ),
            ),
          ),
        ),
      ),
    ],
  );
}

class _MobilityCards extends StatelessWidget {
  const _MobilityCards();
  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.symmetric(horizontal: 18),
    child: LayoutBuilder(
      builder: (context, box) {
        final width = (box.maxWidth - 8) / 2;
        return Wrap(
          spacing: 8,
          runSpacing: 8,
          children: [
            _MiniStatus(
              Icons.wb_sunny_rounded,
              '날씨',
              '맑음',
              Color(0xFF1976E9),
              width,
            ),
            _MiniStatus(
              Icons.groups_rounded,
              '혼잡도',
              '보통',
              Color(0xFF078C83),
              width,
            ),
            _MiniStatus(
              Icons.train_rounded,
              '지하철',
              '일부 지연',
              Color(0xFFEF3D3D),
              width,
            ),
            _MiniStatus(
              Icons.add_road_rounded,
              '도로',
              '통제 없음',
              Color(0xFF078C83),
              width,
            ),
          ],
        );
      },
    ),
  );
}

class _MiniStatus extends StatelessWidget {
  const _MiniStatus(this.icon, this.title, this.value, this.color, this.width);
  final IconData icon;
  final String title, value;
  final Color color;
  final double width;
  @override
  Widget build(BuildContext context) => Container(
    width: width,
    height: 64,
    padding: const EdgeInsets.symmetric(horizontal: 11),
    decoration: BoxDecoration(
      color: Colors.white.withValues(alpha: .96),
      borderRadius: BorderRadius.circular(12),
      boxShadow: const [
        BoxShadow(
          color: Color(0x12003F3B),
          blurRadius: 8,
          offset: Offset(0, 3),
        ),
      ],
    ),
    child: Row(
      children: [
        Icon(icon, size: 29, color: color),
        const SizedBox(width: 9),
        Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Text(
              title,
              style: const TextStyle(
                fontSize: 13,
                fontWeight: FontWeight.w800,
                color: DdubiColors.navy,
              ),
            ),
            const SizedBox(height: 1),
            Text(
              value,
              style: TextStyle(
                fontSize: 13,
                fontWeight: FontWeight.w900,
                color: color,
              ),
            ),
          ],
        ),
      ],
    ),
  );
}

class _IntroBench extends StatelessWidget {
  const _IntroBench();

  @override
  Widget build(BuildContext context) => SizedBox(
    width: 286,
    height: 142,
    child: Stack(
      children: [
        const Positioned(left: 34, bottom: 4, child: _BenchLeg()),
        const Positioned(right: 34, bottom: 4, child: _BenchLeg()),
        Positioned(left: 12, right: 12, top: 28, child: _slat(24)),
        Positioned(left: 8, right: 8, top: 58, child: _slat(22)),
        Positioned(left: 0, right: 0, top: 88, child: _slat(18)),
      ],
    ),
  );

  Widget _slat(double height) => Container(
    height: height,
    decoration: BoxDecoration(
      gradient: const LinearGradient(
        begin: Alignment.topCenter,
        end: Alignment.bottomCenter,
        colors: [Color(0xFFF5C98F), Color(0xFFD99A57)],
      ),
      borderRadius: BorderRadius.circular(8),
      border: Border.all(color: const Color(0x33A8642B)),
      boxShadow: const [
        BoxShadow(
          color: Color(0x22003F3B),
          blurRadius: 7,
          offset: Offset(0, 4),
        ),
      ],
    ),
  );
}

class _BenchLeg extends StatelessWidget {
  const _BenchLeg();

  @override
  Widget build(BuildContext context) => Container(
    width: 17,
    height: 47,
    decoration: BoxDecoration(
      color: const Color(0xFF718083),
      borderRadius: BorderRadius.circular(5),
      boxShadow: const [
        BoxShadow(
          color: Color(0x25003F3B),
          blurRadius: 5,
          offset: Offset(0, 3),
        ),
      ],
    ),
  );
}

class _DepartureCards extends StatelessWidget {
  const _DepartureCards();
  @override
  Widget build(BuildContext context) => Row(
    mainAxisAlignment: MainAxisAlignment.center,
    children: const [
      _DepartureCard(
        Icons.schedule_rounded,
        '추천 출발',
        '17:35',
        Color(0xFF08A79D),
      ),
      SizedBox(width: 8),
      _DepartureCard(Icons.eco_rounded, '여유 출발', '17:25', Color(0xFF3C657E)),
      SizedBox(width: 8),
      _DepartureCard(Icons.flag_rounded, '도착 예상', '18:20', Color(0xFF9B53A4)),
    ],
  );
}

class _DepartureCard extends StatelessWidget {
  const _DepartureCard(this.icon, this.label, this.time, this.color);
  final IconData icon;
  final String label, time;
  final Color color;
  @override
  Widget build(BuildContext context) => Container(
    width: 104,
    height: 102,
    decoration: BoxDecoration(
      color: Colors.white.withValues(alpha: .96),
      borderRadius: BorderRadius.circular(15),
      border: Border.all(color: color.withValues(alpha: .55)),
      boxShadow: const [
        BoxShadow(
          color: Color(0x17003F3B),
          blurRadius: 10,
          offset: Offset(0, 4),
        ),
      ],
    ),
    child: Column(
      mainAxisAlignment: MainAxisAlignment.center,
      children: [
        Icon(icon, size: 20, color: color),
        const SizedBox(height: 3),
        Text(
          label,
          style: TextStyle(
            fontSize: 14,
            fontWeight: FontWeight.w800,
            color: color,
          ),
        ),
        const SizedBox(height: 4),
        Text(
          time,
          style: TextStyle(
            fontSize: 24,
            fontWeight: FontWeight.w900,
            color: color,
          ),
        ),
      ],
    ),
  );
}
