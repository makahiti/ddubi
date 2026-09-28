import 'package:flutter/foundation.dart' show kIsWeb;
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'app/router.dart';
import 'core/theme.dart';
import 'providers/app_providers.dart';

class DdubiApp extends ConsumerWidget {
  const DdubiApp({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final router = ref.watch(appRouterProvider);
    ref.read(deviceTokenServiceProvider).setNotificationOpenHandler(router.go);

    return MaterialApp.router(
      title: '뚜비',
      debugShowCheckedModeBanner: false,
      theme: DdubiTheme.light,
      scrollBehavior: const DdubiScrollBehavior(),
      routerConfig: router,
      builder: (context, child) => WebPhoneFrame(child: child),
    );
  }
}

/// 웹에서 앱을 폰 크기 틀에 담아 가운데 보여준다.
///
/// 왜 이렇게 하나.
/// 이 앱은 화면이 넓으면 데스크톱 전용 화면으로 갈라진다. 그런데 그쪽은 손이 덜 닿아,
/// 모바일 쪽에만 들어간 것들이 빠져 있다(완료·취소 표시, 아래로 당겨 새로 고침,
/// 일정 상세 개편 등). 웹을 심사용으로 공개하는 지금은 "앱을 웹에서 그대로 보는" 모양이
/// 보여줄 것도 많고 확인도 끝나 있다.
///
/// 데스크톱 화면을 지우지는 않는다. 이 틀을 걷어내면 그대로 다시 살아난다.
///
/// 크기만 줄이는 것으로는 부족하다. 화면 너비를 [MediaQuery] 로 직접 읽는 곳이 있어
/// 그 값도 함께 좁혀 줘야 한 화면 안에서 모바일·데스크톱이 섞이지 않는다.
class WebPhoneFrame extends StatelessWidget {
  const WebPhoneFrame({super.key, required this.child});

  final Widget? child;

  /// 폰 틀의 가로 크기. 요즘 안드로이드 폰의 논리 너비에 맞췄다.
  static const double phoneWidth = 430;

  @override
  Widget build(BuildContext context) {
    final content = child ?? const SizedBox.shrink();
    if (!kIsWeb) return content;

    final media = MediaQuery.of(context);
    // 창이 폰보다 좁으면(모바일 브라우저) 틀을 씌우지 않는다. 그대로가 이미 폰 화면이다.
    if (media.size.width <= phoneWidth) return content;

    return ColoredBox(
      color: const Color(0xFFE6ECF3),
      child: Center(
        child: SizedBox(
          width: phoneWidth,
          child: DecoratedBox(
            decoration: const BoxDecoration(
              color: Colors.white,
              border: Border.symmetric(
                vertical: BorderSide(color: Color(0xFFD3DCE7)),
              ),
            ),
            child: MediaQuery(
              data: media.copyWith(size: Size(phoneWidth, media.size.height)),
              child: content,
            ),
          ),
        ),
      ),
    );
  }
}
