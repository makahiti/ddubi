import 'package:dio/dio.dart';

import '../core/constants/api_paths.dart';
import '../domain/models.dart';

class PlaceApi implements PlaceSearchService {
  const PlaceApi(this._dio);

  final Dio _dio;

  @override
  Future<List<PlaceLocation>> search(
    String query, {
    int page = 0,
    int size = 10,
  }) async {
    final response = await _dio.get<Map<String, dynamic>>(
      ApiPaths.placeSearch,
      queryParameters: {'query': query, 'page': page, 'size': size},
    );
    final data = response.data?['data'];
    if (response.data?['success'] != true || data is! Map<String, dynamic>) {
      throw const FormatException('장소 검색 응답 형식을 확인할 수 없습니다.');
    }
    final content = data['content'];
    if (content is! List) return const [];
    return content
        .whereType<Map>()
        .map(
          (item) => PlaceLocation.fromJson(
            item.map((key, value) => MapEntry(key.toString(), value)),
          ),
        )
        .toList(growable: false);
  }

  Future<List<SavedPlace>> list({int page = 0, int size = 100}) async {
    final response = await _dio.get<Map<String, dynamic>>(
      ApiPaths.places,
      queryParameters: {'page': page, 'size': size},
    );
    final content = _pageContent(response, '저장 장소 목록');
    return content
        .whereType<Map>()
        .map((item) => SavedPlace.fromJson(_stringKeyed(item)))
        .toList(growable: false);
  }

  Future<SavedPlace> create(PlaceLocation place) async {
    final response = await _dio.post<Map<String, dynamic>>(
      ApiPaths.places,
      data: {
        'name': place.name,
        'address': place.address ?? place.name,
        'lat': place.lat,
        'lng': place.lng,
      },
    );
    return _savedPlace(response, '저장 장소 등록');
  }

  Future<SavedPlace> get(String id) async => _savedPlace(
    await _dio.get<Map<String, dynamic>>('${ApiPaths.places}/$id'),
    '저장 장소 상세',
  );

  Future<SavedPlace> update(String id, {required String name}) async =>
      _savedPlace(
        await _dio.patch<Map<String, dynamic>>(
          '${ApiPaths.places}/$id',
          data: {'name': name},
        ),
        '저장 장소 수정',
      );

  Future<void> delete(String id) => _dio.delete<void>('${ApiPaths.places}/$id');

  List<dynamic> _pageContent(
    Response<Map<String, dynamic>> response,
    String label,
  ) {
    final data = response.data?['data'];
    final content = data is Map ? data['content'] : null;
    if (response.data?['success'] != true || content is! List) {
      throw FormatException('$label 응답 형식을 확인할 수 없습니다.');
    }
    return content;
  }

  SavedPlace _savedPlace(
    Response<Map<String, dynamic>> response,
    String label,
  ) {
    final data = response.data?['data'];
    if (response.data?['success'] != true || data is! Map) {
      throw FormatException('$label 응답 형식을 확인할 수 없습니다.');
    }
    return SavedPlace.fromJson(_stringKeyed(data));
  }

  static Map<String, dynamic> _stringKeyed(Map<dynamic, dynamic> source) =>
      source.map((key, value) => MapEntry(key.toString(), value));
}
