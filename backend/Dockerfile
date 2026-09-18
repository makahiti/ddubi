# backend 배포 이미지 (인프라 소유 — backend/docker-compose.dev.yml 주석의 합의대로)
# 2단계 빌드: Maven 으로 jar 를 만들고, 실행 이미지에는 JRE + jar 만 담는다.
# 테스트는 여기서 돌리지 않는다 (Testcontainers 가 도커를 요구. 테스트는 CI 몫)

# --- 1단계: 빌드 ---------------------------------------------------------
FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /build

# 의존성 층 분리: pom 이 안 바뀌면 의존성 다운로드가 캐시로 재사용된다
COPY backend/pom.xml ./pom.xml
RUN mvn -q -B dependency:go-offline

COPY backend/src ./src
# 공통 폴더의 지하철 원본을 Maven 리소스 경로에 제공한다. JAR에는 transit/로 포함된다.
COPY backend/transit-master/subway ./transit-master/subway
RUN mvn -q -B package -DskipTests

# --- 2단계: 실행 ---------------------------------------------------------
FROM eclipse-temurin:17-jre-jammy

# 헬스체크용 curl (jre 이미지에는 없음)
RUN apt-get update -qq && apt-get install -y -qq --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

RUN useradd --system --no-create-home ddubi
USER ddubi
WORKDIR /app
COPY --from=build /build/target/*.jar app.jar

EXPOSE 8080
# JAVA_OPTS 는 compose 에서 주입 (힙 크기 등)
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
