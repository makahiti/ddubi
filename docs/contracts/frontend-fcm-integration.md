# [백엔드 → 프론트] FCM 연동에 필요한 것

작성: 백엔드(서비스) 손정민 · 2026-09-04
대상: 프론트(김수원·김영빈)

백엔드 발송 쪽은 끝나 있습니다(`PushSender`·`FcmPushSender`, 무효 토큰 자동 폐기).
**프론트에서 토큰만 올려주면 알림이 실제로 도착합니다.**

---

## 0. 가장 먼저 — 프로젝트를 새로 만들지 마세요 🔴

Firebase 프로젝트는 **이미 있습니다.**

```
프로젝트 ID: ddubi-85d2b
```

백엔드가 이 프로젝트의 서비스 계정 키로 발송합니다. **다른 프로젝트에서 받은
`google-services.json` 을 쓰면 토큰 등록도 되고 앱도 정상으로 보이는데 푸시만 안 옵니다.**
FCM 이 "이 토큰은 내 프로젝트 것이 아니다" 로 거절하는데, 그 실패가 서버 로그에만 남아서
원인을 찾는 데 한참 걸립니다.

콘솔에 `ddubi-85d2b` 가 안 보이면 프로젝트를 만들지 마시고 **저에게 초대를 요청해 주세요.**

---

## 1. 들고 가실 것 — `google-services.json` 하나

Firebase 콘솔 → `ddubi-85d2b` → 프로젝트 설정 → **내 앱 → Android 앱 추가**

| 입력란 | 값 |
| --- | --- |
| Android 패키지 이름 | `com.ssafy.ddubi` ← **정확히 이 값** |
| 앱 닉네임 | 아무거나 (예: 뚜비 Android) |
| 디버그 서명 인증서 SHA-1 | **비워도 됩니다** (푸시에는 불필요) |

> 패키지 이름은 `client/android/app/build.gradle.kts:55` 의 `applicationId` 와 같아야 합니다.
> 한 글자라도 다르면 앱이 자기 설정을 못 찾습니다.

받은 파일을 여기에 둡니다.

```
client/android/app/google-services.json
```

Gradle 은 이미 준비돼 있습니다 — `build.gradle.kts:11` 이 **파일이 있을 때만** 플러그인을
적용하도록 돼 있어서, 파일을 넣는 순간 그냥 붙습니다. 빌드 설정은 건드릴 것이 없습니다.

`pubspec.yaml` 의 `firebase_core` · `firebase_messaging` 도 이미 들어가 있습니다.

### ⚠️ `google-services.json` 은 커밋하지 말아 주세요

APK 에 어차피 들어가는 파일이라 "비밀"은 아니지만, 공개 저장소에 올리면 남이 우리
프로젝트 앞으로 요청을 보낼 수 있습니다. `.gitignore` 에 추가하고 **파일은 팀 비밀 채널로
공유**해 주세요. 제가 `.gitignore` 항목은 넣어두겠습니다.

---

## 2. 절대 받아가면 안 되는 것 🔴

콘솔에 **"서비스 계정 → 새 비공개 키 생성"** 이라는 버튼이 있습니다. **누르지 마세요.**

| | 용도 | 두는 곳 |
| --- | --- | --- |
| `google-services.json` | 앱이 자기 프로젝트를 식별 | ✅ 프론트 |
| 서비스 계정 비공개 키 (`*-firebase-adminsdk-*.json`) | **누구에게든 푸시를 보낼 수 있는 권한** | ❌ 백엔드 서버만 |

서비스 계정 키가 앱에 들어가면 APK 를 뜯은 사람이 우리 사용자 전원에게 알림을 보낼 수
있습니다. 이건 백엔드 담당인 제 몫이니 프론트는 신경 쓰지 않으셔도 됩니다.

> **2026-09-04 정정**: 이 문단에 원래 "이미 서버에 넣어뒀다" 고 적었는데 **틀렸습니다.**
> 제 로컬 개발 환경에만 넣혀 있었고 **배포 서버(서버1·서버2)에는 없습니다.**
> 인프라팀 확인으로 드러났습니다. 배포 서버 설정은 별도로 진행 중이라,
> **지금 프론트가 연동해도 배포 환경에서는 푸시가 오지 않습니다.**
> 로컬 백엔드(`localhost:8080`)로는 테스트할 수 있습니다.

---

## 3. 붙일 API 2개 — 이미 배포돼 있습니다

### DEVICE-001 토큰 등록·갱신

```
PUT /api/v1/devices/token
Authorization: Bearer {accessToken}
Content-Type: application/json

{ "fcmToken": "e7X...", "platform": "ANDROID" }
```

- `platform` 은 `ANDROID` / `IOS` / `WEB`. **v1 은 `ANDROID`** 입니다
- `fcmToken` 은 최대 512자
- **upsert 입니다.** 같은 토큰을 여러 번 보내도 행이 늘지 않으니 마음 놓고 재호출하세요

### DEVICE-002 토큰 해제

