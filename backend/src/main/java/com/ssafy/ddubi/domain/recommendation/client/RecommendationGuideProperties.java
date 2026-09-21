package com.ssafy.ddubi.domain.recommendation.client;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** GMS 추천 카드 문장 생성 설정. API 키는 환경변수로만 받고 응답·로그에 노출하지 않는다. */
@ConfigurationProperties(prefix = "ddubi.recommendation-guide")
public record RecommendationGuideProperties(
        boolean enabled,
        String baseUrl,
        String path,
        String apiKey,
        String model,
        int timeoutMillis) {}
