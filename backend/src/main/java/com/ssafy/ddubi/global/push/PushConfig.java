package com.ssafy.ddubi.global.push;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.util.StringUtils;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 발송기 선택. 프로파일이 아니라 <b>자격증명 유무</b>로 고른다(MailConfig 와 같은 방식).
 *
 * 자격증명을 두 가지로 받는 이유:
 *  - 파일 경로: 로컬 개발. 콘솔에서 받은 JSON 을 그대로 둔다
 *  - base64: 배포·팀 공유. 환경변수 한 줄이라 파일을 돌릴 필요가 없고
 *            인프라도 시크릿 하나로 주입할 수 있다
 */
@Configuration
public class PushConfig {

    private static final Logger log = LoggerFactory.getLogger(PushConfig.class);
    private static final String APP_NAME = "ddubi";

    private final String credentialsPath;
    private final String credentialsBase64;

    public PushConfig(@Value("${ddubi.push.credentials-path:}") String credentialsPath,
                      @Value("${ddubi.push.credentials-base64:}") String credentialsBase64) {
        this.credentialsPath = credentialsPath;
        this.credentialsBase64 = credentialsBase64;
    }

    @Bean
    public PushSender pushSender() {
        try (InputStream credentials = openCredentials()) {
            if (credentials == null) {
                log.warn("FIREBASE_CREDENTIALS_PATH/BASE64 미설정 — 푸시를 실제로 보내지 않고 로그에만 남깁니다");
                return new LoggingPushSender();
            }
            // 테스트가 컨텍스트를 여러 번 띄우면 같은 이름의 앱이 이미 있을 수 있다.
            FirebaseApp app = FirebaseApp.getApps().stream()
                    .filter(a -> APP_NAME.equals(a.getName()))
                    .findFirst()
                    .orElse(null);
            if (app == null) {
                app = FirebaseApp.initializeApp(FirebaseOptions.builder()
                        .setCredentials(GoogleCredentials.fromStream(credentials))
                        .build(), APP_NAME);
            }
            log.info("FCM 발송 준비 완료");
            return new FcmPushSender(FirebaseMessaging.getInstance(app));
        } catch (Exception e) {
            // 키가 깨졌다고 앱을 못 뜨게 하지 않는다 — 알림 말고 나머지는 다 정상이어야 한다.
            log.error("FCM 초기화 실패 — 로그 발송기로 대체합니다: {}", e.getMessage());
            return new LoggingPushSender();
        }
    }

    private InputStream openCredentials() {
        if (StringUtils.hasText(credentialsBase64)) {
            return new ByteArrayInputStream(Base64.getDecoder().decode(credentialsBase64.trim()));
        }
        if (StringUtils.hasText(credentialsPath)) {
            try {
                var resource = new DefaultResourceLoader().getResource("file:" + credentialsPath);
                if (resource.exists()) {
                    return resource.getInputStream();
                }
                log.warn("FCM 자격증명 파일이 없습니다: {}", credentialsPath);
            } catch (Exception e) {
                log.warn("FCM 자격증명 파일을 읽지 못했습니다: {}", e.getMessage());
            }
        }
        return null;
    }
}
