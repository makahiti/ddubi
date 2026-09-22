import 'package:flutter/material.dart';

import '../core/theme.dart';

/// 홈 맨 위 인사. 시간대에 따라 말과 색이 조금씩 다르다.
///
/// 예전에는 종일 "오늘도 뚜비와 함께 좋은 하루 되세요!" 한 문장이었다. 밤 11시에 열어도
/// "좋은 하루 되세요" 라 시계를 안 보는 앱처럼 읽혔다(2026-09-22 제보). 네 시간대로 나눈다.
///
/// ```
/// 새벽  00~05시   남색 계열   막 일어난 사람에게
/// 오전  06~11시   민트·연두   지금까지 쓰던 인사 그대로
/// 오후  12~17시   파랑 계열   남은 하루
/// 밤    18~23시   회색빛 남색  하루를 마무리하는 사람에게
/// ```
///
/// 판단은 기기 시계로 한다. 서버 시각을 기다렸다가 인사하면 첫 화면이 늦는다.
class HomeGreeting {
  const HomeGreeting({
    required this.period,
    required this.message,
    required this.colors,
    required this.textColor,
  });

  /// 검사와 진단용 이름. dawn / morning / afternoon / night.
  final String period;

  /// 세 줄로 나눠 쓴다. 카드 높이가 고정이라 줄 수가 달라지면 그림과 어긋난다.
  final String message;

  /// 카드 바탕 그라디언트. 위→아래로 옅어진다.
  final List<Color> colors;
  final Color textColor;
}

HomeGreeting homeGreeting(DateTime now) {
  final hour = now.hour;
  if (hour < 6) {
    return const HomeGreeting(
      period: 'dawn',
      message: '개운하게 일어났나요?\n오늘도 뚜비와\n힘차게 시작해요!',
      colors: [Color(0xFFDDE3F7), Color(0xFFEEF1FB)],
      textColor: DdubiColors.navy,
    );
  }
  if (hour < 12) {
    return const HomeGreeting(
      period: 'morning',
      message: '오늘도\n뚜비와 함께\n좋은 하루 되세요!',
      colors: [Color(0xFFCFF6FA), Color(0xFFE8FAE9)],
      textColor: DdubiColors.navy,
    );
  }
  if (hour < 18) {
    return const HomeGreeting(
      period: 'afternoon',
      message: '남은 하루도\n뚜비와 함께\n힘차게 보내요!',
      colors: [Color(0xFFD6ECFF), Color(0xFFEAF4FF)],
      textColor: DdubiColors.navy,
    );
  }
  return const HomeGreeting(
    period: 'night',
    message: '오늘 하루 수고했어요.\n내일도 뚜비와\n함께해요!',
    colors: [Color(0xFFD9DEEA), Color(0xFFEBEEF5)],
    textColor: Color(0xFF1B2A4A),
  );
}
