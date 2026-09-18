import 'package:ddubi/domain/models.dart';
import 'package:ddubi/services/api_schedule_repository.dart';
import 'package:ddubi/services/schedule_api.dart';
import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';

Map<String, dynamic> _scheduleJson(int id, {String? status}) => {
  'id': id,
  'title': '일정 $id',
  'origin': {'name': '집', 'lat': 37.5, 'lng': 127.0},
  'destination': {'name': '회사', 'lat': 37.6, 'lng': 127.1},
  'arriveBy': '2026-09-19T09:00:00',
  'importance': 'HIGH',
  'considerLastTrip': false,
  'updatedAt': '2026-09-18T00:00:00',
  'status': ?status,
};

/// 서버 응답을 흉내 낸다. 목록은 status 미지정 시 취소 일정도 돌려주는 실제 계약(SCH-001)을 따른다.
Dio _dio({
  required List<Map<String, dynamic>> schedules,
  int deleteStatus = 204,
  DioExceptionType? deleteError,
  List<RequestOptions>? requests,
}) => Dio(BaseOptions(baseUrl: 'https://example.test/api/v1'))
  ..interceptors.add(
    InterceptorsWrapper(
      onRequest: (options, handler) {
        requests?.add(options);
        if (options.method == 'DELETE') {
          if (deleteError != null) {
            return handler.reject(
              DioException(requestOptions: options, type: deleteError),
            );
          }
          if (deleteStatus >= 400) {
            return handler.reject(
              DioException(
                requestOptions: options,
                response: Response<Map<String, dynamic>>(
                  requestOptions: options,
                  statusCode: deleteStatus,
                  data: {
                    'success': false,
                    'error': {'code': 'SCH-$deleteStatus', 'message': '실패'},
                  },
                ),
              ),
            );
          }
          return handler.resolve(
            Response<void>(requestOptions: options, statusCode: 204),
          );
        }
        return handler.resolve(
          Response<Map<String, dynamic>>(
            requestOptions: options,
            statusCode: 200,
            data: {
              'success': true,
              'data': {'content': schedules, 'totalElements': schedules.length},
            },
          ),
        );
      },
    ),
  );

void main() {
  group('Schedule.status', () {
    test('서버 status 를 읽고 CANCELED 만 취소로 본다', () {
      expect(
        Schedule.fromJson(_scheduleJson(1, status: 'CANCELED')).isCanceled,
        isTrue,
      );
      expect(
        Schedule.fromJson(_scheduleJson(2, status: 'UPCOMING')).status,
        ScheduleStatus.upcoming,
      );
      expect(
        Schedule.fromJson(_scheduleJson(3, status: 'DONE')).status,
        ScheduleStatus.done,
      );
      // 구버전 응답(status 없음)은 취소로 단정하지 않는다.
      final legacy = Schedule.fromJson(_scheduleJson(4));
      expect(legacy.status, ScheduleStatus.upcoming);
      expect(legacy.isCanceled, isFalse);
      // 모르는 값은 unknown 으로 두고 화면에서는 숨기지 않는다.
      final unknown = Schedule.fromJson(_scheduleJson(5, status: 'ARCHIVED'));
      expect(unknown.status, ScheduleStatus.unknown);
      expect(Schedule.visible([unknown]), hasLength(1));
    });

    test('copyWith 는 status 를 잃지 않는다', () {
      final canceled = Schedule.fromJson(_scheduleJson(1, status: 'CANCELED'));
      expect(canceled.copyWith(title: '바꿈').isCanceled, isTrue);
    });
  });

  group('ApiScheduleRepository', () {
    test('목록은 취소 일정을 걸러낸다 — 재로그인 뒤 서버가 다시 보내도 화면에 나오지 않는다', () async {
      final requests = <RequestOptions>[];
      final repository = ApiScheduleRepository(
        ScheduleApi(
          _dio(
            schedules: [
              _scheduleJson(1, status: 'UPCOMING'),
              _scheduleJson(2, status: 'CANCELED'),
              _scheduleJson(3, status: 'DONE'),
              _scheduleJson(4),
            ],
            requests: requests,
          ),
        ),
      );

      final visible = await repository.schedules();

      expect(visible.map((s) => s.id), ['1', '3', '4']);
      expect(requests.single.queryParameters.containsKey('status'), isFalse);
    });

    test('취소는 DELETE 한 번이며 204 를 정상으로 본다(재취소도 서버가 204 로 답한다)', () async {
      final requests = <RequestOptions>[];
      final repository = ApiScheduleRepository(
        ScheduleApi(_dio(schedules: const [], requests: requests)),
      );

      await repository.cancelSchedule('7');
      await repository.cancelSchedule('7');

      expect(requests, hasLength(2));
      expect(requests.every((r) => r.method == 'DELETE'), isTrue);
      expect(requests.first.path, endsWith('/schedules/7'));
    });

    test('취소 실패는 원인별로 구분해 던진다', () async {
      Future<ScheduleCommandFailure> failure({
        int status = 204,
        DioExceptionType? error,
      }) async {
        final repository = ApiScheduleRepository(
          ScheduleApi(
            _dio(schedules: const [], deleteStatus: status, deleteError: error),
          ),
        );
        try {
          await repository.cancelSchedule('7');
        } on ScheduleCommandFailure catch (caught) {
          return caught;
        }
        fail('실패가 던져져야 한다');
      }

      // 401(로그인 만료)과 403(남의 일정)은 다른 안내가 필요하므로 합치지 않는다.
      expect(
        (await failure(status: 401)).kind,
        ScheduleCommandFailureKind.unauthenticated,
      );
      expect(
        (await failure(status: 403)).kind,
        ScheduleCommandFailureKind.forbidden,
      );
      expect(
        (await failure(status: 404)).kind,
        ScheduleCommandFailureKind.notFound,
      );
      expect(
        (await failure(status: 409)).kind,
        ScheduleCommandFailureKind.conflict,
      );
      expect(
        (await failure(error: DioExceptionType.connectionError)).kind,
        ScheduleCommandFailureKind.network,
      );
      expect(
        (await failure(error: DioExceptionType.receiveTimeout)).kind,
        ScheduleCommandFailureKind.network,
      );
      expect(
        (await failure(status: 500)).kind,
        ScheduleCommandFailureKind.unknown,
      );
      expect((await failure(status: 403)).code, 'SCH-403');
    });
  });
}
