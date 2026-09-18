# Notification

Java·Spring Boot 알림 서비스 자리입니다.

Kafka 알림 토픽을 소비하고 FCM 푸시를 전송합니다.
Backend와 동일한 Java·Gradle 기준으로 프로젝트를 생성합니다.

## 2026-09-09 결정: 당장 만들지 않습니다

알림을 만드는 곳이 backend 하나라서, backend 가 FCM 으로 **직접** 보내기로 했습니다(`backend/.../global/push/`).
사용자 요청을 막지 않도록 비동기로 보내고, 실패는 notification 행에 남겨 재시도합니다.
Firebase 키는 GitLab CI/CD 변수 `FIREBASE_CREDENTIALS_BASE64` → 배포 잡 → 서버2 `infra/.env.backend` 로 전달됩니다.

이 폴더와 Kafka 토픽 `notification.requested` 는 알림 원천이 늘어나(예: Spark 판정) 별도 서비스가 필요해질 때를 위해 남겨 둡니다.
