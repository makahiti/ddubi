import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../core/constants/layout_constants.dart';
import '../core/theme.dart';
import '../core/network/dio_provider.dart';
import '../domain/models.dart';
import '../domain/schedule_evaluation.dart';
import '../presentation/screens.dart';
import '../providers/app_providers.dart';

final appRouterProvider = Provider<GoRouter>((ref) {
  final sessions = ref.watch(authSessionStoreProvider);
  var wasAuthenticated = sessions.session != null;
  final repository = ref.watch(ddubiRepositoryProvider);
  final scheduleRepository = ref.watch(scheduleRepositoryProvider);
  final authRepository = ref.watch(authRepositoryProvider);
  final kakaoLoginService = ref.watch(kakaoLoginServiceProvider);
  final googleLoginService = ref.watch(googleLoginServiceProvider);
  final naverLoginService = ref.watch(naverLoginServiceProvider);
  final placeSearchService = ref.watch(placeApiProvider);
  final savedPlaceApi = ref.watch(savedPlaceApiProvider);
  final homeApi = ref.watch(homeApiProvider);
  final repeatScheduleRepository = ref.watch(repeatScheduleRepositoryProvider);
  final notificationApi = ref.watch(notificationApiProvider);
  final userApi = ref.watch(userApiProvider);
  final recommendationApi = ref.watch(recommendationApiProvider);
  final arrivalApi = ref.watch(arrivalApiProvider);
  final reportApi = ref.watch(reportApiProvider);

  return GoRouter(
    initialLocation: sessions.session != null
        ? '/home'
        : sessions.hasLaunched
        ? '/login'
        : '/intro',
    refreshListenable: sessions,
    redirect: (context, state) {
      if (sessions.session != null) {
        wasAuthenticated = true;
      } else if (wasAuthenticated) {
        wasAuthenticated = false;
        return '/login';
      }
      return null;
    },
    routes: [
      GoRoute(path: '/', redirect: (context, state) => '/intro'),
      GoRoute(path: '/intro', builder: (context, state) => const IntroScreen()),
      GoRoute(
        path: '/login',
        builder: (context, state) => LoginScreen(
          authRepository: authRepository,
          kakaoLoginService: kakaoLoginService,
          googleLoginService: googleLoginService,
          naverLoginService: naverLoginService,
          onKeepSignedInChanged: (value) => sessions.keepSignedIn = value,
        ),
      ),
      ShellRoute(
        builder: (context, state, child) =>
            _ResponsiveNavigationShell(location: state.uri.path, child: child),
        routes: [
          GoRoute(
            path: '/home',
            builder: (context, state) => HomeScreen(
              homeApi: homeApi,
              recommendationApi: recommendationApi,
              evaluationRepository:
                  scheduleRepository is ScheduleEvaluationRepository
                  ? scheduleRepository as ScheduleEvaluationRepository
                  : null,
            ),
          ),
          GoRoute(
            path: '/schedules',
            builder: (context, state) =>
                ScheduleListScreen(repository: scheduleRepository),
            routes: [
              GoRoute(
                path: 'new',
                builder: (context, state) => ScheduleFormScreen(
                  repository: scheduleRepository,
                  placeSearchService: placeSearchService,
                  initialDate: state.extra as DateTime?,
                ),
              ),
              GoRoute(
                path: 'repeat/new',
                builder: (context, state) => RepeatScheduleFormScreen(
                  repository: repeatScheduleRepository,
                  placeSearchService: placeSearchService,
                  initialDate: state.extra as DateTime?,
                ),
              ),
              GoRoute(
                path: 'repeats',
                builder: (context, state) => RepeatSchedulesScreen(
                  repository: repeatScheduleRepository,
                  placeSearchService: placeSearchService,
                ),
              ),
              GoRoute(
                path: 'repeats/:id/edit',
                builder: (context, state) => RepeatScheduleEditScreen(
                  repository: repeatScheduleRepository,
                  placeSearchService: placeSearchService,
                  repeatId: state.pathParameters['id']!,
                ),
              ),
              GoRoute(
                path: ':id/edit',
                builder: (context, state) => ScheduleEditScreen(
                  repository: scheduleRepository,
                  placeSearchService: placeSearchService,
                  scheduleId: state.pathParameters['id']!,
                ),
              ),
              GoRoute(
                path: ':id',
                builder: (context, state) {
                  final arguments = state.extra is ScheduleDetailArguments
                      ? state.extra! as ScheduleDetailArguments
                      : null;
                  return ScheduleDetailScreen(
                    repository: scheduleRepository,
                    routeRepository: repository,
                    recommendationApi: recommendationApi,
                    scheduleId: state.pathParameters['id']!,
                    arrivalApi: arrivalApi,
                    initialSchedule: arguments?.schedule,
                    initialRoutes: arguments?.routes,
                  );
                },
              ),
            ],
          ),
          GoRoute(
            path: '/recommendations',
            builder: (context, state) => RouteSearchScreen(
              repository: repository,
              placeSearchService: placeSearchService,
            ),
          ),
          GoRoute(
            path: '/recommendations/:id',
            builder: (context, state) => RouteResultsScreen(
              repository: repository,
              api: recommendationApi,
              condition: state.extra is RouteSearchCondition
                  ? state.extra! as RouteSearchCondition
                  : null,
            ),
          ),
          GoRoute(
            path: '/notifications',
            builder: (context, state) =>
                NotificationsScreen(api: notificationApi),
            routes: [
              GoRoute(
                path: ':id',
                builder: (context, state) => NotificationDetailScreen(
                  api: notificationApi,
                  notificationId: state.pathParameters['id']!,
                  evaluationRepository:
                      scheduleRepository is ScheduleEvaluationRepository
                      ? scheduleRepository as ScheduleEvaluationRepository
                      : null,
                ),
              ),
            ],
          ),
          GoRoute(
            path: '/settings',
            builder: (context, state) => SettingsScreen(
              repository: repository,
              userApi: userApi,
              authRepository: authRepository,
              reportApi: reportApi,
            ),
            routes: [
              GoRoute(
                path: 'places',
                builder: (context, state) => SavedPlacesScreen(
                  api: savedPlaceApi,
                  searchService: placeSearchService,
                ),
              ),
              GoRoute(
                path: 'records',
                builder: (context, state) =>
                    TravelRecordsScreen(api: reportApi),
              ),
              GoRoute(
                path: 'account',
                builder: (context, state) => AccountManagementScreen(
                  userApi: userApi,
                  authRepository: authRepository,
                  kakaoLoginService: kakaoLoginService,
                  googleLoginService: googleLoginService,
                  naverLoginService: naverLoginService,
                ),
              ),
            ],
          ),
        ],
      ),
    ],
    errorBuilder: (context, state) => Scaffold(
      appBar: AppBar(title: const Text('페이지를 찾을 수 없어요')),
      body: Center(child: Text(state.error?.toString() ?? '경로를 확인해주세요.')),
    ),
  );
});

