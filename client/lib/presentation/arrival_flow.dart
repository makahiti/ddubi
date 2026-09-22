import 'package:flutter/material.dart';

import '../domain/models.dart';
import '../services/app_api.dart';
import 'departure_headline.dart' show formatClock;

/// 도착 보고를 한 곳에서 처리한다.
///
/// <h3>왜 함수로 뽑았나</h3>
/// 도착을 보고하는 자리가 둘이다 — 일정 상세의 "도착 완료" 와 이동 안내의 "도착했어요".
/// 예전에는 앞의 것만 서버를 부르고 뒤의 것은 화면만 바꿨다. 같은 뜻의 버튼이 서로 다르게
/// 동작하면 사용자는 어느 쪽이 진짜인지 알 수 없다. 두 버튼이 같은 흐름을 쓰게 묶는다.
///
/// <h3>도착 시각을 왜 함께 보내나</h3>
/// 서버는 계정 동의(`historyConsentedAt`)와 이번 요청 동의(`locationConsent`)가 둘 다 참일 때만
/// 실제 도착 시각을 저장하고, 저장한 시각이 있어야 정시·빠듯·지연을 판정한다. 앱이 시각을
/// 보내지 않아 모든 기록이 "판정 없음" 으로 쌓이던 것이 리포트 정시율 0% 의 원인이었다
/// (2026-09-22 확인).
///
/// 시각은 사용자가 스스로 보고하는 값이지 위치로 확인한 값이 아니다. 문구를 그렇게 맞춘다.
///
/// 돌려주는 값: 서버가 기록한 결과. 사용자가 확인 창을 닫았거나 저장에 실패하면 null 이다.
/// 실패는 호출한 쪽이 아니라 여기서 안내한다.
Future<ArrivalRecord?> recordArrivalWithConsent(
  BuildContext context, {
  required String scheduleId,
  required ArrivalApi arrivalApi,
  required UserApi userApi,
}) async {
  // 버튼을 누른 순간을 기준으로 잡는다. 확인 창을 오래 열어 둔 만큼 시각이 밀리지 않는다.
  var arrivedAt = DateTime.now();
  var keepRecord = true;

  final confirmed = await showDialog<bool>(
    context: context,
    builder: (dialogContext) => StatefulBuilder(
      builder: (dialogContext, refreshDialog) => AlertDialog(
        title: const Text('도착을 완료할까요?'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              keepRecord
                  ? '${formatClock(arrivedAt)} 에 도착한 것으로 기록하고 일정을 완료해요.'
                  : '일정만 완료해요. 도착 시각이 없으면 정시 여부는 판정하지 않아요.',
            ),
            if (keepRecord)
              Align(
                alignment: Alignment.centerLeft,
                child: TextButton(
                  key: const Key('changeArrivalTimeButton'),
                  onPressed: () async {
                    final picked = await showTimePicker(
                      context: dialogContext,
                      initialTime: TimeOfDay.fromDateTime(arrivedAt),
                      helpText: '도착한 시각',
                    );
                    if (picked == null || !dialogContext.mounted) return;
                    final candidate = DateTime(
                      arrivedAt.year,
                      arrivedAt.month,
                      arrivedAt.day,
                      picked.hour,
                      picked.minute,
                    );
                    // 서버는 미래 도착을 받지 않는다. 받으면 정시 통계가 조용히 부풀려진다.
                    if (candidate.isAfter(DateTime.now())) {
                      ScaffoldMessenger.of(dialogContext).showSnackBar(
                        const SnackBar(
                          content: Text('아직 오지 않은 시각은 기록할 수 없어요.'),
                        ),
                      );
                      return;
                    }
                    refreshDialog(() => arrivedAt = candidate);
                  },
                  child: const Text('시각 바꾸기'),
                ),
              ),
            CheckboxListTile(
              key: const Key('keepArrivalRecordCheckbox'),
              contentPadding: EdgeInsets.zero,
              controlAffinity: ListTileControlAffinity.leading,
              value: keepRecord,
              onChanged: (value) =>
                  refreshDialog(() => keepRecord = value ?? false),
              title: const Text('도착 시각 기록하기'),
              subtitle: const Text(
                '정시·빠듯·지연 판정과 리포트에 쓰여요. 더보기의 "이동 기록 저장"이 함께 켜지고, 나중에 끌 수 있어요.',
                style: TextStyle(fontSize: 12),
              ),
            ),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(dialogContext, false),
            child: const Text('취소'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(dialogContext, true),
            child: const Text('완료'),
          ),
        ],
      ),
    ),
  );
  if (confirmed != true) return null;

  try {
    if (keepRecord) {
      // 계정 동의가 꺼져 있으면 서버가 도착 시각을 그냥 버린다. 체크한 뜻이 반영되도록 먼저 켠다.
      // 이미 켜져 있으면 서버가 최초 동의 시각을 그대로 두므로 여러 번 불러도 안전하다.
      await userApi.update(historyConsent: true);
    }
    final record = await arrivalApi.record(
      scheduleId,
      actualArrival: keepRecord ? arrivedAt : null,
      locationConsent: keepRecord,
    );
    if (context.mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(
            keepRecord ? '도착 기록을 저장했어요.' : '일정을 완료했어요. 도착 시각은 남기지 않았어요.',
          ),
        ),
      );
    }
    return record;
  } on Object {
    if (context.mounted) {
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('도착 기록을 저장하지 못했어요.')));
    }
    return null;
  }
}
