import 'dart:async';

import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import '../../core/theme.dart';
import '../../domain/models.dart';
import '../widgets.dart';
import 'shared.dart';

class ScheduleFormScreen extends StatefulWidget {
  const ScheduleFormScreen({
    super.key,
    required this.repository,
    required this.placeSearchService,
    this.initialDate,
    this.schedule,
  });
  final ScheduleRepository repository;
  final PlaceSearchService placeSearchService;
  final DateTime? initialDate;
  final Schedule? schedule;
  @override
  State<ScheduleFormScreen> createState() => _ScheduleFormScreenState();
}

class RepeatScheduleFormScreen extends StatefulWidget {
  const RepeatScheduleFormScreen({
    super.key,
    required this.repository,
    required this.placeSearchService,
    this.initialDate,
    this.rule,
  });

  final RepeatScheduleRepository repository;
  final PlaceSearchService placeSearchService;
  final DateTime? initialDate;
  final RepeatScheduleRule? rule;

  @override
  State<RepeatScheduleFormScreen> createState() =>
      _RepeatScheduleFormScreenState();
}

class _RepeatScheduleFormScreenState extends State<RepeatScheduleFormScreen> {
  final formKey = GlobalKey<FormState>();
  final title = TextEditingController();
  final time = TextEditingController(text: '09:00');
  final weekdays = <int>{1, 2, 3, 4, 5};
  PlaceLocation? origin;
  PlaceLocation? destination;

  late DateTime startDate;
  late DateTime endDate;
  bool hasEndDate = false;
  bool considerLastTrip = false;
  ScheduleImportance importance = ScheduleImportance.high;
  TransportPreferenceChoice transport = TransportPreferenceChoice.balanced;

  @override
  void initState() {
    super.initState();
    final rule = widget.rule;
    title.text = rule?.title ?? '';
    time.text = rule?.time ?? '09:00';
    origin = rule?.origin;
    destination = rule?.destination;
    weekdays
      ..clear()
      ..addAll(rule?.weekdays ?? const {1, 2, 3, 4, 5});
    startDate = rule?.startDate ?? widget.initialDate ?? DateTime.now();
    endDate = rule?.untilDate ?? startDate.add(const Duration(days: 84));
    hasEndDate = rule?.untilDate != null;
    considerLastTrip = rule?.considerLastTrip ?? false;
    importance = rule?.importance ?? ScheduleImportance.high;
    transport = rule?.preferredTransport ?? TransportPreferenceChoice.balanced;
  }

