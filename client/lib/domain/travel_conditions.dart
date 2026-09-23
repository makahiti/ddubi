import 'package:flutter/foundation.dart';

/// 일정 하나에 대한 "오늘의 이동 변수".
///
/// 네 칸(날씨·혼잡도·도로 통제·지하철)이 **각각 따로** 값 있음/없음을 들고 있다.
/// 하나로 묶으면 둘 중 하나가 된다 — 날씨만 들어와도 네 칸이 모두 "확인 안 됨"이 되거나,
/// 반대로 빈 칸을 0 이나 "맑음" 으로 채워 사용자가 없는 정보를 믿게 된다. 둘 다 안 된다.
///
/// 서버의 일정별 conditions API와 연결된다. `available=false`는 정상 상태가 아니라
/// 해당 경로에 연결할 수 있는 유효한 관측이 없다는 뜻이며 reason을 보존해 화면에 설명한다.
class TravelConditions {
  const TravelConditions({
    this.weather = const TravelConditionItem.unavailable(),
    this.congestion = const TravelConditionItem.unavailable(),
    this.roadBlock = const TravelConditionItem.unavailable(),
    this.subwayDelay = const TravelConditionItem.unavailable(),
    this.observedAt,
  });

  /// 아직 조회하지 못한 상태. 정상/통제 없음으로 추정하지 않는다.
  static const TravelConditions unknown = TravelConditions();

  final TravelConditionItem weather;
  final TravelConditionItem congestion;
  final TravelConditionItem roadBlock;
  final TravelConditionItem subwayDelay;

  /// 서버가 값을 관측한 시각. 앱이 받은 시각이 아니다.
  final DateTime? observedAt;

  /// 네 칸 중 하나라도 값이 있으면 참.
  bool get hasAny =>
      weather.available ||
      congestion.available ||
      roadBlock.available ||
      subwayDelay.available;

  /// 서버 응답(movement-conditions-v1)을 화면에 쓸 문장으로 바꾼다.
  ///
  /// 서버는 숫자와 코드로 준다. 문장 만들기는 앱이 한다 — 서버가 "맑음" 같은 말을
  /// 만들어 보내면 값이 없을 때도 그럴듯한 문장이 생길 위험이 있어서다.
  /// 계약: docs/contracts/movement-conditions.md
  factory TravelConditions.fromJson(Object? value) {
    if (value is! Map) return unknown;
    return TravelConditions(
      weather: _weather(value['weather']),
      congestion: _congestion(value['congestion']),
      roadBlock: _roadBlock(value['roadBlock']),
      subwayDelay: _subwayDelay(value['subwayDelay']),
      observedAt: _parseTime(value['observedAt']),
    );
  }

  static DateTime? _parseTime(Object? value) =>
      value is String ? DateTime.tryParse(value)?.toLocal() : null;

  /// 값이 없을 때 서버가 준 사유 코드. 화면에 코드를 그대로 쓰지 않고 말을 고르는 데만 쓴다.
  static String? _reason(Object? value) {
    if (value is! Map) return null;
    final reason = value['reason'];
    return reason is String ? reason : null;
  }

  /// 항목이 쓸 수 있는 상태인지. `available` 이 참이어야만 값을 읽는다.
  static Map<String, Object?>? _usable(Object? value) {
    if (value is! Map) return null;
    if (value['available'] != true) return null;
    return value.map((key, item) => MapEntry(key.toString(), item));
  }

  static TravelConditionItem _unavailable(Object? value) {
    final reason = value is Map ? value['reason']?.toString() : null;
    final note = switch (reason) {
      'NO_BUS_SEGMENT' => '선택 경로에 버스 구간이 없어요.',
      'ROAD_PATH_UNMAPPED' => '버스 구간과 도로 위치가 아직 연결되지 않았어요.',
      'NO_ACTIVE_ROAD_CONTROL' => '선택 경로에 연결된 유효한 통제 정보가 없어요.',
      'NO_SUBWAY_SEGMENT' => '선택 경로에 지하철 구간이 없어요.',
      'NO_MATCHING_ACTIVE_ALERT' => '선택한 호선에 연결된 유효한 운행 공지가 없어요.',
      'CONGESTION_UNAVAILABLE_OR_STALE' => '연결된 혼잡도 자료가 없거나 오래됐어요.',
      'WEATHER_NOT_FOUND' || 'WEATHER_STALE' => '현재 유효한 날씨 자료가 없어요.',
      'WEATHER_READ_FAILED' ||
      'CONGESTION_READ_FAILED' ||
      'ROAD_CONTROL_READ_FAILED' ||
      'SUBWAY_ALERT_READ_FAILED' => '자료 조회가 일시적으로 실패했어요.',
      _ => null,
    };
    return TravelConditionItem.unavailable(note: note);
  }

