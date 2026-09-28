package com.ssafy.ddubi.domain.recommendation.client;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * GMS 추천 카드 문장 생성 설정. API 키는 환경변수로만 받고 응답·로그에 노출하지 않는다.
 *
 * <p>하루 호출 상한은 두지 않는다. GMS 크레딧은 여러 기능이 함께 쓰므로 여기서 숫자로 끊으면 정상 안내도 함께 끊긴다. 크레딧이 새는 길은 {@code
 * RecommendationGuideService} 가 계산 번호마다 한 번만 부르는 규칙으로 막는다.
 */
@ConfigurationProperties(prefix = "ddubi.recommendation-guide")
public record RecommendationGuideProperties(
        boolean enabled,
        String baseUrl,
        String path,
        String apiKey,
        String model,
        int timeoutMillis) {}
