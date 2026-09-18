import 'package:ddubi/services/time_based_app_icon_service.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  group('TimeBasedAppIconService.iconFor', () {
    test('uses the day icon from 06:00 through 17:59', () {
      expect(
        TimeBasedAppIconService.iconFor(DateTime(2026, 9, 9, 6)),
        AppIconType.day,
      );
      expect(
        TimeBasedAppIconService.iconFor(DateTime(2026, 9, 9, 9)),
        AppIconType.day,
      );
      expect(
        TimeBasedAppIconService.iconFor(DateTime(2026, 9, 9, 15)),
        AppIconType.day,
      );
      expect(
        TimeBasedAppIconService.iconFor(DateTime(2026, 9, 9, 17, 59)),
        AppIconType.day,
      );
    });

    test('uses the night icon from 18:00 through 05:59', () {
      expect(
        TimeBasedAppIconService.iconFor(DateTime(2026, 9, 9, 18)),
        AppIconType.night,
      );
      expect(
        TimeBasedAppIconService.iconFor(DateTime(2026, 9, 9, 23)),
        AppIconType.night,
      );
      expect(
        TimeBasedAppIconService.iconFor(DateTime(2026, 9, 9, 3)),
        AppIconType.night,
      );
      expect(
        TimeBasedAppIconService.iconFor(DateTime(2026, 9, 9, 5, 59)),
        AppIconType.night,
      );
    });
  });
}
