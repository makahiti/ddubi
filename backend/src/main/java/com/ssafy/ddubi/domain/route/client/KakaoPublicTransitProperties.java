package com.ssafy.ddubi.domain.route.client;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "ddubi.external.kakao")
public class KakaoPublicTransitProperties {

    // application.yml의 ddubi.external.kakao 아래 값을 이 필드들에 자동으로 연결한다.
    private String baseUrl = "https://dapi.kakao.com";
    private String publicTransitPath = "/v2/routing/publictraffic";

    // 실제 키는 소스코드가 아니라 KAKAO_REST_API_KEY 환경변수에서 들어온다.
    private String restApiKey = "";

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getPublicTransitPath() {
        return publicTransitPath;
    }

    public void setPublicTransitPath(String publicTransitPath) {
        this.publicTransitPath = publicTransitPath;
    }

    public String getRestApiKey() {
        return restApiKey;
    }

    public void setRestApiKey(String restApiKey) {
        this.restApiKey = restApiKey;
    }
}
