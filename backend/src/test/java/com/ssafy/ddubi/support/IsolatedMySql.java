package com.ssafy.ddubi.support;

import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 테스트 하나가 통째로 소유하는 빈 MySQL 을 준다.
 *
 * <p>공용 DB(IntegrationTestSupport)를 쓰지 못하는 테스트, 즉 마이그레이션 한 장만 직접 적용하거나 표를 스스로 만드는 테스트가 쓴다. DB 를 얻는
 * 길은 둘이다.
 *
 * <ul>
 *   <li>CI: GitLab {@code services:} 가 붙여 준 MySQL 에 root 로 접속해 {@code ddubi_isolated_<난수>} 데이터베이스를
 *       새로 만들고 그것을 돌려준다. 닫을 때 지운다. root 를 쓰는 이유는 일반 계정에는 CREATE DATABASE 권한이 없기 때문이다.
 *   <li>로컬: 전처럼 Testcontainers 로 mysql:8.0 컨테이너를 하나 띄운다. 닫을 때 컨테이너를 내린다.
 * </ul>
 *
 * <p>둘 다 안 되면(CI 변수도 없고 Docker 도 없으면) 예외를 던져 테스트를 실패시킨다. 전에는 Docker 가 없으면 조용히 건너뛰어(skip) 파이프라인이
 * 초록불인데 DB 검증은 하나도 안 돈 상태가 될 수 있었다. 2026-09-23 backend·인프라 합의.
 */
public final class IsolatedMySql implements AutoCloseable {
    private static final String CI_MYSQL_HOST = System.getenv("CI_MYSQL_HOST");
    private static final String CI_MYSQL_ROOT_PASSWORD =
            System.getenv().getOrDefault("CI_MYSQL_ROOT_PASSWORD", "root");
    // IntegrationTestSupport 의 CI 접속 옵션과 같은 값이다. 시간대·문자셋 처리를 공용 DB 와 같게 맞춘다.
    private static final String JDBC_OPTIONS =
            "?useSSL=false&allowPublicKeyRetrieval=true"
                    + "&connectionTimeZone=LOCAL&preserveInstants=false&characterEncoding=UTF-8";

    private final DataSource dataSource;
    private final Runnable cleanup;

    private IsolatedMySql(DataSource dataSource, Runnable cleanup) {
        this.dataSource = dataSource;
        this.cleanup = cleanup;
    }

    public static IsolatedMySql create() {
        if (CI_MYSQL_HOST != null) {
            return fromCiService();
        }
        if (DockerClientFactory.instance().isDockerAvailable()) {
            return fromContainer();
        }
        throw new IllegalStateException(
                "격리 MySQL 을 얻을 수 없다. CI 에서는 CI_MYSQL_HOST(services) 가, 로컬에서는 Docker 가 필요하다. "
                        + "이 테스트는 실제 MySQL 없이는 검증이 되지 않으므로 건너뛰지 않고 실패시킨다.");
    }

    private static IsolatedMySql fromCiService() {
        String database =
                "ddubi_isolated_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String base = "jdbc:mysql://" + CI_MYSQL_HOST + ":3306/";
        var admin =
                new DriverManagerDataSource(base + JDBC_OPTIONS, "root", CI_MYSQL_ROOT_PASSWORD);
        new JdbcTemplate(admin)
                .execute(
                        "CREATE DATABASE `"
                                + database
                                + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        var dataSource =
                new DriverManagerDataSource(
                        base + database + JDBC_OPTIONS, "root", CI_MYSQL_ROOT_PASSWORD);
        return new IsolatedMySql(
                dataSource,
                () -> new JdbcTemplate(admin).execute("DROP DATABASE `" + database + "`"));
    }

    private static IsolatedMySql fromContainer() {
        MySQLContainer<?> container = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"));
        container.start();
        var dataSource =
                new DriverManagerDataSource(
                        container.getJdbcUrl(), container.getUsername(), container.getPassword());
        return new IsolatedMySql(dataSource, container::stop);
    }

    public DataSource dataSource() {
        return dataSource;
    }

    @Override
    public void close() {
        cleanup.run();
    }
}
