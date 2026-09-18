import 'dart:convert';
import 'dart:io';

import 'package:ddubi/domain/schedule_evaluation.dart';
import 'package:ddubi/presentation/schedule_evaluation_card.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

const _captureFontFamily = 'DdubiCaptureKorean';

Future<void> _loadCaptureFont() async {
  final configured = Platform.environment['DDUBI_CAPTURE_FONT'];
  final path = configured?.isNotEmpty == true
      ? configured!
      : r'C:\Windows\Fonts\malgun.ttf';
  final file = File(path);
  if (!await file.exists()) {
    throw StateError('한글 화면 캡처 글꼴이 없습니다. DDUBI_CAPTURE_FONT에 TTF 경로를 지정하세요.');
  }
  final bytes = await file.readAsBytes();
  final loader = FontLoader(_captureFontFamily)
    ..addFont(Future.value(ByteData.sublistView(Uint8List.fromList(bytes))));
  await loader.load();
}

Future<ScheduleEvaluationV2> _fixture(String name) async {
  final raw = await File('test/fixtures/schedule_evaluation_$name.json')
      .readAsString();
  return ScheduleEvaluationV2.fromJson(jsonDecode(raw) as Map<String, dynamic>);
}

Widget _captureFrame(String title, ScheduleEvaluationV2 evaluation) =>
    MaterialApp(
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: const Color(0xFF0CB3A2)),
        fontFamily: _captureFontFamily,
        useMaterial3: true,
      ),
      home: Scaffold(
        backgroundColor: const Color(0xFFF2FBF9),
        appBar: AppBar(title: Text(title)),
        body: SingleChildScrollView(
          padding: const EdgeInsets.all(16),
          child: Card(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: ScheduleEvaluationSummary(evaluation: evaluation),
            ),
          ),
        ),
      ),
    );

String _captureTitle(String name) => switch (name) {
  'walking_fallback' => '이동 예측 · 도보 fallback',
  _ => '이동 예측 · ${name.toUpperCase()}',
};

void main() {
  final names = [
    'keep',
    'advance',
    'margin',
    'late',
    'data',
    'search',
    'preview',
    'walking_fallback',
  ];
  late Map<String, ScheduleEvaluationV2> fixtures;

  setUpAll(() async {
    await _loadCaptureFont();
    fixtures = {for (final name in names) name: await _fixture(name)};
  });

  for (final name in names) {
    testWidgets('$name 화면 캡처', (tester) async {
      tester.view.physicalSize = Size(
        390,
        name == 'keep' || name == 'walking_fallback' ? 1100 : 844,
      );
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);

      await tester.pumpWidget(
        _captureFrame(_captureTitle(name), fixtures[name]!),
      );
      await tester.pumpAndSettle();

      await expectLater(
        find.byType(MaterialApp),
        matchesGoldenFile('captures/schedule_evaluation_$name.png'),
      );
    });
  }
}
