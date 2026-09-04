class ApiPaths {
  ApiPaths._();

  static const String login = '/auth/local/login';
  static const String emailVerification = '/auth/email/verification';
  static const String emailVerificationConfirm =
      '/auth/email/verification/confirm';
  static const String oauthTokenLogin = '/auth/oauth/token';
  static const String oauthIdTokenLogin = '/auth/oauth/id-token';
  static const String signUp = '/auth/local/signup';
  static const String refresh = '/auth/refresh';
  static const String logout = '/auth/logout';
  static const String schedules = '/schedules';
  static const String scheduleRepeats = '/schedule-repeats';
  static const String deviceToken = '/devices/token';
  static const String places = '/places';
  static const String home = '/home';
  static const String notifications = '/notifications';
  static const String me = '/users/me';

  static const String placeSearch = '$places/search';

  static String schedule(String id) => '$schedules/$id';
  static const String scheduleConflicts = '$schedules/conflicts';
  static String notification(String id) => '$notifications/$id';
  static String notificationRead(String id) => '${notification(id)}/read';
  static const String notificationsReadAll = '$notifications/read-all';
  static String scheduleRepeat(String id) => '$scheduleRepeats/$id';
  static String scheduleRecommendations(String scheduleId) =>
      '${schedule(scheduleId)}/recommendations';
  static String latestRecommendation(String scheduleId) =>
      '${scheduleRecommendations(scheduleId)}/latest';
  static String scheduleRoutes(String scheduleId) =>
      '${schedule(scheduleId)}/routes';
  static String recommendationExplanation(String recommendationId) =>
      '/recommendations/$recommendationId/explanation';
  static String route(String routeId) => '/routes/$routeId';
  static const String routeSearch = '/routes/search';
  static String routeMap(String routeId) => '${route(routeId)}/map';
}