  @override
  void dispose() {
    title.dispose();
    time.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AppPage(
    title: widget.rule == null ? '반복 일정 등록' : '반복 일정 수정',
    child: Form(
      key: formKey,
      child: LayoutBuilder(
        builder: (context, constraints) {
          final isWide = constraints.maxWidth >= 760;
          final basic = _basicCard(context);
          final repeat = _repeatCard(context);
          final preference = _preferenceCard(context);
          return ListView(
            padding: screenPadding,
            children: [
              const TtubeeGuideCard(
                '출근이나 수업처럼 정해진 요일에 반복되는 일정을 한 번에 등록해요.',
                compact: true,
              ),
              const SizedBox(height: 16),
              if (isWide)
                Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Expanded(child: basic),
                    const SizedBox(width: 16),
                    Expanded(
                      child: Column(
                        children: [
                          repeat,
                          const SizedBox(height: 16),
                          preference,
                        ],
                      ),
                    ),
                  ],
                )
              else ...[
                basic,
                const SizedBox(height: 12),
                repeat,
                const SizedBox(height: 12),
                preference,
              ],
              const SizedBox(height: 16),
              PrimaryButton(
                widget.rule == null ? '반복 일정 저장하기' : '반복 일정 수정하기',
                onPressed: _save,
              ),
              const SizedBox(height: 24),
            ],
          );
        },
      ),
    ),
  );

  Widget _basicCard(BuildContext context) => InfoCard(
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text('일정 정보', style: Theme.of(context).textTheme.titleMedium),
        const SizedBox(height: 16),
        _field('일정 제목', title, key: const Key('repeatTitleField')),
        PlaceSearchField(
          key: const Key('repeatPlaceField'),
          label: '도착지',
          value: destination,
          searchService: widget.placeSearchService,
          onSelected: (value) => setState(() => destination = value),
        ),
        _field(
          '반드시 도착해야 하는 시간',
          time,
          key: const Key('repeatTimeField'),
          icon: Icons.schedule,
          timeField: true,
        ),
        PlaceSearchField(
          key: const Key('repeatDepartureField'),
          label: '출발지',
          value: origin,
          searchService: widget.placeSearchService,
          onSelected: (value) => setState(() => origin = value),
        ),
      ],
    ),
  );

  Widget _repeatCard(BuildContext context) => InfoCard(
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text('반복 설정', style: Theme.of(context).textTheme.titleMedium),
        const SizedBox(height: 16),
        const Text('반복 요일', style: TextStyle(fontWeight: FontWeight.w700)),
        const SizedBox(height: 8),
        Wrap(
          spacing: 6,
          runSpacing: 6,
          children: List.generate(7, (index) {
            final weekday = index + 1;
            return FilterChip(
              key: Key('repeatWeekday$weekday'),
              label: Text(weekdayLabels[index]),
              selected: weekdays.contains(weekday),
              onSelected: (selected) => setState(() {
                selected ? weekdays.add(weekday) : weekdays.remove(weekday);
              }),
            );
          }),
        ),
        if (weekdays.isEmpty) ...[
          const SizedBox(height: 8),
          const Text(
            '반복할 요일을 하나 이상 선택해주세요.',
            style: TextStyle(color: DdubiColors.red, fontSize: 12),
          ),
        ],
        const SizedBox(height: 18),
        _dateRow(
          context,
          label: '시작일',
          date: startDate,
          key: const Key('repeatStartDateButton'),
          onPressed: () => _pickStartDate(context),
        ),
        const SizedBox(height: 14),
        SwitchListTile(
          contentPadding: EdgeInsets.zero,
          title: const Text('종료일 설정'),
          subtitle: Text(hasEndDate ? formatFullDate(endDate) : '종료일 없음'),
          value: hasEndDate,
          onChanged: (value) => setState(() => hasEndDate = value),
        ),
        if (hasEndDate)
          _dateRow(
            context,
            label: '종료일',
            date: endDate,
            key: const Key('repeatEndDateButton'),
            onPressed: () => _pickEndDate(context),
          ),
      ],
    ),
  );

  Widget _preferenceCard(BuildContext context) => InfoCard(
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text('이동 설정', style: Theme.of(context).textTheme.titleMedium),
        const SizedBox(height: 16),
        const Text('중요도', style: TextStyle(fontWeight: FontWeight.w700)),
        const SizedBox(height: 8),
        SegmentedButton<ScheduleImportance>(
          segments: const [
            ButtonSegment(value: ScheduleImportance.normal, label: Text('일반')),
            ButtonSegment(value: ScheduleImportance.high, label: Text('중요')),
            ButtonSegment(
              value: ScheduleImportance.critical,
              label: Text('절대 지각 불가'),
            ),
          ],
          selected: {importance},
          onSelectionChanged: (value) =>
              setState(() => importance = value.first),
        ),
        const SizedBox(height: 12),
        SwitchListTile(
          key: const Key('repeatConsiderLastTripSwitch'),
          contentPadding: EdgeInsets.zero,
          title: const Text('막차 고려'),
          subtitle: const Text('늦은 반복 일정의 막차 위험을 함께 확인해요.'),
          value: considerLastTrip,
          onChanged: (value) => setState(() => considerLastTrip = value),
        ),
        const SizedBox(height: 18),
        const Text('교통수단 선호', style: TextStyle(fontWeight: FontWeight.w700)),
        TransportPreferenceSelector(
          value: transport,
          onChanged: (value) => setState(() => transport = value),
        ),
      ],
    ),
  );

  Widget _field(
    String label,
    TextEditingController controller, {
    required Key key,
    IconData? icon,
    bool timeField = false,
  }) => Padding(
    padding: const EdgeInsets.only(bottom: 15),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(label, style: const TextStyle(fontWeight: FontWeight.w700)),
        const SizedBox(height: 6),
        TextFormField(
          key: key,
          controller: controller,
          keyboardType: timeField ? TextInputType.datetime : TextInputType.text,
          validator: (value) {
            final text = (value ?? '').trim();
            if (text.isEmpty) return '필수 입력 항목입니다.';
            if (timeField &&
                !RegExp(r'^([01]\d|2[0-3]):[0-5]\d$').hasMatch(text)) {
              return '시간을 HH:mm 형식으로 입력해주세요.';
            }
            return null;
          },
          decoration: InputDecoration(
            suffixIcon: icon == null ? null : Icon(icon),
          ),
        ),
      ],
    ),
  );

  Widget _dateRow(
    BuildContext context, {
    required String label,
    required DateTime date,
    required Key key,
    required VoidCallback onPressed,
  }) => Row(
    children: [
      Expanded(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(label, style: const TextStyle(fontWeight: FontWeight.w700)),
            Text(formatFullDate(date)),
          ],
        ),
      ),
      OutlinedButton.icon(
        key: key,
        onPressed: onPressed,
        icon: const Icon(Icons.calendar_month_outlined),
        label: const Text('변경'),
      ),
    ],
  );

  Future<void> _pickStartDate(BuildContext context) async {
    final selected = await showDatePicker(
      context: context,
      initialDate: startDate,
      firstDate: DateTime.now().subtract(const Duration(days: 1)),
      lastDate: DateTime.now().add(const Duration(days: 365 * 2)),
    );
    if (selected == null || !mounted) return;
    setState(() {
      startDate = selected;
      if (endDate.isBefore(startDate)) {
        endDate = startDate.add(const Duration(days: 84));
      }
    });
  }

  Future<void> _pickEndDate(BuildContext context) async {
    final selected = await showDatePicker(
      context: context,
      initialDate: endDate.isBefore(startDate) ? startDate : endDate,
      firstDate: startDate,
      lastDate: startDate.add(const Duration(days: 365 * 2)),
    );
    if (selected != null && mounted) setState(() => endDate = selected);
  }

  Future<void> _save() async {
    setState(() {});
    if (weekdays.isEmpty ||
        origin == null ||
        destination == null ||
        !(formKey.currentState?.validate() ?? false)) {
      return;
    }
    try {
      final rule = RepeatScheduleRule(
        id:
            widget.rule?.id ??
            'repeat-${DateTime.now().microsecondsSinceEpoch}',
        title: title.text.trim(),
        origin: origin!,
        destination: destination!,
        time: time.text.trim(),
        startDate: startDate,
        weekdays: {...weekdays},
        untilDate: hasEndDate ? endDate : null,
        importance: importance,
        considerLastTrip: considerLastTrip,
        preferredTransport: transport,
      );
      if (widget.rule == null) {
        await widget.repository.addRepeatSchedule(rule);
      } else {
        await widget.repository.updateRepeatSchedule(rule);
      }
      if (mounted) context.pop(true);
    } on Object {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('반복 일정을 저장하지 못했어요. 연결 상태를 확인해주세요.')),
      );
    }
  }
}

