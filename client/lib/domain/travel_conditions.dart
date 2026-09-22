import 'package:flutter/foundation.dart';

/// 일정 하나에 대한 "오늘의 이동 변수".
///
/// 네 칸(날씨·혼잡도·도로 통제·지하철)이 **각각 따로** 값 있음/없음을 들고 있다.
/// 하나로 묶으면 둘 중 하나가 된다 — 날씨만 들어와도 네 칸이 모두 "확인 안 됨"이 되거나,
/// 반대로 빈 칸을 0 이나 "맑음" 으로 채워 사용자가 없는 정보를 믿게 된다. 둘 다 안 된다.
///
/// 서버 조회 API 는 아직 없다(2026-09-22). 그때까지 이 화면은 네 칸을 모두 "확인 안 됨"
/// 으로 그린다. 예전처럼 한 줄로 뭉뚱그리면 무엇이 되고 무엇이 안 되는지 알 수 없었다.
class TravelConditions {
  const TravelConditions({
    this.weather = const TravelConditionItem.unavailable(),
    this.congestion = const TravelConditionItem.unavailable(),
    this.roadBlock = const TravelConditionItem.unavailable(),
    this.subwayDelay = const TravelConditionItem.unavailable(),
    this.observedAt,
  });

  /// 아무것도 확인되지 않은 상태. 조회 API 가 붙기 전의 기본값이다.
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

  /// 항목이 쓸 수 있는 상태인지. `available` 이 참이어야만 값을 읽는다.
  static Map<String, Object?>? _usable(Object? value) {
    if (value is! Map) return null;
    if (value['available'] != true) return null;
    return value.map((key, item) => MapEntry(key.toString(), item));
  }

  static double? _number(Object? value) =>
      value is num ? value.toDouble() : null;

  /// 예: `20.9℃ · 비 안 옴` / `18.0℃ · 비 2.5mm`
  ///
  /// 기온이 없으면 칸을 비운다. 강수량만으로는 "지금 어떤지" 를 말할 수 없다.
  static TravelConditionItem _weather(Object? value) {
    final data = _usable(value);
    if (data == null) return const TravelConditionItem.unavailable();
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
    if (data == null) return const TravelConditionItem.unavailable();
    final area = data['areaName'];
    final level = congestionLabel(data['level']);
    if (area is! String || area.isEmpty || level == null) {
      return const TravelConditionItem.unavailable();
    }
    final message = data['message'];
    return TravelConditionItem.value(
      '$area 일대 $level',
      note: message is String && message.isNotEmpty ? message : null,
    );
  }

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
    if (data == null) return const TravelConditionItem.unavailable();
    final summary = data['summary'];
    if (summary is! String || summary.isEmpty) {
      return const TravelConditionItem.unavailable();
    }
    final count = data['count'];
    return TravelConditionItem.value(
      summary,
      note: count is int && count > 1 ? '통제 $count건' : null,
    );
  }

  static TravelConditionItem _subwayDelay(Object? value) {
    final data = _usable(value);
    if (data == null) return const TravelConditionItem.unavailable();
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
  const TravelConditionItem.value(String this.text, {this.note});

  const TravelConditionItem.unavailable() : text = null, note = null;

  /// 사용자에게 보여줄 값. 예: `20.9℃ · 비 안 옴`
  final String? text;

  /// 값 아래 작게 붙는 덧말. 예: 어느 지역 기준인지
  final String? note;

  bool get available => text != null && text!.isNotEmpty;
}

/// 이동 변수를 가져오는 통로.
///
/// 서버 API 가 생기면 이 인터페이스만 구현해서 화면에 넘기면 된다. 화면은 이 통로가
/// 없어도(=null) 동작한다.
abstract class TravelConditionsService {
  Future<TravelConditions> conditionsFor(String scheduleId);
}
