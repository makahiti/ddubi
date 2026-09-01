import 'package:ddubi/core/constants/api_paths.dart';
import 'package:ddubi/core/network/api_error_response.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('리소스 ID를 API 경로에 안전하게 조합한다', () {
    expect(ApiPaths.schedule('schedule-1'), '/schedules/schedule-1');
    expect(ApiPaths.login, '/auth/local/login');
    expect(
      ApiPaths.latestRecommendation('schedule-1'),
      '/schedules/schedule-1/recommendations/latest',
    );
    expect(ApiPaths.routeMap('route-1'), '/routes/route-1/map');
  });

  test('공통 오류 응답에서 코드와 필드 오류를 읽는다', () {
    final response = ApiErrorResponse.fromJson({
      'success': false,
      'error': {
        'code': 'INVALID_REQUEST',
        'message': '입력값을 확인해주세요.',
        'fieldErrors': [
          {
            'field': 'email',
            'rejectedValue': 'abc@',
            'reason': '올바른 이메일 형식이 아닙니다.',
          },
        ],
      },
      'timestamp': '2026-08-31T12:00:00+09:00',
      'requestId': 'request-1',
    });

    expect(response.code, 'INVALID_REQUEST');
    expect(response.message, '입력값을 확인해주세요.');
    expect(response.fieldErrors.single.field, 'email');
    expect(response.fieldErrors.single.reason, '올바른 이메일 형식이 아닙니다.');
    expect(response.requestId, 'request-1');
  });
}
