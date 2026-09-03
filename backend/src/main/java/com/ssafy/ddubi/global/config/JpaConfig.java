package com.ssafy.ddubi.global.config;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * 영속성은 Spring Data JPA. 스키마의 진실은 Flyway 이고 Hibernate 는 검증만 한다
 * (application.yml 의 ddl-auto: validate).
 *
 * 엔티티와 리포지토리는 도메인 패키지 안에 둔다:
 *   domain.{도메인}.entity / domain.{도메인}.repository
 */
@Configuration
@EnableJpaAuditing(dateTimeProviderRef = "auditingDateTimeProvider")
@EntityScan(basePackages = "com.ssafy.ddubi.domain")
@EnableJpaRepositories(basePackages = "com.ssafy.ddubi.domain")
public class JpaConfig {

    /**
     * created_at / updated_at 을 <b>마이크로초로 잘라서</b> 만든다.
     *
     * Java 의 now() 는 나노초인데 MySQL DATETIME(6) 은 마이크로초다. 자르지 않으면
     * "방금 저장한 엔티티의 updatedAt" 과 "DB 에서 다시 읽은 updatedAt" 이 달라져서,
     * updated_at 을 낙관적 잠금 비교값으로 쓰는 SCH-004 가 정상 요청을 409 로 거절한다.
     */
    @Bean
    public DateTimeProvider auditingDateTimeProvider() {
        return () -> Optional.of(LocalDateTime.now().truncatedTo(ChronoUnit.MICROS));
    }
}
