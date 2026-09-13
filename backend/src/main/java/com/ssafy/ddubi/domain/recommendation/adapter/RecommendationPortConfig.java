package com.ssafy.ddubi.domain.recommendation.adapter;

import com.ssafy.ddubi.domain.recommendation.port.BusTravelTimePredictor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * 예측기 선택. 프로파일이 아니라 <b>설정값 유무</b>로 고른다 (PushConfig·MailConfig 와 같은 방식).
 *
 * <h3>지금은 항상 폴백이다</h3>
 * 예측 서비스의 HTTP 요청·응답 계약이 아직 확정 전이다(김종무, 2026-09-10 — 배치 형태로
 * 맞추기로 했고 데모 가동은 확약 불가). 계약이 오면 여기에 HTTP 구현체를 하나 추가한다.
 *
 * <p>주소가 설정돼 있는데 구현이 없으면 <b>경고를 남긴다.</b> 그게 없으면 운영자는 주소만 넣고
 * "예측이 켜졌다" 고 믿게 되는데, 실제로는 계속 기준선으로 내려간다.
 */
@Configuration
public class RecommendationPortConfig {

    private static final Logger log = LoggerFactory.getLogger(RecommendationPortConfig.class);

    private final String predictorBaseUrl;

    public RecommendationPortConfig(
            @Value("${ddubi.recommendation.predictor.base-url:}") String predictorBaseUrl) {
        this.predictorBaseUrl = predictorBaseUrl;
    }

    @Bean
    public BusTravelTimePredictor busTravelTimePredictor() {
        if (StringUtils.hasText(predictorBaseUrl)) {
            log.warn("예측 서비스 주소({})가 설정되었지만 HTTP 구현이 아직 없습니다 "
                    + "— 계약 확정 전이라 기준선·경로 API 로 계산합니다", predictorBaseUrl);
        } else {
            log.info("예측 서비스 미설정 — 기준선·경로 API 로 계산합니다");
        }
        return new UnavailableBusTravelTimePredictor();
    }
}
