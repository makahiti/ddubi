import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

class HelpInquiryScreen extends StatefulWidget {
  const HelpInquiryScreen({super.key});

  @override
  State<HelpInquiryScreen> createState() => _HelpInquiryScreenState();
}

class _HelpInquiryScreenState extends State<HelpInquiryScreen> {
  final _inquiryController = TextEditingController();
  String _category = '앱 이용';

  static const _faqs = <(String, String)>[
    ('출발 알림은 언제 받을 수 있나요?', '일정과 출발지가 등록되어 있고 알림 권한이 허용되면 추천 출발 시간에 맞춰 안내해요.'),
    ('추천 경로는 어떻게 정해지나요?', '예상 이동 시간과 실시간 이동 변수, 설정한 교통수단을 함께 반영해 경로를 추천해요.'),
    ('알림이 오지 않아요.', '더보기의 알림 권한이 허용됨인지 확인하고, 기기의 뚜비 알림 설정도 함께 확인해 주세요.'),
    ('저장 장소는 어디에서 관리하나요?', '더보기 화면의 저장 장소 관리에서 자주 가는 장소를 추가하거나 수정할 수 있어요.'),
  ];

  @override
  void dispose() {
    _inquiryController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    backgroundColor: const Color(0xFFF5FBFD),
    appBar: AppBar(
      backgroundColor: const Color(0xFFF5FBFD),
      surfaceTintColor: Colors.transparent,
      title: const Text('도움말 · 문의하기'),
    ),
    body: SafeArea(
      child: ListView(
        padding: const EdgeInsets.fromLTRB(16, 8, 16, 28),
        children: [
          _card(
            child: const Row(
              children: [
                CircleAvatar(
                  radius: 25,
                  backgroundColor: Color(0xFFDDF8F3),
                  child: Icon(
                    Icons.support_agent_rounded,
                    color: Color(0xFF0CB3A2),
                    size: 29,
                  ),
                ),
                SizedBox(width: 13),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        '무엇을 도와드릴까요?',
                        style: TextStyle(
                          color: Color(0xFF102454),
                          fontSize: 19,
                          fontWeight: FontWeight.w900,
                        ),
                      ),
                      SizedBox(height: 4),
                      Text(
                        '자주 묻는 내용을 확인하거나 문의 내용을 준비해 보세요.',
                        style: TextStyle(color: Color(0xFF64708B), height: 1.4),
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(height: 16),
          const Text(
            '자주 묻는 질문',
            style: TextStyle(
              color: Color(0xFF102454),
              fontSize: 18,
              fontWeight: FontWeight.w900,
            ),
          ),
          const SizedBox(height: 10),
          _card(
            padding: EdgeInsets.zero,
            child: Column(
              children: [
                for (var index = 0; index < _faqs.length; index++) ...[
                  ExpansionTile(
                    tilePadding: const EdgeInsets.symmetric(horizontal: 16),
                    childrenPadding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
                    iconColor: const Color(0xFF0CB3A2),
                    collapsedIconColor: const Color(0xFF64708B),
                    title: Text(
                      _faqs[index].$1,
                      style: const TextStyle(
                        color: Color(0xFF102454),
                        fontWeight: FontWeight.w800,
                      ),
                    ),
                    children: [
                      Align(
                        alignment: Alignment.centerLeft,
                        child: Text(
                          _faqs[index].$2,
                          style: const TextStyle(
                            color: Color(0xFF64708B),
                            height: 1.55,
                          ),
                        ),
                      ),
                    ],
                  ),
                  if (index != _faqs.length - 1)
                    const Divider(height: 1, indent: 16, endIndent: 16),
                ],
              ],
            ),
          ),
          const SizedBox(height: 20),
          const Text(
            '문의 내용 준비하기',
            style: TextStyle(
              color: Color(0xFF102454),
              fontSize: 18,
              fontWeight: FontWeight.w900,
            ),
          ),
          const SizedBox(height: 6),
          const Text(
            '문의 전송 채널이 연결되기 전까지 작성한 내용을 복사해 보관할 수 있어요.',
            style: TextStyle(color: Color(0xFF64708B), fontSize: 13),
          ),
          const SizedBox(height: 10),
          _card(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                DropdownButtonFormField<String>(
                  initialValue: _category,
                  decoration: const InputDecoration(
                    labelText: '문의 유형',
                    prefixIcon: Icon(Icons.category_outlined),
                  ),
                  items: const ['앱 이용', '일정 · 알림', '경로 추천', '계정', '기타']
                      .map(
                        (value) =>
                            DropdownMenuItem(value: value, child: Text(value)),
                      )
                      .toList(),
                  onChanged: (value) =>
                      setState(() => _category = value ?? _category),
                ),
                const SizedBox(height: 12),
                TextField(
                  key: const Key('inquiryMessageField'),
                  controller: _inquiryController,
                  minLines: 5,
                  maxLines: 8,
                  maxLength: 500,
                  decoration: const InputDecoration(
                    labelText: '문의 내용',
                    hintText: '궁금한 점과 발생한 상황을 자세히 적어주세요.',
                    alignLabelWithHint: true,
                  ),
                ),
                const SizedBox(height: 8),
                FilledButton.icon(
                  key: const Key('copyInquiryButton'),
                  onPressed: _copyInquiry,
                  style: FilledButton.styleFrom(
                    backgroundColor: const Color(0xFF10B8A5),
                    minimumSize: const Size.fromHeight(50),
                    shape: const StadiumBorder(),
                  ),
                  icon: const Icon(Icons.copy_rounded),
                  label: const Text('문의 내용 복사하기'),
                ),
              ],
            ),
          ),
        ],
      ),
    ),
  );

  Widget _card({
    required Widget child,
    EdgeInsetsGeometry padding = const EdgeInsets.all(17),
  }) => Material(
    color: Colors.white,
    elevation: 1,
    shadowColor: const Color(0x1A1A6275),
    shape: RoundedRectangleBorder(
      borderRadius: BorderRadius.circular(22),
      side: const BorderSide(color: Color(0xFFE4EDF2)),
    ),
    clipBehavior: Clip.antiAlias,
    child: Padding(padding: padding, child: child),
  );

  Future<void> _copyInquiry() async {
    final message = _inquiryController.text.trim();
    if (message.isEmpty) {
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('문의 내용을 먼저 입력해주세요.')));
      return;
    }
    await Clipboard.setData(ClipboardData(text: '[$_category 문의]\n$message'));
    if (mounted) {
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('문의 내용을 복사했어요.')));
    }
  }
}
