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
}
