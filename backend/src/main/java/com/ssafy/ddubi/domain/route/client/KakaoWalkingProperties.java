package com.ssafy.ddubi.domain.route.client;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 공식 카카오맵 도보 API 설정. 키는 기존 KAKAO_REST_API_KEY 설정을 공유한다. */
@Component
@ConfigurationProperties(prefix = "ddubi.walking")
public class KakaoWalkingProperties {
    private boolean enabled;
    private String baseUrl = "https://dapi.kakao.com";
    private String path = "/v2/routing/walk";
    private int timeoutMillis = 1500;
    private int cacheSeconds = 60;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    public int getTimeoutMillis() { return timeoutMillis; }
    public void setTimeoutMillis(int timeoutMillis) { this.timeoutMillis = timeoutMillis; }
    public int getCacheSeconds() { return cacheSeconds; }
    public void setCacheSeconds(int cacheSeconds) { this.cacheSeconds = cacheSeconds; }
}
