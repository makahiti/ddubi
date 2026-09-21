package com.ssafy.ddubi.global.config;

import com.ssafy.ddubi.global.util.BusinessTime;
import java.util.Optional;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * 영속성은 Spring Data JPA. 스키마의 진실은 Flyway 이고 Hibernate 는 검증만 한다 (application.yml 의 ddl-auto:
 * validate).
 *
 * <p>엔티티와 리포지토리는 도메인 패키지 안에 둔다: domain.{도메인}.entity / domain.{도메인}.repository
 */
@Configuration
@EnableJpaAuditing(dateTimeProviderRef = "auditingDateTimeProvider")
@EntityScan(basePackages = "com.ssafy.ddubi.domain")
@EnableJpaRepositories(basePackages = "com.ssafy.ddubi.domain")
public class JpaConfig {

    /**
     * created_at / updated_at 을 <b>마이크로초로 잘라서</b> 만든다.
     *
     * <p>Java 의 now() 는 나노초인데 MySQL DATETIME(6) 은 마이크로초다. 자르지 않으면 "방금 저장한 엔티티의 updatedAt" 과 "DB 에서
     * 다시 읽은 updatedAt" 이 달라져서, updated_at 을 낙관적 잠금 비교값으로 쓰는 SCH-004 가 정상 요청을 409 로 거절한다.
     *
     * <p>시간대도 {@link BusinessTime} 과 같아야 한다. 감사 컬럼만 JVM 기본 시간대로 찍히면 CI(UTC)에서 created_at 이 업무 시각보다
     * 9시간 이르게 남아, 둘을 비교하는 로직이 뒤집힌다.
     */
    @Bean
    public DateTimeProvider auditingDateTimeProvider() {
        return () -> Optional.of(BusinessTime.nowMicros());
    }
}
