package com.ssafy.ddubi.global.config;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * 영속성은 Spring Data JPA. 스키마의 진실은 Flyway 이고 Hibernate 는 검증만 한다
 * (application.yml 의 ddl-auto: validate).
 *
 * 엔티티와 리포지토리는 도메인 패키지 안에 둔다:
 *   domain.{도메인}.entity / domain.{도메인}.repository
 */
@Configuration
@EnableJpaAuditing
@EntityScan(basePackages = "com.ssafy.ddubi.domain")
@EnableJpaRepositories(basePackages = "com.ssafy.ddubi.domain")
public class JpaConfig {
}
