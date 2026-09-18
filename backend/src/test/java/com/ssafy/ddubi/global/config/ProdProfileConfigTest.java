package com.ssafy.ddubi.global.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 운영 프로파일이 개발용 값으로 새지 않는지 설정 파일만 읽어 확인한다.
 * 컨테이너를 띄우지 않으므로 빠르고, CI 어디서나 돈다(2026-09-18 개발/운영 분리 점검).
 */
class ProdProfileConfigTest {

    private static Properties load(String name) {
        YamlPropertiesFactoryBean bean = new YamlPropertiesFactoryBean();
        bean.setResources(new ClassPathResource(name));
        return bean.getObject();
    }

    @Test
    @DisplayName("프로파일을 지정하지 않으면 운영(prod)으로 뜬다")
    void defaultProfileIsProd() {
        assertThat(load("application.yml").getProperty("spring.profiles.active"))
                .isEqualTo("${SPRING_PROFILES_ACTIVE:prod}");
    }

    @Test
    @DisplayName("운영 프로파일은 개발 API 를 끄고 actuator 를 health·info·prometheus 로 제한하고 비밀값 기본값이 없다")
    void prodProfileHasNoDevDefaults() {
        Properties prod = load("application-prod.yml");
        assertThat(prod.getProperty("ddubi.dev-endpoints.enabled")).isEqualTo("${DDUBI_DEV_ENDPOINTS_ENABLED:false}");
        assertThat(prod.getProperty("management.endpoints.web.exposure.include")).isEqualTo("health, info, prometheus");
        assertThat(prod.getProperty("ddubi.jwt.secret")).isEqualTo("${JWT_SECRET:}");
        assertThat(prod.getProperty("logging.level.org.hibernate.orm.jdbc.bind")).isEqualTo("WARN");
        assertThat(prod.getProperty("springdoc.swagger-ui.enabled")).isEqualTo("false");
    }

    @Test
    @DisplayName("개발 프로파일에도 저장소에 공개된 서명키 기본값과 actuator 전체 노출은 없다")
    void devProfileHasNoCommittedSecretOrWildcardActuator() {
        Properties dev = load("application-dev.yml");
        assertThat(dev.getProperty("ddubi.jwt.secret")).isEqualTo("${JWT_SECRET:}");
        assertThat(dev.getProperty("management.endpoints.web.exposure.include")).doesNotContain("*");
        assertThat(dev.getProperty("ddubi.dev-endpoints.enabled")).isEqualTo("${DDUBI_DEV_ENDPOINTS_ENABLED:false}");
    }
}