class _ScheduleFormScreenState extends State<ScheduleFormScreen> {
  late ScheduleImportance importance;
  TransportPreferenceChoice transport = TransportPreferenceChoice.balanced;
  late bool considerLastTrip;
  late DateTime departureDate;
  late DateTime arrivalDate;
  late TimeOfDay departureTime;
  late TimeOfDay arrivalTime;
  late bool departureConfirmed;
  final formKey = GlobalKey<FormState>();
  late final TextEditingController title;
  PlaceLocation? origin;
  PlaceLocation? destination;

  @override
  void initState() {
    super.initState();
    final schedule = widget.schedule;
    title = TextEditingController(text: schedule?.title ?? '');
    final arriveBy =
        schedule?.arriveBy ??
        DateTime(
          (widget.initialDate ?? DateTime.now()).year,
          (widget.initialDate ?? DateTime.now()).month,
          (widget.initialDate ?? DateTime.now()).day,
          19,
          30,
        );
    final departAt =
        schedule?.plannedDepartAt ??
        DateTime.now().add(const Duration(minutes: 30));
    departureConfirmed = schedule?.plannedDepartAt != null;
    arrivalDate = arriveBy;
    arrivalTime = TimeOfDay.fromDateTime(arriveBy);
    departureDate = departAt;
    departureTime = TimeOfDay.fromDateTime(departAt);
    origin = schedule?.origin;
    destination = schedule?.destination;
    importance = schedule?.importance ?? ScheduleImportance.high;
    considerLastTrip = schedule?.considerLastTrip ?? false;
    transport =
        schedule?.preferredTransport ?? TransportPreferenceChoice.balanced;
  }

