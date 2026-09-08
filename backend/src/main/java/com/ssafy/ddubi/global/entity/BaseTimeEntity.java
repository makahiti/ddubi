package com.ssafy.ddubi.global.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 전 테이블 공통 created_at / updated_at.
 *
 * DDL 에도 DEFAULT CURRENT_TIMESTAMP / ON UPDATE 가 걸려 있지만 그건 안전망이다.
 * 값을 정하는 주체는 서버다(BACKEND_CONVENTION.md 도메인 규칙 12) — JPA 가 명시값을 쓰므로
 * DB 의 ON UPDATE 는 발동하지 않는다.
 *
 * updated_at 은 일정 수정의 낙관적 잠금 비교값으로도 쓰이므로 갱신 주체가 하나여야 한다.
 */
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseTimeEntity {

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
