# 수도권 지하철 마스터 DB 적재

`SubwayImportTool.java`는 2026-09-16에 확인한 고정 SHA 원본의 일회성 관리 적재 도구다. 일반 앱 시작 시 실행하지 않는다.

- 선행 조건: Flyway V7 적용. 기존 V1~V6 이력은 수정하지 않는다.
- 원본: `/opt/ddubi/transit-master/subway/metropolitan-subway-master.json.gz`.
- 도구가 원본 SHA와 배포된 `ResourceSubwayMasterDataProvider` 검증을 통과한 뒤 적재한다.
- 환승역의 정적 역 ID는 유지한다. 호선별 실시간 역 ID는 V7의 `seoul_subway_line_station.realtime_station_id`에 저장한다. 역 단위 대표 ID를 임의 선택하지 않는다.
- 기존 역 단위 crosswalk는 JDBC 조회 시 fallback으로 유지된다.
- 기본 실행은 트랜잭션 롤백 검증이다. `--commit`만 실제 적재한다.
- 이미 적재된 버전은 덮어쓰지 않는다. 동일 SHA의 Resource 캐시용 빈 STAGING 행만 재사용한다.
- 배포된 JDBC Provider로 모든 노선/역 연결과 별칭을 다시 읽어 원본 객체와 완전 일치해야 ACTIVE로 전환한다.
- 운영 환경변수는 JVM 내부에서만 읽으며 출력하지 않는다. 별도 비밀번호 파일을 만들지 않는다.

실행 예시(관리 승인 및 대상 서버 확인 후):

```sh
javac --release 17 -encoding UTF-8 SubwayImportTool.java
# class 파일을 backend 컨테이너 /tmp/ddubi-master-import/에 복사한 뒤:
docker exec backend java -Xmx384m \
  -Dloader.path=/tmp/ddubi-master-import -Dloader.main=SubwayImportTool \
  -cp /app/app.jar org.springframework.boot.loader.launch.PropertiesLauncher
# 위 롤백 검증 성공 후 동일 명령 끝에 --commit 을 추가한다.
```

확인 결과: 버전 `metro-20260910-b192e5734f46814a`, 429개 패턴, 714개 정적 역, 11,040개 노선-역 연결, 별칭 1건.

이 적재는 **topology/crosswalk** 적재다. 열차 운행 시간표가 생기는 것은 아니다. 현재 `@Primary` Resource Provider는 파일을 계속 읽으며 DB Provider와 같은 topology임을 검증했다. 지하철 시간표 미설정 시 기존 API 시간으로 복귀하는 동작은 유지한다.
