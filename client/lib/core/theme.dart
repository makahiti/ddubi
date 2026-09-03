import 'package:flutter/material.dart';

abstract final class DdubiColors {
  static const blue = Color(0xFF00A99D),
      navy = Color(0xFF10244B),
      green = Color(0xFF079455),
      orange = Color(0xFFF97316),
      red = Color(0xFFE5484D),
      border = Color(0xFFDDE3EC),
      surface = Color(0xFFF7F9FC);
}

abstract final class DdubiTheme {
  static ThemeData get light => ThemeData(
    useMaterial3: true,
    colorScheme: ColorScheme.fromSeed(
      seedColor: DdubiColors.blue,
      primary: DdubiColors.blue,
      secondary: const Color(0xFF2377E8),
      surface: Colors.white,
    ),
    scaffoldBackgroundColor: Colors.white,
    textTheme: const TextTheme(
      headlineLarge: TextStyle(
        fontSize: 32,
        fontWeight: FontWeight.w800,
        color: DdubiColors.navy,
      ),
      headlineMedium: TextStyle(
        fontSize: 25,
        fontWeight: FontWeight.w800,
        color: DdubiColors.navy,
      ),
      titleLarge: TextStyle(
        fontSize: 20,
        fontWeight: FontWeight.w800,
        color: DdubiColors.navy,
      ),
      titleMedium: TextStyle(
        fontSize: 16,
        fontWeight: FontWeight.w700,
        color: DdubiColors.navy,
      ),
      bodyMedium: TextStyle(
        fontSize: 14,
        height: 1.4,
        color: Color(0xFF344054),
      ),
      bodySmall: TextStyle(
        fontSize: 12,
        height: 1.35,
        color: Color(0xFF667085),
      ),
    ),
    cardTheme: const CardThemeData(
      elevation: 0,
      color: Colors.white,
      margin: EdgeInsets.zero,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.all(Radius.circular(18)),
        side: BorderSide(color: DdubiColors.border),
      ),
    ),
    inputDecorationTheme: const InputDecorationTheme(
      filled: true,
      fillColor: Colors.white,
      contentPadding: EdgeInsets.symmetric(horizontal: 14, vertical: 13),
      border: OutlineInputBorder(
        borderRadius: BorderRadius.all(Radius.circular(10)),
        borderSide: BorderSide(color: DdubiColors.border),
      ),
      enabledBorder: OutlineInputBorder(
        borderRadius: BorderRadius.all(Radius.circular(10)),
        borderSide: BorderSide(color: DdubiColors.border),
      ),
    ),
  );
}

/// Keeps scroll content stable when the user drags past either end of a list.
class DdubiScrollBehavior extends MaterialScrollBehavior {
  const DdubiScrollBehavior();

  @override
  Widget buildOverscrollIndicator(
    BuildContext context,
    Widget child,
    ScrollableDetails details,
  ) => child;
}
