package com.ssafy.ddubi.domain.recommendation.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 검증용 버스 ML HTTP 경계. 서울 provider ID와 KST 진입 시간대를 Python 배치 계약으로 보낸다.
 * 운영용 BusPredictionPort의 승인 검사를 우회하지 않고 별도의 PREVIEW 결과만 반환한다.
 * 주소 미설정/시간초과/응답 불일치는 이유 코드로 반환한다. raw 응답/URL/비밀값은 로그에 쓰지 않는다.
 */
@Component
public class BusPreviewClient {
    private final String baseUrl;
    private final ObjectMapper json;
    private final HttpClient http;
    private final Duration timeout;

    public BusPreviewClient(ObjectMapper json,
            @Value("${ddubi.recommendation.ml-preview-base-url:}") String baseUrl,
            @Value("${ddubi.recommendation.ml-preview-timeout-ms:5000}") int timeoutMs) {
        if (timeoutMs < 1 || timeoutMs > 5000) throw new IllegalArgumentException("ML timeout 범위 오류");
        this.baseUrl = baseUrl; this.json = json; this.timeout = Duration.ofMillis(timeoutMs);
        this.http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    /** 학습 원천의 서울 ID·순번이다. canonical 문자열을 잘라 provider ID로 만들지 않는다. */
    public record Input(String routeId, String fromStopId, String toStopId, int sequence,
                        LocalDate date, int hour) { }
    /** 모델 출처·계산시각·결측 품질까지 저장한다. 실패의 seconds는 null이며 0초로 변환하지 않는다. */
    public record Estimate(Double seconds, String reason, String modelVersion, Instant computedAt,
                           Integer missingFeatureCount, Integer historySampleCount, Boolean dataInsufficient) {
        public static Estimate unavailable(String reason) {
            return new Estimate(null, reason, null, null, null, null, null);
        }
    }

    /** 한 edge를 batch API로 호출한다. 진입 시간대가 앞 edge의 예측에 의존하여 순차 평가한다. 자동 재시도는 없다. */
    public Estimate predict(Input input) {
        return predictBatch(List.of(input)).get(input);
    }

    /** 입력별 상관키를 검증하며 순서가 바뀐 배치 응답도 정확한 구간에 연결한다. */
    public Map<Input, Estimate> predictBatch(List<Input> inputs) {
        inputs = inputs.stream().distinct().toList();
        if (inputs.isEmpty()) return Map.of();
        if (inputs.size() > 64) throw new IllegalArgumentException("ML batch limit exceeded");
        if (baseUrl == null || baseUrl.isBlank()) return unavailable(inputs, "ML_NOT_CONFIGURED");
        String requestId = UUID.randomUUID().toString();
        try {
            var rowsToSend = new ArrayList<Map<String, Object>>();
            var keyedInputs = new LinkedHashMap<String, Input>();
            for (Input input : inputs) {
                String key = inputs.size() == 1 ? "edge" : "edge-" + keyedInputs.size();
                keyedInputs.put(key, input);
                rowsToSend.add(Map.of("segment_key", key, "provider_namespace", "SEOUL_BUS",
                    "provider_route_id", input.routeId(), "provider_from_stop_id", input.fromStopId(),
                    "provider_to_stop_id", input.toStopId(), "from_stop_sequence", input.sequence(),
                    "service_date", input.date().toString(), "hour_slot", input.hour()));
            }
            String body = json.writeValueAsString(Map.of("request_id", requestId, "segments", rowsToSend));
            var request = HttpRequest.newBuilder(URI.create(baseUrl.replaceAll("/+$", "") + "/v1/bus/predictions:batch"))
                    .timeout(timeout).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return unavailable(inputs, "ML_HTTP_UNAVAILABLE");
            var value = json.readTree(response.body());
            var rows = value.path("results");
            // 키/대상/날짜 범위를 확인한다. 배열 첫 값을 무조건 해당 edge에 붙이지 않는다.
            if (!requestId.equals(value.path("request_id").asText())
                    || !"hourly_mean_bus_segment_seconds".equals(value.path("target").asText())
                    || !"lightgbm".equals(value.path("model_type").asText())
                    || !value.path("production_ready").isBoolean() || value.path("production_ready").asBoolean()
                    || !rows.isArray() || rows.size() != inputs.size())
                return unavailable(inputs, "ML_INVALID_RESPONSE");
            var estimates = new LinkedHashMap<Input, Estimate>();
            for (var result : rows) {
                Input input = keyedInputs.get(result.path("segment_key").asText());
                if (input == null || estimates.containsKey(input)) return unavailable(inputs, "ML_INVALID_RESPONSE");
                estimates.put(input, readEstimate(value, result, input));
            }
            return Map.copyOf(estimates);
        } catch (HttpTimeoutException timeoutFailure) {
            return unavailable(inputs, "ML_TIMEOUT");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); return unavailable(inputs, "ML_INTERRUPTED");
        } catch (Exception failure) {
            return unavailable(inputs, "ML_UNAVAILABLE");
        }
    }

    private Map<Input, Estimate> unavailable(List<Input> inputs, String reason) {
        var result = new LinkedHashMap<Input, Estimate>();
        inputs.forEach(input -> result.put(input, Estimate.unavailable(reason)));
        return Map.copyOf(result);
    }

    private Estimate readEstimate(com.fasterxml.jackson.databind.JsonNode value,
                                  com.fasterxml.jackson.databind.JsonNode result, Input input) {
        try {
            if ("UNAVAILABLE".equals(result.path("status").asText())) {
                String reason = result.path("reason").asText();
                return Estimate.unavailable(Set.of("UNSUPPORTED_PROVIDER", "REFERENCE_DATE_UNSUPPORTED", "UNSEEN_PROVIDER_ID").contains(reason)
                        ? reason : "ML_INVALID_RESPONSE");
            }
            var prediction = result.path("prediction");
            String version = value.path("model_version").asText();
            Instant computed = Instant.parse(value.path("computed_at").asText());
            if (!"PREVIEW".equals(result.path("status").asText())
                    || !"MODEL_NOT_PRODUCTION_READY".equals(result.path("reason").asText())
                    || !prediction.path("mean_seconds").isNumber() || version.isBlank()
                    || input.date().isBefore(LocalDate.parse(value.path("supported_from").asText()))
                    || input.date().isAfter(LocalDate.parse(value.path("supported_until").asText()))
                    || computed.isAfter(Instant.now().plusSeconds(5)) || computed.isBefore(Instant.now().minusSeconds(300))
                    || !prediction.path("training_ids_verified").isBoolean() || prediction.path("training_ids_verified").asBoolean()
                    || !prediction.path("data_insufficient").isBoolean()
                    || !prediction.path("missing_feature_count").isIntegralNumber() || prediction.path("missing_feature_count").asInt(-1) < 0
                    || !prediction.path("history_sample_count").isIntegralNumber() || prediction.path("history_sample_count").asInt(-1) < 0)
                return Estimate.unavailable("ML_INVALID_RESPONSE");
            double seconds = prediction.path("mean_seconds").asDouble();
            if (!Double.isFinite(seconds) || seconds < 0 || seconds > 86400)
                return Estimate.unavailable("ML_INVALID_DURATION");
            return new Estimate(seconds, "MODEL_NOT_PRODUCTION_READY", version, computed,
                    prediction.path("missing_feature_count").asInt(), prediction.path("history_sample_count").asInt(),
                    prediction.path("data_insufficient").asBoolean());
        } catch (Exception failure) {
            return Estimate.unavailable("ML_INVALID_RESPONSE");
        }
    }
}
