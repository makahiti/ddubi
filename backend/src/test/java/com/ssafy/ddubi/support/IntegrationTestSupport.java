package com.ssafy.ddubi.support;

import com.ssafy.ddubi.domain.auth.client.KakaoOAuthClient;
import com.ssafy.ddubi.domain.auth.service.EmailVerificationStore;
import com.ssafy.ddubi.global.mail.EmailSender;
import com.ssafy.ddubi.global.push.PushSender;
import com.ssafy.ddubi.domain.auth.entity.AuthProvider;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.mockito.BDDMockito.given;

/**
 * 통합 테스트 공통 기반.
 *
 * <p>실제 MySQL 8 과 Redis 컨테이너를 띄운다. H2 로 대체하지 않는 이유:
 * 우리 스키마는 MySQL 의 ENUM·ON DELETE CASCADE·UNIQUE(NULL 중복 허용) 동작에 기대고 있고,
 * <b>검증하려는 도메인 규칙 4(탈퇴 CASCADE)가 바로 그 DB 동작 자체</b>다.
 *
 * <p>컨테이너는 static 이라 테스트 클래스마다 새로 뜨지 않고 JVM 당 한 번만 뜬다.
 * 대신 데이터가 남으므로 각 테스트 시작 전에 {@link DatabaseCleaner} 로 비운다.
 *
 * <p>카카오 클라이언트는 목으로 대체한다. 인가 코드는 사람이 브라우저에서 로그인해야 나오는
 * 1회용 값이라 자동화할 수 없다. 검증 대상은 카카오 통신이 아니라 그 뒤의 계정 통합 로직이다.
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class IntegrationTestSupport {

    /**
     * CI 에서 GitLab {@code services:} 가 붙여준 DB 주소.
     *
     * 둘 다 있으면 Testcontainers 를 띄우지 않고 이쪽에 붙는다. 컨테이너를 직접 띄우려면
     * Docker 데몬이 필요한데(privileged 러너), services 로 받으면 그게 없어도 된다.
     * 로컬은 이 값이 없으므로 지금까지처럼 Testcontainers 로 돈다.
     */
    private static final String CI_MYSQL_HOST = System.getenv("CI_MYSQL_HOST");
    private static final String CI_REDIS_HOST = System.getenv("CI_REDIS_HOST");
    private static final boolean USE_CI_SERVICES = CI_MYSQL_HOST != null && CI_REDIS_HOST != null;

    private static final MySQLContainer<?> MYSQL = USE_CI_SERVICES ? null
            : new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
                    .withDatabaseName("ddubi")
                    .withUsername("ddubi")
                    .withPassword("ddubi")
                    .withCommand(
                            "--character-set-server=utf8mb4",
                            "--collation-server=utf8mb4_0900_ai_ci",
                            "--default-time-zone=+09:00")
                    .withReuse(true);

    private static final GenericContainer<?> REDIS = USE_CI_SERVICES ? null
            : new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379)
                    .withReuse(true);

    static {
        if (!USE_CI_SERVICES) {
            MYSQL.start();
            REDIS.start();
        }
    }

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        if (USE_CI_SERVICES) {
            // 시간대·문자셋은 services 정의에서 맞춰 준다(docs/contracts/backend-ci-requirements.md).
            // 어긋나면 시각 비교 테스트가 9시간 밀리고 한글 비교가 깨진다.
            registry.add("spring.datasource.url", () -> "jdbc:mysql://" + CI_MYSQL_HOST
                    + ":3306/ddubi?useSSL=false&allowPublicKeyRetrieval=true"
                    + "&serverTimezone=Asia/Seoul&characterEncoding=UTF-8");
            registry.add("spring.datasource.username", () -> "ddubi");
            registry.add("spring.datasource.password", () -> "ddubi");
            registry.add("spring.data.redis.host", () -> CI_REDIS_HOST);
            registry.add("spring.data.redis.port", () -> 6379);
            return;
        }
        registry.add("spring.datasource.url", () ->
                MYSQL.getJdbcUrl() + "?serverTimezone=Asia/Seoul&characterEncoding=UTF-8");
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @MockitoBean
    protected KakaoOAuthClient kakaoOAuthClient;

    /** 테스트가 실제로 메일을 보내지 않게 막는다. 발송 내용 검증에도 쓴다. */
    @MockitoBean
    protected EmailSender emailSender;

    /** 테스트가 실제로 푸시를 보내지 않게 막는다. 발송 결과를 바꿔가며 검증한다. */
    @MockitoBean
    protected PushSender pushSender;

    @org.springframework.beans.factory.annotation.Autowired
    private DatabaseCleaner databaseCleaner;

    @org.springframework.beans.factory.annotation.Autowired
    private StringRedisTemplate redisTemplate;

    @org.springframework.beans.factory.annotation.Autowired
    protected EmailVerificationStore emailVerificationStore;

    @BeforeEach
    void resetState() {
        databaseCleaner.truncateAll();
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
        // AuthService 가 호출 시점에 provider 로 클라이언트를 찾으므로 매번 다시 스텁한다.
        given(kakaoOAuthClient.provider()).willReturn(AuthProvider.KAKAO);
    }

    /**
     * 이메일 인증을 통과한 상태로 만든다.
     *
     * 로컬 가입의 전제 조건이라 가입을 쓰는 테스트는 모두 이걸 먼저 부른다.
     * 인증 절차 자체는 EmailVerificationIntegrationTest 가 따로 검증한다.
     */
    protected void givenEmailVerified(String email) {
        emailVerificationStore.markVerified(email, java.time.Duration.ofMinutes(30));
    }
}
