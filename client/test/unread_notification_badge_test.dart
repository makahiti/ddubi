import 'package:ddubi/domain/models.dart';
import 'package:ddubi/providers/unread_notifications.dart';
import 'package:ddubi/services/app_api.dart';
import 'package:flutter_test/flutter_test.dart';

/// 종 아이콘의 안 읽은 알림 개수.
///
/// 서버는 개수를 내려주고 앱도 받아서 담아 뒀는데 화면에 쓰는 곳이 없어서, 알림이 와도
/// 앱 안에서는 알 길이 없었다(2026-09-21 확인). 이 검사는 개수가 실제로 갱신되는지와,
/// 통신이 실패해도 이전 값을 지우지 않는지를 지킨다.
class _FakeNotificationApi implements NotificationApi {
  _FakeNotificationApi(this._counts);

  final List<Object> _counts;
  var calls = 0;

  @override
  Future<NotificationInbox> getInbox({bool unreadOnly = false}) async {
    final next = _counts[calls.clamp(0, _counts.length - 1)];
    calls++;
    if (next is Exception) throw next;
    return NotificationInbox(unreadCount: next as int, items: const []);
  }

  @override
  dynamic noSuchMethod(Invocation invocation) =>
      throw UnimplementedError('${invocation.memberName} 은 이 검사에서 쓰지 않는다');
}

void main() {
  testWidgets('서버가 준 안 읽은 개수를 그대로 들고 있는다', (tester) async {
    final api = _FakeNotificationApi([3]);
    final counter = UnreadNotifications(api);
    addTearDown(counter.dispose);

    await tester.pumpAndSettle();

    expect(counter.count, 3);
  });

  testWidgets('개수를 못 받아오면 이전 값을 지우지 않는다', (tester) async {
    // 통신이 한 번 끊겼다고 0 으로 만들면 안 읽은 알림을 숨기는 셈이 된다.
    final api = _FakeNotificationApi([5, Exception('네트워크 끊김')]);
    final counter = UnreadNotifications(api);
    addTearDown(counter.dispose);
    await tester.pumpAndSettle();
    expect(counter.count, 5);

    await counter.refresh();

    expect(counter.count, 5);
  });

  testWidgets('읽음 처리로 줄어든 개수를 바로 반영한다', (tester) async {
    final api = _FakeNotificationApi([2]);
    final counter = UnreadNotifications(api);
    addTearDown(counter.dispose);
    await tester.pumpAndSettle();

    var notified = 0;
    counter.addListener(() => notified++);
    counter.set(0);

    expect(counter.count, 0);
    expect(notified, 1);
  });

  testWidgets('음수는 0 으로 본다', (tester) async {
    final api = _FakeNotificationApi([1]);
    final counter = UnreadNotifications(api);
    addTearDown(counter.dispose);
    await tester.pumpAndSettle();

    counter.set(-3);

    expect(counter.count, 0);
  });

  test('브리핑 알림은 평가 연결을 기대하지 않는다', () {
    // 브리핑은 일정을 요약할 뿐이라 평가가 없다. 그런데 상세 화면이 모든 알림에
    // 평가가 있다고 보고 "확인할 수 없습니다" 를 띄워 고장처럼 보였다.
    AppNotification of(String type) => AppNotification(
      id: '1',
      type: type,
      title: '제목',
      body: '내용',
      status: 'SENT',
      createdAt: DateTime(2026, 9, 21),
    );

    expect(of('MORNING_BRIEF').expectsEvaluation, isFalse);
    expect(of('NIGHT_BRIEF').expectsEvaluation, isFalse);
    expect(of('ROUTE_CHANGE').expectsEvaluation, isTrue);
    expect(of('ON_TIME_UNLIKELY').expectsEvaluation, isTrue);
  });
}