class _ResponsiveNavigationShell extends StatelessWidget {
  const _ResponsiveNavigationShell({
    required this.location,
    required this.child,
  });

  final String location;
  final Widget child;

  int get _selectedIndex {
    if (location.startsWith('/schedules')) return 0;
    if (location.startsWith('/recommendations')) return 1;
    if (location.startsWith('/notifications')) return 3;
    if (location.startsWith('/settings')) return 4;
    return 2;
  }

  void _go(BuildContext context, int index) {
    const locations = [
      '/schedules',
      '/recommendations',
      '/home',
      '/notifications',
      '/settings',
    ];
    context.go(locations[index]);
  }

  @override
  Widget build(BuildContext context) {
    const destinations = [
      NavigationDestination(
        icon: Icon(Icons.calendar_today_outlined),
        label: '일정',
      ),
      NavigationDestination(icon: Icon(Icons.route_outlined), label: '경로'),
      NavigationDestination(icon: Icon(Icons.home_outlined), label: '홈'),
      NavigationDestination(icon: Icon(Icons.notifications_none), label: '알림'),
      NavigationDestination(icon: Icon(Icons.menu), label: '더보기'),
    ];

    return LayoutBuilder(
      builder: (context, constraints) {
        final isWide =
            constraints.maxWidth >= LayoutConstants.wideScreenBreakpoint;
        if (isWide) {
          final useExtendedRail = constraints.maxWidth >= 1100;
          return Scaffold(
            body: Row(
              children: [
                NavigationRail(
                  extended: useExtendedRail,
                  minExtendedWidth: 210,
                  backgroundColor: Colors.white,
                  selectedIndex: _selectedIndex,
                  onDestinationSelected: (index) => _go(context, index),
                  groupAlignment: -.72,
                  indicatorColor: const Color(0xFFDDF7F3),
                  labelType: useExtendedRail
                      ? NavigationRailLabelType.none
                      : NavigationRailLabelType.all,
                  leading: useExtendedRail
                      ? Padding(
                          padding: const EdgeInsets.only(bottom: 28),
                          child: Row(
                            mainAxisSize: MainAxisSize.min,
                            children: [
                              Container(
                                width: 34,
                                height: 34,
                                decoration: const BoxDecoration(
                                  color: DdubiColors.blue,
                                  shape: BoxShape.circle,
                                ),
                                child: const Icon(
                                  Icons.directions_walk,
                                  color: Colors.white,
                                  size: 21,
                                ),
                              ),
                              const SizedBox(width: 10),
                              Text(
                                '뚜비',
                                style: Theme.of(context).textTheme.titleLarge,
                              ),
                            ],
                          ),
                        )
                      : null,
                  trailing: useExtendedRail
                      ? const Padding(
                          padding: EdgeInsets.only(top: 30),
                          child: _WebUserSummary(),
                        )
                      : null,
                  destinations: const [
                    NavigationRailDestination(
                      icon: Icon(Icons.calendar_today_outlined),
                      label: Text('일정'),
                    ),
                    NavigationRailDestination(
                      icon: Icon(Icons.route_outlined),
                      label: Text('경로'),
                    ),
                    NavigationRailDestination(
                      icon: Icon(Icons.home_outlined),
                      label: Text('홈'),
                    ),
                    NavigationRailDestination(
                      icon: Icon(Icons.notifications_none),
                      label: Text('알림'),
                    ),
                    NavigationRailDestination(
                      icon: Icon(Icons.menu),
                      label: Text('더보기'),
                    ),
                  ],
                ),
                const VerticalDivider(width: 1),
                Expanded(child: child),
              ],
            ),
          );
        }

        return Scaffold(
          body: child,
          bottomNavigationBar: NavigationBar(
            selectedIndex: _selectedIndex,
            onDestinationSelected: (index) => _go(context, index),
            destinations: destinations,
          ),
        );
      },
    );
  }
}

class _WebUserSummary extends StatelessWidget {
  const _WebUserSummary();

  @override
  Widget build(BuildContext context) => Row(
    mainAxisSize: MainAxisSize.min,
    children: [
      const CircleAvatar(
        radius: 16,
        backgroundColor: Color(0xFFEAFBF8),
        child: Icon(Icons.person_outline, size: 18, color: DdubiColors.blue),
      ),
      const SizedBox(width: 9),
      Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text('뚜비 사용자', style: Theme.of(context).textTheme.titleSmall),
          Text('이동 준비 중', style: Theme.of(context).textTheme.bodySmall),
        ],
      ),
    ],
  );
}
