package com.ssafy.ddubi.domain.pipeline.entity;

import com.ssafy.ddubi.global.util.BusinessTime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 파이프라인 설정 포인터 (key-value 한 줄).
 *
 * <p>지금 담는 것은 "어느 기준선 버전을 서빙 중인가" 하나다. 테이블을 따로 둔 이유는
 * <b>업로드와 활성화를 분리</b>하기 위해서다. 기준선 행은 새 버전으로 계속 쌓이고,
 * 추천은 이 포인터가 가리키는 버전만 읽는다. 그래서 절반만 올라간 데이터가
 * 서빙되는 일이 없다.
 *
 * <p>id 가 없고 {@code config_key} 가 곧 PK 다.
 */
@Entity
@Table(name = "pipeline_config")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PipelineConfig {

    @Id
    @Column(name = "config_key", length = 50)
    private String configKey;

    @Column(name = "config_value", nullable = false, length = 100)
    private String configValue;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    private PipelineConfig(String configKey, String configValue) {
        this.configKey = configKey;
        this.configValue = configValue;
        this.updatedAt = BusinessTime.nowMicros();
    }

    public static PipelineConfig of(String key, String value) {
        return new PipelineConfig(key, value);
    }

    public void changeValue(String value) {
        this.configValue = value;
        this.updatedAt = BusinessTime.nowMicros();
    }
}