  static double? _number(Object? value) =>
      value is num ? value.toDouble() : null;

  /// 예: `20.9℃ · 비 안 옴` / `18.0℃ · 비 2.5mm`
  ///
  /// 기온이 없으면 칸을 비운다. 강수량만으로는 "지금 어떤지" 를 말할 수 없다.
  static TravelConditionItem _weather(Object? value) {
    final data = _usable(value);
    if (data == null) return _unavailable(value);
    final temperature = _number(data['temperatureC']);
    if (temperature == null) return const TravelConditionItem.unavailable();
    final rain = _number(data['precipitationMm']);
    final text = StringBuffer('${temperature.toStringAsFixed(1)}℃');
    if (rain != null) {
      // 0.0mm 은 "관측했고 비가 안 온다" 는 뜻이다. 값이 아예 없는 것과 다르다.
      text.write(rain > 0 ? ' · 비 ${rain.toStringAsFixed(1)}mm' : ' · 비 안 옴');
    }
    final districts = data['districts'];
    final district = districts is List
        ? districts.whereType<String>().take(2).join('·')
        : null;
    return TravelConditionItem.value(
      text.toString(),
      note: district != null && district.isNotEmpty ? '$district 기준' : null,
    );
  }

  /// 예: `시청 일대 붐빔`
  static TravelConditionItem _congestion(Object? value) {
    final data = _usable(value);
    if (data == null) {
      // 혼잡도는 버스가 지나는 곳 기준으로만 계산한다. 지하철만 타는 경로면 확인 범위 밖이다.
      return _reason(value) == 'NO_BUS_SEGMENT'
          ? const TravelConditionItem.checked('해당 없음', note: '버스 구간 기준으로만 확인해요')
          : _unavailable(value);
    }
    final area = data['areaName'];
    final level = congestionLabel(data['level']);
    if (area is! String || area.isEmpty || level == null) {
      return const TravelConditionItem.unavailable();
    }
    final message = data['message'];
    final note = message is String && message.isNotEmpty ? message : null;
    // 서버가 stale 을 주면 유효시각이 지난 관측이다. 값을 감추지 않고 언제 기준인지 밝힌다.
    final observedAt = _parseTime(data['observedAt']);
    if (data['stale'] == true && observedAt != null) {
      final basis =
          '${_two(observedAt.hour)}:${_two(observedAt.minute)} 기준 · 최신 아님';
      return TravelConditionItem.value(
        '$area 일대 $level',
        note: note == null ? basis : '$note · $basis',
      );
    }
    return TravelConditionItem.value('$area 일대 $level', note: note);
  }

  static String _two(int value) => value.toString().padLeft(2, '0');

  /// 서울시 생활인구 혼잡도 등급. 모르는 등급이면 영어 코드를 그대로 내보내지 않는다.
  @visibleForTesting
  static String? congestionLabel(Object? level) => switch (level) {
    'RELAXED' => '여유',
    'NORMAL' => '보통',
    'BUSY' => '붐빔',
    'CROWDED' => '매우 붐빔',
    _ => null,
  };

