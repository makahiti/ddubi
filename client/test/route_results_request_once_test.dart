import 'package:ddubi/data/mock_repository.dart';
import 'package:ddubi/domain/models.dart';
import 'package:ddubi/presentation/screens.dart';
import 'package:ddubi/services/recommendation_api.dart';
import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

/// 추천 결과 화면은 조회를 한 번만 한다.
///
/// 예전에는 `build()` 안에서 곧바로 `evaluateOrSearch()` 를 불렀다. Flutter 는 키보드가
/// 올라오거나 화면이 돌아가는 등 여러 이유로 `build()` 를 다시 부르는데, 그때마다 새
/// 요청이 나갔다. 서버는 요청마다 카카오 대중교통·도보 API 를 부르므로 분당 호출 제한에
/// 걸릴 수 있다(BE 지적, 2026-09-22).
const _evaluationData = {
  'evaluationId': 'ev-1',
  'state': 'READY',
  'proposedDepartAt': '2026-09-22T08:10:00',
  'candidateEstimates': <Map<String, dynamic>>[],
};

RouteSearchCondition _condition() => RouteSearchCondition(
  origin: const PlaceLocation(name: '집', lat: 37.5, lng: 127.0),
  destination: const PlaceLocation(name: '회사', lat: 37.51, lng: 127.03),
  plannedDepartAt: DateTime(2026, 9, 22, 8, 10),
  desiredArrivalAt: DateTime(2026, 9, 22, 9),
);

/// 요청을 세면서 가짜 응답을 돌려준다.
(Dio, List<String>) _countingDio() {
  final paths = <String>[];
  final dio = Dio(BaseOptions(baseUrl: 'https://example.test/api/v1'))
    ..interceptors.add(
      InterceptorsWrapper(
        onRequest: (options, handler) {
          paths.add(options.path);
          handler.resolve(
            Response<Map<String, dynamic>>(
              requestOptions: options,
              statusCode: 200,
              data: const {'success': true, 'data': _evaluationData},
            ),
          );
        },
      ),
    );
  return (dio, paths);
}

void main() {
  testWidgets('화면을 여러 번 다시 그려도 서버를 한 번만 부른다', (tester) async {
    final (dio, paths) = _countingDio();

    await tester.pumpWidget(
      MaterialApp(
        home: RouteResultsScreen(
          repository: MockDdubiRepository(),
          api: RecommendationApi(dio),
          condition: _condition(),
        ),
      ),
    );
    await tester.pumpAndSettle();
    expect(paths.length, 1, reason: '첫 조회는 한 번이어야 한다');

    // 화면 크기가 바뀌면 Flutter 가 build 를 다시 부른다. 실제로 키보드가 올라올 때 그렇다.
    tester.view.physicalSize = const Size(390 * 3, 600 * 3);
    tester.view.devicePixelRatio = 3;
    addTearDown(tester.view.reset);
    await tester.pumpAndSettle();

    // 부모가 갱신돼도 마찬가지다.
    await tester.pumpWidget(
      MaterialApp(
        home: RouteResultsScreen(
          repository: MockDdubiRepository(),
          api: RecommendationApi(dio),
          condition: _condition(),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(paths.length, 1, reason: '다시 그린다고 서버를 또 부르면 안 된다');
  });

  testWidgets('검색 조건이 바뀌면 그때는 다시 부른다', (tester) async {
    final (dio, paths) = _countingDio();
    final repository = MockDdubiRepository();

    await tester.pumpWidget(
      MaterialApp(
        home: RouteResultsScreen(
          repository: repository,
          api: RecommendationApi(dio),
          condition: _condition(),
        ),
      ),
    );
    await tester.pumpAndSettle();

    await tester.pumpWidget(
      MaterialApp(
        home: RouteResultsScreen(
          repository: repository,
          api: RecommendationApi(dio),
          condition: RouteSearchCondition(
            origin: const PlaceLocation(name: '집', lat: 37.5, lng: 127.0),
            destination: const PlaceLocation(name: '학교', lat: 37.6, lng: 127.1),
            plannedDepartAt: DateTime(2026, 9, 22, 8, 10),
            desiredArrivalAt: DateTime(2026, 9, 22, 9),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(paths.length, 2);
  });
}