```
DELETE /api/v1/devices/token
Authorization: Bearer {accessToken}
Content-Type: application/json

{ "fcmToken": "e7X..." }
```

본인 토큰만 해제됩니다.

---

## 4. 언제 호출해야 하나 — 세 시점 모두 필요합니다

```dart
// ① 로그인 직후
final token = await FirebaseMessaging.instance.getToken();
if (token != null) await api.registerDevice(token);

// ② 토큰이 갱신될 때 — 이걸 빠뜨리면 어느 날 갑자기 알림이 끊깁니다
FirebaseMessaging.instance.onTokenRefresh.listen(api.registerDevice);

// ③ 로그아웃 직전 — 순서 주의, 토큰을 지우기 전에 서버에 알려야 합니다
await api.unregisterDevice(token);
await FirebaseMessaging.instance.deleteToken();
```

②가 특히 중요합니다. FCM 토큰은 앱 재설치·데이터 삭제·장기 미사용으로 **말없이 바뀝니다.**
등록을 로그인 시점 한 번만 하면 그 뒤로 조용히 알림이 안 오게 됩니다.

> 서버는 FCM 이 `UNREGISTERED` 를 주면 그 토큰을 자동으로 무효 처리합니다.
> 죽은 토큰이 쌓이는 건 걱정하지 않으셔도 됩니다.

---

## 5. 알림 권한 (Android 13+)

Android 13 부터 알림도 런타임 권한입니다. **요청하지 않으면 아무것도 표시되지 않습니다.**

```dart
await FirebaseMessaging.instance.requestPermission();
```

권한을 거부해도 **토큰 등록은 따로 해주세요.** 나중에 설정에서 권한을 켜면 바로 알림이
오게 됩니다. 반대로 하면 권한을 켠 뒤에도 서버에 기기가 없어서 안 옵니다.

---

## 6. 알림을 탭했을 때 — 서버가 보내는 data

푸시마다 아래 값이 함께 옵니다. 이걸로 어느 화면을 열지 정하시면 됩니다.

| 키 | 값 | 비고 |
| --- | --- | --- |
| `notificationId` | 알림 id (문자열) | 알림함 상세(NTF-002)로 갈 때 |
| `type` | 아래 8종 중 하나 | 화면 분기용 |
| `scheduleId` | 일정 id (문자열) | **없을 수 있습니다** — 일정과 무관한 알림 |

```
MORNING_BRIEF     아침 브리핑
DEPART_SOON       곧 출발
EARLY_DEPART      예정보다 일찍 출발 (지연 발생)
ROUTE_CHANGE      더 나은 경로로 바뀜
ON_TIME_UNLIKELY  제시간 도착 어려움
LAST_TRIP         막차 위험
SCHEDULE_CONFLICT 연속 일정 충돌
DATA_STALE        데이터가 낡아 추천 신뢰도 하락
```

> **FCM data 값은 전부 문자열입니다.** `scheduleId` 도 `"12"` 로 오니 `int.parse` 하세요.
> 그리고 `scheduleId` 는 없을 수 있으므로 `data['scheduleId']` 를 null 체크해 주세요 —
> 일정이 지워진 알림은 서버가 `scheduleId` 를 null 로 만듭니다(FK SET NULL).

탭 처리는 두 경로를 모두 잡아야 합니다.

```dart
// 앱이 떠 있을 때
FirebaseMessaging.onMessageOpenedApp.listen(handle);
// 앱이 죽어 있다가 알림으로 켜졌을 때 — 이쪽을 빠뜨리는 경우가 많습니다
final initial = await FirebaseMessaging.instance.getInitialMessage();
if (initial != null) handle(initial);
```

---

## 7. 연동 확인 방법

Firebase 콘솔에서 테스트 푸시를 쏘는 것보다, **서버를 거쳐서 확인**하시는 편이 확실합니다.
콘솔 테스트는 서버 경로를 하나도 검증하지 못합니다.

개발 프로파일에 수동 발송 엔드포인트가 있습니다(로그인한 본인에게만 갑니다).
필요하시면 알려주세요 — 제가 쏴 드리거나 호출법을 알려드리겠습니다.

**서버 로그에 `FCM 발송 준비 완료` 가 떠 있어야** 실제로 나갑니다.
`FIREBASE_CREDENTIALS_PATH/BASE64 미설정` 이 보이면 로그로만 남고 발송되지 않습니다.

---

## 요약 — 들고 가실 것

1. Firebase 콘솔 **`ddubi-85d2b`** 에서 Android 앱 등록 (패키지 `com.ssafy.ddubi`)
2. `google-services.json` → `client/android/app/` (커밋 X, 비밀 채널로 공유)
3. 서비스 계정 비공개 키는 **받지 마세요**
4. 붙일 API 는 `PUT` / `DELETE /api/v1/devices/token` 둘뿐
5. 등록 호출은 **로그인 · 토큰 갱신 · 로그아웃** 세 시점 모두

콘솔에 프로젝트가 안 보이면 초대해 드리겠습니다. 편하게 말씀 주세요.
