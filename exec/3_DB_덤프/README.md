# 3. DB 덤프 (최신본)

| 항목 | 값 |
|---|---|
| 파일 | `ddubi_dump_20260927.zip` (압축 약 37MB, 풀면 `ddubi_dump_20260927.sql` 약 193MB) |
| 만든 시각 | 2026-09-27 21:53 KST |
| 원본 | 서버2 운영 MySQL 8.4.11, 데이터베이스 `ddubi` |
| 만든 방법 | `mysqldump --single-transaction` (서비스를 멈추지 않고 한 시점 기준으로 뜸) |
| 스키마 버전 | Flyway V15 (`flyway_schema_history` 포함) |
| 풀린 SQL 파일 지문 (SHA-256) | `21D538A111A470798EFB7FCDEFC6AF81F6C150C565D8C2E48C5B9D4F989027DC` |

## 들어 있는 것

- 표 **35개 전부의 구조**(CREATE TABLE, 인덱스, 외래키)
- 아래 "뺀 자료"를 제외한 모든 표의 실제 자료: 일정, 경로, 경로 구간, 평가 결과, 알림, 반복 일정, 리포트, 지하철·버스 마스터, 기준선, 설정 등

## 뺀 자료 (구조는 있고 행만 비어 있음)

운영 DB 전체는 약 14GB라 저장소에 올릴 수 없어 큰 표 세 개의 행을 뺐다.

| 표 | 크기 | 내용 | 다시 채우는 법 |
|---|---|---|---|
| `bus_reference_row` | 약 876만 행, 8GB | 버스 구간 과거 소요시간 참고자료 | 업로드 도구로 다시 적재 ([포팅 매뉴얼 7-6](../1_포팅_매뉴얼.md#7-6-버스-과거-참고자료-서버2)) |
| `bus_reference_chunk` | 1만 행 | 위 참고자료의 업로드 묶음 기록 | 위와 함께 채워진다 (행과 묶음 기록이 어긋나지 않게 같이 뺐다) |
| `recommendation` | 약 7천 행, 5.7GB | 추천 계산 기록 (한 행에 계산 근거가 수백 KB) | 서버가 일정마다 새로 계산해 만든다. 이 표를 가리키는 알림 행은 0건이라 연결이 끊기지 않는다 |

## 가린 개인정보

원본 값은 파일에 들어 있지 않다.

| 표.칸 | 바꾼 값 |
|---|---|
| `user.email` | `user{번호}@example.com` |
| `user.nickname` | `사용자{번호}` |
| `user.mobility_note` | NULL |
| `user_auth.provider_user_id` (카카오·구글·네이버 회원 번호) | `masked-{공급자}-{번호}` |
| `user_auth.password_hash` | NULL |
| `device_token.fcm_token` (휴대폰 알림 토큰) | `masked-token-{번호}`, `is_valid = 0` |

- 그래서 **덤프 속 기존 계정으로는 로그인할 수 없다.** 복원한 뒤 앱에서 새로 가입해 쓴다.
- 가린 뒤 파일 전체에서 이메일·전화번호 모양의 값이 없음을 검사했다.
- 일정의 출발지·도착지 이름과 좌표는 팀원이 시험으로 만든 일정이라 그대로 두었다.

## 복원 방법

```bash
# 1) 압축 풀기
unzip ddubi_dump_20260927.zip            # 윈도는 탐색기에서 "압축 풀기"

# 2) MySQL 8.4 에 넣기 (파일 안에 CREATE DATABASE ddubi / USE ddubi 가 들어 있다)
mysql -uroot -p --default-character-set=utf8mb4 < ddubi_dump_20260927.sql

# 서버2 컨테이너에 넣을 때 (새 서버·빈 DB 기준. 운영 DB 에 덮어쓰지 말 것)
docker exec -i mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" --default-character-set=utf8mb4' < ddubi_dump_20260927.sql
```

- 복원한 뒤 백엔드를 띄우면 Flyway가 V15까지 적용된 것으로 보고 넘어간다.
- 앱 계정 `ddubi`는 MySQL 컨테이너가 처음 뜰 때 `.env`의 `MYSQL_USER`/`MYSQL_PASSWORD`로 만든다. 덤프에는 MySQL 계정이 들어 있지 않다. 감시용 `exporter` 계정은 [포팅 매뉴얼 5-6](../1_포팅_매뉴얼.md#5-6-처음-한-번만-하는-일-배포-잡이-해-주지-않음)대로 따로 만든다.
