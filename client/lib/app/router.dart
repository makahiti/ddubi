import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../core/constants/layout_constants.dart';
import '../domain/models.dart';
import '../presentation/screens.dart';
import '../providers/app_providers.dart';

final appRouterProvider = Provider<GoRouter>((ref) {
  final repository = ref.watch(ddubiRepositoryProvider);
  final authRepository = ref.watch(authRepositoryProvider);
  final kakaoLoginService = ref.watch(kakaoLoginServiceProvider);
  final googleLoginService = ref.watch(googleLoginServiceProvider);
  final naverLoginService = ref.watch(naverLoginServiceProvider);

  return GoRouter(
    initialLocation: '/login',
    routes: [
      GoRoute(path: '/', redirect: (context, state) => '/login'),
      GoRoute(
        path: '/login',
        builder: (context, state) => LoginScreen(
          authRepository: authRepository,
          kakaoLoginService: kakaoLoginService,
          googleLoginService: googleLoginService,
          naverLoginService: naverLoginService,
        ),
      ),
      ShellRoute(
        builder: (context, state, child) =>
            _ResponsiveNavigationShell(location: state.uri.path, child: child),
        routes: [
          GoRoute(
            path: '/home',
            builder: (context, state) => HomeScreen(repository: repository),
          ),
          GoRoute(
            path: '/schedules',
            builder: (context, state) =>
                ScheduleListScreen(repository: repository),
            routes: [
              GoRoute(
                path: 'new',
                builder: (context, state) => ScheduleFormScreen(
                  repository: repository,
                  initialDate: state.extra as DateTime?,
                ),
              ),
              GoRoute(
                path: 'repeat/new',
                builder: (context, state) => RepeatScheduleFormScreen(
                  repository: repository,
                  initialDate: state.extra as DateTime?,
                ),
              ),
              GoRoute(
                path: ':id',
                builder: (context, state) => ScheduleDetailScreen(
                  repository: repository,
                  scheduleId: state.pathParameters['id']!,
                ),
              ),
            ],
          ),
          GoRoute(
            path: '/recommendations',
            builder: (context, state) =>
                RouteSearchScreen(repository: repository),
          ),
          GoRoute(
            path: '/recommendations/:id',
            builder: (context, state) => RouteResultsScreen(
              repository: repository,
              condition: state.extra is RouteSearchCondition
                  ? state.extra! as RouteSearchCondition
                  : null,
            ),
          ),
          GoRoute(
            path: '/notifications',
            builder: (context, state) => const NotificationsScreen(),
          ),
          GoRoute(
            path: '/settings',
            builder: (context, state) => SettingsScreen(repository: repository),
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
          return Scaffold(
            body: Row(
              children: [
                NavigationRail(
                  selectedIndex: _selectedIndex,
                  onDestinationSelected: (index) => _go(context, index),
                  labelType: NavigationRailLabelType.all,
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
