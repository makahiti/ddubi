/// Server-wide inventory only. It is never evidence that a route used this data.
class TransitReferenceState {
  const TransitReferenceState({
    required this.status,
    this.limitations = const [],
    this.sampleReferenceIds = const [],
  });

  final String status;
  final List<String> limitations;
  final List<String> sampleReferenceIds;

  factory TransitReferenceState.fromJson(Object? value) {
    if (value is! Map) return const TransitReferenceState(status: 'UNKNOWN');
    final status = value['status'];
    final limitations = value['limitations'];
    final samples = value['sampleReferenceIds'];
    return TransitReferenceState(
      status: status is String ? status : 'UNKNOWN',
      limitations: limitations is List
          ? limitations.whereType<String>().toList(growable: false)
          : const [],
      sampleReferenceIds: samples is List
          ? samples
                .whereType<String>()
                .where((x) => x.isNotEmpty && x.length <= 200)
                .toSet()
                .take(3)
                .toList(growable: false)
          : const [],
    );
  }
}

class TransitReferenceStatus {
  const TransitReferenceStatus({
    required this.bus,
    required this.subway,
    required this.road,
    this.checkedAt,
  });

  final TransitReferenceState bus;
  final TransitReferenceState subway;
  final TransitReferenceState road;
  final DateTime? checkedAt;

  factory TransitReferenceStatus.fromJson(Map<String, dynamic> json) =>
      TransitReferenceStatus(
        bus: TransitReferenceState.fromJson(json['busReference']),
        subway: TransitReferenceState.fromJson(json['subwayReference']),
        road: TransitReferenceState.fromJson(json['roadReference']),
        checkedAt: json['checkedAt'] is String
            ? DateTime.tryParse(json['checkedAt'] as String)
            : null,
      );
}

abstract interface class TransitReferenceRepository {
  Future<TransitReferenceStatus> transitReferenceStatus();
  Future<TransitReferenceDetail> transitReferenceDetail(
    String kind,
    String referenceId,
  );
}

class TransitReferenceDetail {
  const TransitReferenceDetail({
    required this.referenceId,
    required this.label,
    required this.recordType,
    required this.kind,
    required this.data,
    required this.limitations,
    this.sourceMasterVersion,
    this.sourceVersion,
  });
  final String referenceId;
  final String label;
  final String recordType;
  final String kind;
  final Map<String, dynamic> data;
  final List<String> limitations;
  final String? sourceMasterVersion;
  final String? sourceVersion;

  factory TransitReferenceDetail.fromJson(
    Map<String, dynamic> json,
    String expectedId,
  ) {
    final detail = json['detail'];
    if (json['status'] != 'STAGED_REFERENCE_NOT_APPROVED' ||
        json['referenceId'] != expectedId ||
        json['referenceOnly'] != true ||
        json['productionReady'] != false ||
        json['realtimeApplied'] != false ||
        detail is! Map ||
        detail['referenceId'] != expectedId ||
        detail['data'] is! Map) {
      throw const TransitReferenceFailure('조회 가능한 검토용 참고자료가 없습니다.');
    }
    return TransitReferenceDetail(
      referenceId: expectedId,
      label: detail['label'] is String ? detail['label'] as String : '보유 참고자료',
      recordType: detail['recordType'] is String
          ? detail['recordType'] as String
          : 'UNKNOWN',
      kind: detail['kind'] is String ? detail['kind'] as String : 'UNKNOWN',
      data: (detail['data'] as Map).map(
        (key, value) => MapEntry(key.toString(), value),
      ),
      limitations: [
        if (json['limitations'] is List)
          ...(json['limitations'] as List).whereType<String>(),
        if (detail['limitations'] is List)
          ...(detail['limitations'] as List).whereType<String>(),
      ],
      sourceMasterVersion: detail['sourceMasterVersion'] is String
          ? detail['sourceMasterVersion'] as String
          : null,
      sourceVersion: json['sourceVersion'] is String
          ? json['sourceVersion'] as String
          : null,
    );
  }
}

class TransitReferenceFailure implements Exception {
  const TransitReferenceFailure(this.message);
  final String message;
}
