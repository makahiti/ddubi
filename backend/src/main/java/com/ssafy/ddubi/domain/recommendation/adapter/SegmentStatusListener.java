package com.ssafy.ddubi.domain.recommendation.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.ddubi.domain.recommendation.service.ScheduleEvaluationStore;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Spark segment.status를 수신해 해당 선택 경로의 재평가를 깨운다. 데이터 자체는 Redis에서 다시 읽는다.
 * 이미 만료된 재전송은 건너뛰며 DB 장애는 예외를 전파해 Kafka 재시도 대상이 된다.
 * Kafka 미배포 환경에서는 비활성이고 주기적 Redis 확인은 계속 작동한다.
 */
@Component
@ConditionalOnProperty(name="ddubi.recommendation.kafka-enabled", havingValue="true")
public class SegmentStatusListener {
    private static final Logger log = LoggerFactory.getLogger(SegmentStatusListener.class);
    private final ObjectMapper json;
    private final ScheduleEvaluationStore store;
    public SegmentStatusListener(ObjectMapper json, ScheduleEvaluationStore store) { this.json = json; this.store = store; }

    @KafkaListener(topics={"segment.status", "road.impact"}, groupId="ddubi-schedule-evaluation-v1")
    public void receive(String payload) {
        String id;
        Instant now = Instant.now();
        try {
            var event = json.readTree(payload);
            id = event.path("segmentId").asText();
            if (!id.matches("^(BUS|SUB):[A-Za-z0-9._-]{1,50}(:[A-Za-z0-9._-]{1,50}){2}$"))
                throw new IllegalArgumentException("invalid segment ID");
            if (Instant.parse(event.path("computedAt").asText()).isAfter(now)
                    || !Instant.parse(event.path("validUntil").asText()).isAfter(now)) return;
        } catch (Exception malformed) {
            // 원문은 Kafka/Archiver에 남고 로그는 실패 종류만 남긴다. 잘못된 한 행으로 소비 전체가 멈추지 않는다.
            log.warn("segment.status 형식 오류로 재평가 트리거 제외"); return;
        }
        store.wakeSegments(List.of(id), now);
    }
}
