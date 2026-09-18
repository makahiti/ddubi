import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

/// 로그는 `AppLog` 한 곳으로만 나간다.
///
/// `debugPrint`/`debugPrintStack`/`print` 를 앱 코드에서 직접 부르면 릴리스 빌드에서도 출력된다.
/// 린트(`avoid_print`)는 `debugPrint` 를 잡지 못하므로 이 시험이 대신 막는다(2026-09-18).
void main() {
  test('lib/ 안에서 debugPrint·debugPrintStack·print 직접 호출은 AppLog 하나만 허용한다', () {
    final offenders = <String>[];
    final pattern = RegExp(
      r'(?<![A-Za-z_.])(debugPrint|debugPrintStack|print)\(',
    );
    for (final file in Directory(
      'lib',
    ).listSync(recursive: true).whereType<File>()) {
      if (!file.path.endsWith('.dart')) continue;
      final normalized = file.path.replaceAll('\\', '/');
      if (normalized.endsWith('lib/core/diagnostics/app_log.dart')) continue;
      final lines = file.readAsLinesSync();
      for (var i = 0; i < lines.length; i++) {
        final line = lines[i];
        if (line.trimLeft().startsWith('//')) continue;
        if (pattern.hasMatch(line)) {
          offenders.add('$normalized:${i + 1}: ${line.trim()}');
        }
      }
    }
    expect(
      offenders,
      isEmpty,
      reason: '직접 출력 대신 AppLog.debug/AppLog.stack 을 쓰세요.',
    );
  });
}
