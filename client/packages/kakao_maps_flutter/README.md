# kakao_maps_flutter (DDUBI 로컬 사본)

이 폴더는 `kakao_maps_flutter` 패키지를 저장소 안으로 들여온 사본입니다.

```
원본      https://pub.dev/packages/kakao_maps_flutter
버전      0.2.2  (2026-09-22 기준 최신)
라이선스   MIT — 아래 LICENSE 파일 참고 (Copyright (c) 2024 seunghwanly)
```

## 왜 들여왔나

원본 패키지에는 **선 그리기 기능이 없습니다.** 마커·말풍선·카메라만 있습니다.

그래서 일정 상세의 경로선을 지도 위에 얹은 그림(Flutter `CustomPaint`)으로 그려 왔는데,
지도를 만지면 화면 좌표가 맞지 않아 선을 지웠다가 이동이 끝난 뒤 다시 그려야 했습니다.
사용자에게는 **만질 때마다 선이 깜빡이는 것**으로 보였습니다(2026-09-22 제보).

카카오 안드로이드 SDK(`com.kakao.maps.open:android:2.15.1`)에는 선 기능이 이미
들어 있습니다(`RouteLineLayer`, `RouteLineOptions`, `RouteLineStyle` …).
패키지가 그 기능을 Flutter 쪽으로 열어주지 않았을 뿐입니다.

패키지 안쪽을 고쳐야 열 수 있어서 사본을 두었습니다.

## 우리가 더한 것

원본과 다른 부분은 이 목록이 전부입니다.

| 어디 | 무엇 |
|---|---|
| `android/.../view/KakaoMapController.kt` | `addRouteLine` · `clearRouteLines` 처리 |
| `lib/src/controller/kakao_map_controller.dart` | 같은 이름의 Dart 메서드 |
| `lib/src/controller/interface/…` | 통로 선언 |
| `lib/src/data/routeLine/…` | 선 모양 값 객체 |

`doc/` 과 `example/` 은 1.7MB 라 들여오지 않았습니다. 필요하면 원본에서 보면 됩니다.

## 주의

- **원본이 새 버전을 내도 자동으로 따라오지 않습니다.** 올릴 때는 위 표의 파일만
  다시 얹으면 됩니다.
- iOS 쪽은 손대지 않았습니다. 이 프로젝트에는 iOS 폴더가 없어 빌드 대상이 아닙니다.
  iOS 를 시작하면 `ios/` 에 같은 기능을 더해야 합니다.
