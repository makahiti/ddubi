class ApiFieldError {
  const ApiFieldError({
    required this.field,
    required this.reason,
    this.rejectedValue,
  });

  final String field;
  final String reason;
  final Object? rejectedValue;

  factory ApiFieldError.fromJson(Map<String, dynamic> json) {
    return ApiFieldError(
      field: json['field'] as String? ?? '',
      reason: json['reason'] as String? ?? '',
      rejectedValue: json['rejectedValue'],
    );
  }
}

class ApiErrorResponse {
  const ApiErrorResponse({
    required this.code,
    required this.message,
    this.fieldErrors = const [],
    this.requestId,
    this.timestamp,
  });

  final String code;
  final String message;
  final List<ApiFieldError> fieldErrors;
  final String? requestId;
  final DateTime? timestamp;

  factory ApiErrorResponse.fromJson(Map<String, dynamic> json) {
    final error = json['error'] as Map<String, dynamic>? ?? const {};
    final rawFieldErrors = error['fieldErrors'];

    return ApiErrorResponse(
      code: error['code'] as String? ?? 'UNKNOWN_ERROR',
      message: error['message'] as String? ?? '요청을 처리하지 못했습니다.',
      fieldErrors: rawFieldErrors is List
          ? rawFieldErrors
                .whereType<Map<String, dynamic>>()
                .map(ApiFieldError.fromJson)
                .toList(growable: false)
          : const [],
      requestId: json['requestId'] as String?,
      timestamp: DateTime.tryParse(json['timestamp'] as String? ?? ''),
    );
  }
}
