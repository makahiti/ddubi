import 'package:ddubi/core/constants/api_paths.dart';
import 'package:ddubi/core/network/api_error_response.dart';
import 'package:ddubi/domain/models.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('리소스 ID를 API 경로에 안전하게 조합한다', () {
    expect(ApiPaths.schedule('schedule-1'), '/schedules/schedule-1');
    expect(ApiPaths.login, '/auth/local/login');
    expect(ApiPaths.oauthTokenLogin, '/auth/oauth/token');
    expect(ApiPaths.oauthIdTokenLogin, '/auth/oauth/id-token');
    expect(ApiPaths.placeSearch, '/places/search');
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

  test('일정 조회 응답에서 좌표와 updatedAt을 잃지 않는다', () {
    final schedule = Schedule.fromJson({
      'id': 12,
      'title': '프로젝트 회의',
      'origin': {'name': '강남역', 'lat': 37.4979, 'lng': 127.0276},
      'destination': {'name': '잠실야구장', 'lat': 37.5121, 'lng': 127.0719},
      'arriveBy': '2026-09-10T19:00:00',
      'importance': 'HIGH',
      'considerLastTrip': true,
      'updatedAt': '2026-09-03T09:14:22.123456',
    });

    expect(schedule.id, '12');
    expect(schedule.destination.lat, 37.5121);
    expect(schedule.importance, ScheduleImportance.high);
    expect(schedule.considerLastTrip, isTrue);
    expect(schedule.updatedAt, DateTime(2026, 9, 3, 9, 14, 22, 123, 456));
  });

  test('교통수단 3지선다를 사용자 API transportPrefs로 변환한다', () {
    expect(TransportPreferenceChoice.balanced.apiTransportPrefs, isEmpty);
    expect(TransportPreferenceChoice.subwayFirst.apiTransportPrefs, [
      {'mode': 'SUBWAY', 'preference': 'PREFER'},
    ]);
    expect(TransportPreferenceChoice.busFirst.apiTransportPrefs, [
      {'mode': 'BUS', 'preference': 'PREFER'},
    ]);
  });
}
