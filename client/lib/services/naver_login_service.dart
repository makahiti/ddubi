import 'dart:convert';
import 'dart:math';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:webview_flutter/webview_flutter.dart';

const naverRedirectUri = String.fromEnvironment(
  'NAVER_REDIRECT_URI',
  defaultValue: 'https://j15e202.p.ssafy.io/oauth/naver',
);

class NaverLoginException implements Exception {
  const NaverLoginException(this.message);

  final String message;
}

class NaverAuthorizationResult {
  const NaverAuthorizationResult({
    required this.authorizationCode,
    required this.state,
    required this.redirectUri,
  });

  final String authorizationCode;
  final String state;
  final String redirectUri;
}

abstract interface class NaverLoginService {
  Future<NaverAuthorizationResult> login(BuildContext context);
}

class NaverWebLoginService implements NaverLoginService {
  const NaverWebLoginService();

  static const _clientId = String.fromEnvironment('NAVER_CLIENT_ID');

  @override
  Future<NaverAuthorizationResult> login(BuildContext context) async {
    final isAndroid =
        !kIsWeb && defaultTargetPlatform == TargetPlatform.android;
    if (!isAndroid) {
      throw const NaverLoginException('네이버 로그인은 현재 Android 앱에서 확인할 수 있어요.');
    }
    if (_clientId.isEmpty) {
      throw const NaverLoginException('NAVER_CLIENT_ID를 설정하고 앱을 다시 실행해주세요.');
    }

    final state = createNaverState();
    final authorizationUri = createNaverAuthorizationUri(
      clientId: _clientId,
      redirectUri: naverRedirectUri,
      state: state,
    );
    final callbackUri = await Navigator.of(context).push<Uri>(
      MaterialPageRoute<Uri>(
        fullscreenDialog: true,
        builder: (_) => _NaverAuthorizationPage(
          authorizationUri: authorizationUri,
          redirectUri: naverRedirectUri,
        ),
      ),
    );

    if (callbackUri == null) {
      throw const NaverLoginException('네이버 로그인이 취소되었어요.');
    }
    return parseNaverAuthorizationCallback(
      callbackUri,
      expectedState: state,
      redirectUri: naverRedirectUri,
    );
  }
}

String createNaverState() {
  final random = Random.secure();
  final bytes = List<int>.generate(32, (_) => random.nextInt(256));
  return base64Url.encode(bytes).replaceAll('=', '');
}

Uri createNaverAuthorizationUri({
  required String clientId,
  required String redirectUri,
  required String state,
}) => Uri.https('nid.naver.com', '/oauth2.0/authorize', {
  'response_type': 'code',
  'client_id': clientId,
  'redirect_uri': redirectUri,
  'state': state,
});

bool isNaverRedirect(Uri uri, String redirectUri) {
  final expected = Uri.parse(redirectUri);
  return uri.scheme == expected.scheme &&
      uri.host == expected.host &&
      uri.port == expected.port &&
      uri.path == expected.path;
}

NaverAuthorizationResult parseNaverAuthorizationCallback(
  Uri uri, {
  required String expectedState,
  required String redirectUri,
}) {
  if (!isNaverRedirect(uri, redirectUri)) {
    throw const NaverLoginException('네이버 로그인 Callback 주소가 올바르지 않아요.');
  }
  final error = uri.queryParameters['error'];
  if (error != null) {
    final description = uri.queryParameters['error_description'];
    throw NaverLoginException(
      description?.isNotEmpty == true
          ? description!
          : '네이버 인증을 완료하지 못했어요. 다시 시도해주세요.',
    );
  }
  final state = uri.queryParameters['state'];
  if (state != expectedState) {
    throw const NaverLoginException('로그인 요청을 확인할 수 없어요. 네이버 로그인을 다시 시도해주세요.');
  }
  final code = uri.queryParameters['code'];
  if (code == null || code.isEmpty) {
    throw const NaverLoginException('네이버 인증 코드를 받지 못했어요.');
  }
  return NaverAuthorizationResult(
    authorizationCode: code,
    state: state!,
    redirectUri: redirectUri,
  );
}

class _NaverAuthorizationPage extends StatefulWidget {
  const _NaverAuthorizationPage({
    required this.authorizationUri,
    required this.redirectUri,
  });

  final Uri authorizationUri;
  final String redirectUri;

  @override
  State<_NaverAuthorizationPage> createState() =>
      _NaverAuthorizationPageState();
}

class _NaverAuthorizationPageState extends State<_NaverAuthorizationPage> {
  late final WebViewController _controller;
  var _loading = true;
  String? _error;

  @override
  void initState() {
    super.initState();
    _controller = WebViewController()
      ..setJavaScriptMode(JavaScriptMode.unrestricted)
      ..setNavigationDelegate(
        NavigationDelegate(
          onPageStarted: (_) {
            if (mounted) setState(() => _loading = true);
          },
          onPageFinished: (_) {
            if (mounted) setState(() => _loading = false);
          },
          onWebResourceError: (error) {
            if (error.isForMainFrame == true && mounted) {
              setState(() {
                _loading = false;
                _error = '네이버 로그인 화면을 불러오지 못했어요.';
              });
            }
          },
          onNavigationRequest: (request) {
            final uri = Uri.tryParse(request.url);
            if (uri == null || !isNaverRedirect(uri, widget.redirectUri)) {
              return NavigationDecision.navigate;
            }
            Navigator.of(context).pop(uri);
            return NavigationDecision.prevent;
          },
        ),
      )
      ..loadRequest(widget.authorizationUri);
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(
      title: const Text('네이버 로그인'),
      leading: IconButton(
        tooltip: '닫기',
        onPressed: () => Navigator.of(context).pop(),
        icon: const Icon(Icons.close),
      ),
    ),
    body: Stack(
      children: [
        WebViewWidget(controller: _controller),
        if (_loading) const LinearProgressIndicator(),
        if (_error != null)
          ColoredBox(
            color: Theme.of(context).colorScheme.surface,
            child: Center(
              child: Padding(
                padding: const EdgeInsets.all(24),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Text(_error!, textAlign: TextAlign.center),
                    const SizedBox(height: 16),
                    FilledButton(
                      onPressed: () {
                        setState(() {
                          _error = null;
                          _loading = true;
                        });
                        _controller.loadRequest(widget.authorizationUri);
                      },
                      child: const Text('다시 시도'),
                    ),
                  ],
                ),
              ),
            ),
          ),
      ],
    ),
  );
}