  static TravelConditionItem _roadBlock(Object? value) {
    final data = _usable(value);
    if (data == null) {
      return switch (_reason(value)) {
        // 이 경로의 도로를 살펴봤고 걸린 통제가 없다.
        'NO_ACTIVE_ROAD_CONTROL' => const TravelConditionItem.checked(
          '확인된 통제 없음',
        ),
        'NO_BUS_SEGMENT' => const TravelConditionItem.checked(
          '해당 없음',
          note: '버스로 가는 구간이 없어요',
        ),
        _ => _unavailable(value),
      };
    }
    final summary = data['summary'];
    if (summary is! String || summary.isEmpty) {
      return const TravelConditionItem.unavailable();
    }
    // 발표용 가짜 통제. 실제 사고로 오해하지 않게 덧말로 한 번 더 밝힌다.
    if (data['source'] == 'DEMO_TOPIS') {
      return TravelConditionItem.value(summary, note: '시연용 가상 통제');
    }
    final count = data['count'];
    final provisional = data['mappingStatus'] == 'PROVISIONAL';
    return TravelConditionItem.value(
      summary,
      note: provisional
          ? (count is int && count > 1 ? '후보 매핑 · 돌발 $count건' : '후보 매핑 · 추정 정보')
          : (count is int && count > 1 ? '통제 $count건' : null),
    );
  }

  static TravelConditionItem _subwayDelay(Object? value) {
    final data = _usable(value);
    if (data == null) {
      return switch (_reason(value)) {
        // 탈 호선의 공지를 살펴봤고 걸린 공지가 없다. "지연 없음" 이라고 단정하지는 않는다.
        'NO_MATCHING_ACTIVE_ALERT' => const TravelConditionItem.checked(
          '확인된 공지 없음',
        ),
        'NO_SUBWAY_SEGMENT' => const TravelConditionItem.checked(
          '해당 없음',
          note: '지하철로 가는 구간이 없어요',
        ),
        _ => _unavailable(value),
      };
    }
    final summary = data['summary'];
    if (summary is String && summary.isNotEmpty) {
      return TravelConditionItem.value(summary);
    }
    // summary 가 없으면 공지 제목을 쓴다. 제목도 없으면 값 없음이다.
    final alerts = data['alerts'];
    if (alerts is! List) return const TravelConditionItem.unavailable();
    final first = alerts.whereType<Map>().firstOrNull;
    final title = first?['title'];
    if (title is! String || title.isEmpty) {
      return const TravelConditionItem.unavailable();
    }
    final line = first?['lineName'];
    return TravelConditionItem.value(
      title,
      note: line is String && line.isNotEmpty ? line : null,
    );
  }
}

/// 이동 변수 한 칸.
///
/// [text] 가 null 이면 "확인 안 됨"이다. 빈 문자열도 값 없음으로 본다 — 서버가 실수로
/// 빈 문자열을 보냈을 때 화면에 빈칸이 덩그러니 남는 것보다 낫다.
class TravelConditionItem {
  const TravelConditionItem.value(String this.text, {this.note})
    : checkedEmpty = null;

  const TravelConditionItem.unavailable({this.note})
    : text = null,
      checkedEmpty = null;

  /// 확인은 했는데 보여줄 값이 없는 칸. "확인된 통제 없음", "해당 없음" 처럼 적는다.
  ///
  /// "확인 안 됨" 과 섞으면 안 된다(2026-09-24 제보). 경로의 도로를 다 살펴봤는데 통제가 없는 것과,
  /// 자료가 없어 살펴보지 못한 것은 사용자에게 다른 말이다. 다만 "정상", "지연 없음" 처럼 단정하지는 않는다 —
  /// 수집되지 않은 사건이 있을 수 있다.
  const TravelConditionItem.checked(String this.checkedEmpty, {this.note})
    : text = null;

  /// 사용자에게 보여줄 값. 예: `20.9℃ · 비 안 옴`
  final String? text;

  /// 값 아래 작게 붙는 덧말. 예: 어느 지역 기준인지
  final String? note;

  /// 확인했지만 값이 없을 때 칸에 적는 말. 값도 이것도 없으면 "확인 안 됨" 이다.
  final String? checkedEmpty;

  bool get available => text != null && text!.isNotEmpty;
}

/// 이동 변수를 가져오는 통로.
///
/// 일정별 conditions API의 응답을 읽는 통로다. 화면은 이 통로가 없어도 동작하지만
/// 그 경우에는 확인하지 못했다고 표시한다.
abstract class TravelConditionsService {
  Future<TravelConditions> conditionsFor(String scheduleId);
}
