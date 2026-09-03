class ApiPaths {
  ApiPaths._();

  static const String login = '/auth/local/login';
  static const String oauthTokenLogin = '/auth/oauth/token';
  static const String oauthIdTokenLogin = '/auth/oauth/id-token';
  static const String signUp = '/auth/local/signup';
  static const String schedules = '/schedules';
  static const String deviceToken = '/devices/token';
  static const String places = '/places';

  static const String placeSearch = '$places/search';

  static String schedule(String id) => '$schedules/$id';
  static String scheduleRecommendations(String scheduleId) =>
      '${schedule(scheduleId)}/recommendations';
  static String latestRecommendation(String scheduleId) =>
      '${scheduleRecommendations(scheduleId)}/latest';
  static String scheduleRoutes(String scheduleId) =>
      '${schedule(scheduleId)}/routes';
  static String recommendationExplanation(String recommendationId) =>
      '/recommendations/$recommendationId/explanation';
  static String route(String routeId) => '/routes/$routeId';
  static String routeMap(String routeId) => '${route(routeId)}/map';
}