  @override
  void dispose() {
    title.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    backgroundColor: const Color(0xFFF2FBF9),
    appBar: AppBar(
      backgroundColor: const Color(0xFFF2FBF9),
      surfaceTintColor: Colors.transparent,
      centerTitle: true,
      leading: IconButton(
        key: const Key('scheduleFormBackButton'),
        tooltip: '뒤로가기',
        onPressed: () => _leaveForm(context),
        icon: const Icon(
          Icons.arrow_back_ios_new_rounded,
          color: DdubiColors.navy,
          size: 22,
        ),
      ),
      title: Text(widget.schedule == null ? '일정 등록' : '일정 수정'),
    ),
    body: SafeArea(
      top: false,
      child: Form(
        key: formKey,
        child: ListView(
          key: const Key('scheduleFormScrollView'),
          padding: const EdgeInsets.fromLTRB(16, 4, 16, 28),
          children: [
            _hero(),
            const SizedBox(height: 14),
            Container(
              padding: const EdgeInsets.fromLTRB(16, 20, 16, 18),
              decoration: BoxDecoration(
                color: Colors.white,
                borderRadius: BorderRadius.circular(28),
                boxShadow: const [
                  BoxShadow(
                    color: Color(0x12004F4A),
                    blurRadius: 24,
                    offset: Offset(0, 8),
                  ),
                ],
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  _field('일정 제목', title, icon: Icons.edit_calendar_rounded),
                  PlaceSearchField(
                    key: const Key('originPlaceField'),
                    label: '출발지',
                    value: origin,
                    searchService: widget.placeSearchService,
                    onSelected: (value) => setState(() => origin = value),
                  ),
                  _dateTimeSection(
                    label: '출발 예정 시간',
                    icon: Icons.schedule_rounded,
                    date: departureDate,
                    time: departureTime,
                    dateKey: const Key('scheduleDepartureDateButton'),
                    timeKey: const Key('scheduleDepartureTimeButton'),
                    onDatePressed: () => _pickDate(context, arrival: false),
                    onTimePressed: () => _pickTime(context, arrival: false),
                  ),
                  if (!departureConfirmed)
                    const Padding(
                      padding: EdgeInsets.only(top: 8),
                      child: Text(
                        '자동으로 표시된 시각입니다. 출발할 날짜와 시간을 직접 확인해 주세요.',
                        style: TextStyle(color: DdubiColors.red, fontSize: 12),
                      ),
                    ),
                  const SizedBox(height: 15),
                  PlaceSearchField(
                    key: const Key('destinationPlaceField'),
                    label: '도착지',
                    value: destination,
                    searchService: widget.placeSearchService,
                    onSelected: (value) => setState(() => destination = value),
                  ),
                  _dateTimeSection(
                    label: '도착 희망 시간',
                    icon: Icons.flag_rounded,
                    date: arrivalDate,
                    time: arrivalTime,
                    dateKey: const Key('scheduleDateButton'),
                    timeKey: const Key('scheduleArrivalTimeButton'),
                    onDatePressed: () => _pickDate(context, arrival: true),
                    onTimePressed: () => _pickTime(context, arrival: true),
                  ),
                ],
              ),
            ),
            const SizedBox(height: 14),
            _lastTripCard(),
            const SizedBox(height: 12),
            _infoCard(),
            const SizedBox(height: 16),
            SizedBox(
              height: 56,
              child: PrimaryButton(
                widget.schedule == null ? '저장하기' : '수정 완료',
                onPressed: _save,
              ),
            ),
          ],
        ),
      ),
    ),
  );

  Widget _hero() => Container(
    height: 210,
    clipBehavior: Clip.antiAlias,
    decoration: BoxDecoration(
      gradient: const LinearGradient(
        colors: [Color(0xFFE6FBF6), Color(0xFFD6F5F1)],
        begin: Alignment.topLeft,
        end: Alignment.bottomRight,
      ),
      borderRadius: BorderRadius.circular(28),
    ),
    child: LayoutBuilder(
      builder: (context, constraints) => Stack(
        children: [
          const Positioned(
            left: -22,
            bottom: -32,
            child: _ScheduleHeroBubble(size: 110),
          ),
          const Positioned(
            right: 78,
            top: 18,
            child: _ScheduleHeroBubble(size: 48),
          ),
          Positioned(
            left: 20,
            top: 24,
            width: constraints.maxWidth * .59,
            child: const Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  '어디서 출발해서\n어디로 가시나요?',
                  style: TextStyle(
                    color: DdubiColors.navy,
                    fontSize: 24,
                    height: 1.25,
                    fontWeight: FontWeight.w900,
                    letterSpacing: -.7,
                  ),
                ),
                SizedBox(height: 12),
                Text(
                  '뚜비가 가장 좋은 출발 시간을\n찾도록 도와드릴게요!',
                  style: TextStyle(
                    color: Color(0xFF587086),
                    fontSize: 13,
                    height: 1.45,
                    fontWeight: FontWeight.w600,
                  ),
                ),
              ],
            ),
          ),
          Positioned(
            right: -8,
            bottom: -2,
            width: constraints.maxWidth * .43,
            height: 170,
            child: Image.asset(
              'assets/images/ttubee/intro-calendar-clock.png',
              fit: BoxFit.contain,
              filterQuality: FilterQuality.high,
            ),
          ),
        ],
      ),
    ),
  );

  Future<void> _leaveForm(BuildContext context) async {
    FocusScope.of(context).unfocus();
    if (await Navigator.of(context).maybePop()) return;
    if (!context.mounted) return;
    GoRouter.maybeOf(context)?.go('/schedules');
  }

  Widget _lastTripCard() => Container(
    decoration: BoxDecoration(
      color: const Color(0xFFE4F8F4),
      borderRadius: BorderRadius.circular(20),
      border: Border.all(color: const Color(0xFFC2EEE5)),
    ),
    child: Material(
      color: Colors.transparent,
      borderRadius: BorderRadius.circular(20),
      clipBehavior: Clip.antiAlias,
      child: SwitchListTile(
        key: const Key('considerLastTripSwitch'),
        contentPadding: const EdgeInsets.fromLTRB(16, 6, 12, 6),
        secondary: const CircleAvatar(
          radius: 22,
          backgroundColor: DdubiColors.green,
          child: Icon(Icons.directions_bus_rounded, color: Colors.white),
        ),
        title: const Text(
          '막차 고려',
          style: TextStyle(
            color: DdubiColors.navy,
            fontWeight: FontWeight.w900,
          ),
        ),
        subtitle: const Text(
          '늦은 일정의 막차 위험을 함께 확인해요.',
          style: TextStyle(color: Color(0xFF60778A), fontSize: 12),
        ),
        value: considerLastTrip,
        onChanged: (value) => setState(() => considerLastTrip = value),
      ),
    ),
  );

  Widget _infoCard() => Container(
    height: 104,
    clipBehavior: Clip.antiAlias,
    padding: const EdgeInsets.fromLTRB(16, 14, 2, 12),
    decoration: BoxDecoration(
      color: const Color(0xFFECFAF7),
      borderRadius: BorderRadius.circular(20),
      border: Border.all(color: const Color(0xFFCBEFE8)),
    ),
    child: Row(
      children: [
        Container(
          width: 38,
          height: 38,
          decoration: const BoxDecoration(
            color: Color(0xFFFFEAA0),
            shape: BoxShape.circle,
          ),
          child: const Icon(
            Icons.lightbulb_rounded,
            color: Color(0xFFFFB800),
            size: 24,
          ),
        ),
        const SizedBox(width: 11),
        const Expanded(
          child: Text(
            '도착 희망 시간까지 갈 수 있도록\n교통 상황을 고려해 최적의\n출발 시간을 추천해드려요!',
            style: TextStyle(
              color: DdubiColors.navy,
              fontSize: 12,
              height: 1.45,
              fontWeight: FontWeight.w700,
            ),
          ),
        ),
        SizedBox(
          width: 78,
          height: 88,
          child: Image.asset(
            'assets/images/ttubee/welcome.png',
            fit: BoxFit.contain,
            alignment: Alignment.bottomCenter,
            filterQuality: FilterQuality.high,
          ),
        ),
      ],
    ),
  );

  Future<void> _save() async {
    if (!(formKey.currentState?.validate() ?? false)) return;
    if (origin == null || destination == null) return;
    if (!departureConfirmed) {
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('출발 예정시각을 직접 확인해 주세요.')));
      return;
    }
    final plannedDeparture = DateTime(
      departureDate.year,
      departureDate.month,
      departureDate.day,
      departureTime.hour,
      departureTime.minute,
    );
    final arriveBy = DateTime(
      arrivalDate.year,
      arrivalDate.month,
      arrivalDate.day,
      arrivalTime.hour,
      arrivalTime.minute,
    );
    if (!plannedDeparture.isBefore(arriveBy)) {
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('출발시각은 도착시각보다 앞서야 합니다.')));
      return;
    }
    final schedule = Schedule(
      id:
          widget.schedule?.id ??
          'schedule-${DateTime.now().microsecondsSinceEpoch}',
      title: title.text.trim(),
      origin: origin!,
      destination: destination!,
      arriveBy: arriveBy,
      plannedDepartAt: plannedDeparture,
      importance: importance,
      considerLastTrip: considerLastTrip,
      preferredTransport: transport,
      repeatLabel: widget.schedule?.repeatLabel,
      updatedAt: widget.schedule?.updatedAt,
    );
    try {
      if (widget.schedule == null) {
        await widget.repository.addSchedule(schedule);
      } else {
        await widget.repository.updateSchedule(schedule);
      }
      if (mounted) context.pop(true);
    } on ScheduleConflictException {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('다른 기기에서 일정이 변경됐어요. 새로 고침 후 다시 시도해주세요.')),
      );
    } on Object {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(
            widget.schedule == null
                ? '일정을 저장하지 못했어요. 입력값과 연결 상태를 확인해주세요.'
                : '일정을 수정하지 못했어요. 잠시 후 다시 시도해주세요.',
          ),
        ),
      );
    }
  }

  Future<void> _pickDate(BuildContext context, {required bool arrival}) async {
    final initialDate = arrival ? arrivalDate : departureDate;
    final selected = await showDatePicker(
      context: context,
      initialDate: initialDate,
      firstDate: DateTime.now().subtract(const Duration(days: 1)),
      lastDate: DateTime.now().add(const Duration(days: 365 * 2)),
    );
    if (selected == null || !mounted) return;
    setState(() {
      if (arrival) {
        arrivalDate = selected;
      } else {
        departureDate = selected;
        departureConfirmed = true;
      }
    });
  }

  Future<void> _pickTime(BuildContext context, {required bool arrival}) async {
    final selected = await showTimePicker(
      context: context,
      initialTime: arrival ? arrivalTime : departureTime,
    );
    if (selected == null || !mounted) return;
    setState(() {
      if (arrival) {
        arrivalTime = selected;
      } else {
        departureTime = selected;
        departureConfirmed = true;
      }
    });
  }

  Widget _field(
    String label,
    TextEditingController controller, {
    IconData? icon,
  }) => Padding(
    padding: const EdgeInsets.only(bottom: 15),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          label,
          style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 12),
        ),
        const SizedBox(height: 6),
        TextFormField(
          controller: controller,
          validator: (value) {
            final text = (value ?? '').trim();
            if (text.isEmpty) return '필수 입력 항목입니다.';
            return null;
          },
          decoration: InputDecoration(
            filled: true,
            fillColor: const Color(0xFFFAFCFD),
            prefixIcon: icon == null
                ? null
                : Icon(icon, color: DdubiColors.green),
            suffixIcon: controller.text.isEmpty
                ? null
                : IconButton(
                    tooltip: '입력값 지우기',
                    onPressed: () => setState(controller.clear),
                    icon: const Icon(Icons.cancel_rounded, size: 20),
                  ),
            border: OutlineInputBorder(
              borderRadius: BorderRadius.circular(14),
              borderSide: const BorderSide(color: Color(0xFFD7E4E8)),
            ),
          ),
        ),
      ],
    ),
  );

  Widget _dateTimeSection({
    required String label,
    required IconData icon,
    required DateTime date,
    required TimeOfDay time,
    required Key dateKey,
    required Key timeKey,
    required VoidCallback onDatePressed,
    required VoidCallback onTimePressed,
  }) => Column(
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      Row(
        children: [
          Icon(icon, color: DdubiColors.green, size: 20),
          const SizedBox(width: 7),
          Text(
            label,
            style: const TextStyle(
              color: DdubiColors.navy,
              fontSize: 13,
              fontWeight: FontWeight.w900,
            ),
          ),
        ],
      ),
      const SizedBox(height: 8),
      Row(
        children: [
          Expanded(
            flex: 6,
            child: _dateTimeButton(
              key: dateKey,
              icon: Icons.calendar_month_rounded,
              text: _scheduleDateLabel(date),
              onPressed: onDatePressed,
            ),
          ),
          const SizedBox(width: 8),
          Expanded(
            flex: 4,
            child: _dateTimeButton(
              key: timeKey,
              icon: Icons.access_time_filled_rounded,
              text: _scheduleTimeLabel(time),
              onPressed: onTimePressed,
            ),
          ),
        ],
      ),
    ],
  );

  Widget _dateTimeButton({
    required Key key,
    required IconData icon,
    required String text,
    required VoidCallback onPressed,
  }) => InkWell(
    key: key,
    borderRadius: BorderRadius.circular(14),
    onTap: onPressed,
    child: Container(
      height: 52,
      padding: const EdgeInsets.symmetric(horizontal: 11),
      decoration: BoxDecoration(
        color: const Color(0xFFFAFCFD),
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: const Color(0xFFD7E4E8)),
      ),
      child: Row(
        children: [
          Icon(icon, color: const Color(0xFF365976), size: 20),
          const SizedBox(width: 7),
          Expanded(
            child: Text(
              text,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: const TextStyle(
                color: DdubiColors.navy,
                fontSize: 12,
                fontWeight: FontWeight.w700,
              ),
            ),
          ),
          const Icon(Icons.keyboard_arrow_down_rounded, size: 18),
        ],
      ),
    ),
  );

  String _scheduleDateLabel(DateTime date) {
    const weekdays = ['월', '화', '수', '목', '금', '토', '일'];
    return '${date.year}.${date.month}.${date.day} (${weekdays[date.weekday - 1]})';
  }

  String _scheduleTimeLabel(TimeOfDay time) {
    final period = time.hour < 12 ? '오전' : '오후';
    final hour = time.hourOfPeriod == 0 ? 12 : time.hourOfPeriod;
    return '$period $hour:${time.minute.toString().padLeft(2, '0')}';
  }
}

class _ScheduleHeroBubble extends StatelessWidget {
  const _ScheduleHeroBubble({required this.size});

  final double size;

  @override
  Widget build(BuildContext context) => Container(
    width: size,
    height: size,
    decoration: BoxDecoration(
      color: Colors.white.withValues(alpha: .38),
      shape: BoxShape.circle,
    ),
  );
}
