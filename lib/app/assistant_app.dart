import 'package:flutter/material.dart';
import '../presentation/assistant_screen.dart';

class AssistantApp extends StatelessWidget {
  const AssistantApp({super.key});
  @override
  Widget build(BuildContext context) => MaterialApp(
    title: 'Aiko',
    debugShowCheckedModeBanner: false,
    theme: ThemeData(
      colorSchemeSeed: const Color(0xFF238BE6),
      useMaterial3: true,
      scaffoldBackgroundColor: const Color(0xFFF8FAFE),
      cardTheme: const CardThemeData(color: Colors.white, elevation: 0),
    ),
    home: const AssistantScreen(),
  );
}
