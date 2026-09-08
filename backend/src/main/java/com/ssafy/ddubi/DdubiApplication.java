package com.ssafy.ddubi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * UserDetailsServiceAutoConfiguration 을 제외하는 이유:
 * 인증은 JWT 로만 한다. 이 자동설정은 UserDetailsService 빈이 없을 때 임의 비밀번호를 만들고
 * 기동 로그에 "Using generated security password" 를 남긴다. 실제로 쓰이지 않는 값이라
 * 운영 로그에서 오해를 부른다.
 */
@EnableScheduling
@ConfigurationPropertiesScan
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class DdubiApplication {

    public static void main(String[] args) {
        SpringApplication.run(DdubiApplication.class, args);
    }
}
