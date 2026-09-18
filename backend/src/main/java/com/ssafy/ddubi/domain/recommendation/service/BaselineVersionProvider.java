package com.ssafy.ddubi.domain.recommendation.service;

import com.ssafy.ddubi.domain.pipeline.entity.PipelineConfig;
import com.ssafy.ddubi.domain.pipeline.entity.PipelineConfigKey;
import com.ssafy.ddubi.domain.pipeline.repository.PipelineConfigRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 지금 서빙 중인 기준선 버전을 알려 준다 ({@code pipeline_config.baseline_active_version}).
 *
 * <h3>왜 따로 두나</h3>
 * 추천 계산은 <b>한 번의 계산 안에서 같은 버전</b>을 봐야 한다. 계산 도중 activate 가
 * 일어나면 구간마다 다른 버전의 기준선을 섞어 쓰게 되고, 그러면 나중에 그 추천을 재현할 수 없다.
 * 그래서 계산 시작 시점에 한 번만 읽어 끝까지 들고 간다.
 *
 * <p>값이 없으면 {@code null} 이다 — 아직 기준선을 한 번도 활성화하지 않은 상태이며,
 * 지금(2026-09-10) 실제로 그렇다. 구간 기준선을 올리는 Spark 배치가 아직 없다.
 */
@Component
public class BaselineVersionProvider {

    private final PipelineConfigRepository configRepository;

    public BaselineVersionProvider(PipelineConfigRepository configRepository) {
        this.configRepository = configRepository;
    }

    @Transactional(readOnly = true)
    public String activeVersion() {
        return configRepository.findById(PipelineConfigKey.BASELINE_ACTIVE_VERSION)
                .map(PipelineConfig::getConfigValue)
                .orElse(null);
    }
}
