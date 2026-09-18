package com.ssafy.ddubi.domain.recommendation.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 버스 ML HTTP 경계. 서울 provider ID와 KST 진입 시간대를 Python 배치 계약으로 보낸다.
 * 응답 계약은 두 가지를 분리해 검사한다.
 * - PREVIEW: 현재 v12 서빙 계약. production_ready=false, training_ids_verified=false, status=PREVIEW.
 * - PRODUCTION: 운영 승인 모델 계약(제안). production_ready=true, training_ids_verified=true, feature_version·
 *   features_as_of·valid_until·input_digest가 있어야 하며 status=PRODUCTION. 조건문을 지우거나 boolean을 강제로
 *   true로 만들지 않고 계약별로 검사한다.
 * 주소 미설정/시간초과/응답 불일치는 이유 코드로 반환한다. raw 응답/URL/비밀값은 로그에 쓰지 않는다.
 */
@Component
public class BusPreviewClient {
    /** 응답 계약 종류. 운영 계약은 ML 담당이 새 bundle을 제공한 뒤에만 실제로 통과할 수 있다. */
    public enum Contract { PREVIEW, PRODUCTION }
    public static final int MISSING_FEATURES_LIMIT = 32;
    /** HTTP 호출 한 건에 허용하는 최대 대기. 설정으로도 이 값을 넘길 수 없다. */
    public static final int MAX_TIMEOUT_MS = 10000;
    private static final Pattern FEATURE_NAME = Pattern.compile("[A-Za-z0-9_.:-]{1,64}");
    private static final Set<String> PASS_THROUGH_UNAVAILABLE = Set.of("UNSUPPORTED_PROVIDER", "REFERENCE_DATE_UNSUPPORTED", "UNSEEN_PROVIDER_ID");

    private final String baseUrl;
    private final ObjectMapper json;
    private final HttpClient http;
    private final Duration timeout;
    private final int maxBatchSegments;

    /**
     * Spring 주입용 생성자. 배치 크기를 지정하는 생성자가 따로 있으므로 어느 쪽으로 주입할지 여기서 명시한다.
     * 표시가 없으면 public 생성자가 둘이라 Spring이 고르지 못하고 기동 자체가 실패한다.
     */
    @Autowired
    public BusPreviewClient(ObjectMapper json,
            @Value("${ddubi.recommendation.ml-preview-base-url:}") String baseUrl,
            @Value("${ddubi.recommendation.ml-preview-timeout-ms:5000}") int timeoutMs) {
        this(json, baseUrl, timeoutMs, 200);
    }
    /** 배치 크기를 직접 정하는 생성자. 테스트가 작은 배치로 분할 동작을 확인할 때 쓴다. */
    public BusPreviewClient(ObjectMapper json, String baseUrl, int timeoutMs, int maxBatchSegments) {
        // 상한은 10초다. ml-serving 단독 응답이 약 4초라 5초는 여유가 없어 정상 응답도 끊겼다(2026-09-18).
        // 무제한은 허용하지 않는다. 한 번의 호출이 평가 예산 전체를 삼키면 과거 평균 계산 시간이 사라진다.
        if (timeoutMs < 1 || timeoutMs > MAX_TIMEOUT_MS) throw new IllegalArgumentException("ML timeout 범위 오류");
        if (maxBatchSegments < 1 || maxBatchSegments > 1000) throw new IllegalArgumentException("ML 배치 크기 범위 오류");
        this.baseUrl = baseUrl; this.json = json; this.timeout = Duration.ofMillis(timeoutMs); this.maxBatchSegments = maxBatchSegments;
        this.http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    /** 이 클라이언트가 한 번의 호출에 기다리는 시간. 평가 예산이 이만큼 남았을 때만 호출을 시작한다. */
    public long timeoutMillis() { return timeout.toMillis(); }

    /** 학습 원천의 서울 ID·순번이다. canonical 문자열을 잘라 provider ID로 만들지 않는다. */
    public record Input(String routeId, String fromStopId, String toStopId, int sequence,
                        LocalDate date, int hour) { }

    /**
     * 모델 출처·계산시각·결측 품질까지 저장한다. 실패의 seconds는 null이며 0초로 변환하지 않는다.
     * missingFeatures: 응답에 필드가 없으면 null, 빈 배열이면 빈 목록. 최대 {@link #MISSING_FEATURES_LIMIT}개만 보존하고
     * 초과하면 missingFeaturesTruncated=true. 사용자 일반 화면에는 원시 특성명을 내보내지 않는다(진단 전용).
     * contract: 이 값이 통과한 응답 계약(PREVIEW/PRODUCTION). 실패는 null.
     */
    public record Estimate(Double seconds, String reason, String modelVersion, Instant computedAt,
                           Integer missingFeatureCount, Integer historySampleCount, Boolean dataInsufficient,
                           List<String> missingFeatures, Boolean missingFeaturesTruncated, Boolean trainingIdsVerified,
                           Contract contract) {
        public Estimate { missingFeatures = missingFeatures == null ? null : List.copyOf(missingFeatures); }
        /** 기존 PREVIEW fixture 호환 생성자. 특성명 목록은 알 수 없음(null)으로 둔다. */
        public Estimate(Double seconds, String reason, String modelVersion, Instant computedAt,
                        Integer missingFeatureCount, Integer historySampleCount, Boolean dataInsufficient) {
            this(seconds, reason, modelVersion, computedAt, missingFeatureCount, historySampleCount, dataInsufficient,
                    null, null, seconds == null ? null : Boolean.FALSE, seconds == null ? null : Contract.PREVIEW);
        }
        /**
         * HTTP 호출 성공과 실제 시간 교체 자격을 분리한다. 모델은 결측 대체값으로 숫자를 만들 수
         * 있으므로 표본 부족을 PREVIEW 승인 사유로 덮지 않는다. 원래 반환 수치는 진단에 보존한다.
         */
        public String applicationFailure() {
            if (Boolean.TRUE.equals(dataInsufficient)) return "ML_HISTORY_INSUFFICIENT";
            // 이력 표본이 0건이면 서버가 data_insufficient를 표시하지 않았어도 시간 계산에 쓰지 않는다(SHADOW 진단·PRODUCTION 공통).
            if (seconds != null && historySampleCount != null && historySampleCount == 0) return "ML_HISTORY_INSUFFICIENT";
            if (seconds == null) return reason;
            if (!Double.isFinite(seconds) || seconds < 0) return "ML_INVALID_DURATION";
            return null;
        }
        /** 숫자 자체가 유효한지만 본다(시험 적용 판단용). 표본 부족은 별도 사유로 보존한다. */
        public boolean numeric() { return seconds != null && Double.isFinite(seconds) && seconds >= 0; }
        /** 운영 계약 통과 여부. 계약 자체가 PRODUCTION이고 학습 ID·표본 조건까지 만족해야 한다. */
        public boolean productionEligible() {
            return numeric() && contract == Contract.PRODUCTION && Boolean.TRUE.equals(trainingIdsVerified) && !Boolean.TRUE.equals(dataInsufficient);
        }
        public static Estimate unavailable(String reason) {
            return new Estimate(null, reason, null, null, null, null, null, null, null, null, null);
        }
    }

    /** 한 edge를 PREVIEW 계약으로 호출한다. 자동 재시도는 없다. */
    public Estimate predict(Input input) {
        return predictBatch(List.of(input), Contract.PREVIEW).getOrDefault(input, Estimate.unavailable("ML_INVALID_RESPONSE"));
    }

    /**
     * 여러 edge를 제한된 수의 배치 요청으로 보낸다. 같은 입력은 한 번만 보내고, 응답은 segment_key로 원래 입력에
     * 연결한다. 개수·순서·키 불일치는 그 배치 전체를 ML_INVALID_RESPONSE로 처리하고, 개별 UNAVAILABLE 결과는
     * 그 입력만 실패로 남긴다. 요청 전체 시간초과(ML_TIMEOUT)와 개별 결과 실패를 구분한다.
     */
    public Map<Input, Estimate> predictBatch(List<Input> inputs, Contract contract) {
        var out = new LinkedHashMap<Input, Estimate>();
        var unique = new ArrayList<Input>(new LinkedHashSet<>(inputs));
        if (unique.isEmpty()) return out;
        if (baseUrl == null || baseUrl.isBlank()) { for (Input i : unique) out.put(i, Estimate.unavailable("ML_NOT_CONFIGURED")); return out; }
        for (int from = 0; from < unique.size(); from += maxBatchSegments) {
            var chunk = unique.subList(from, Math.min(unique.size(), from + maxBatchSegments));
            out.putAll(sendChunk(chunk, contract));
        }
        return out;
    }

    private Map<Input, Estimate> sendChunk(List<Input> chunk, Contract contract) {
        var out = new LinkedHashMap<Input, Estimate>();
        String requestId = UUID.randomUUID().toString();
        String body;
        try {
            var segments = new ArrayList<Map<String, Object>>();
            for (int i = 0; i < chunk.size(); i++) {
                Input input = chunk.get(i);
                segments.add(Map.of("segment_key", "e" + i, "provider_namespace", "SEOUL_BUS",
                        "provider_route_id", input.routeId(), "provider_from_stop_id", input.fromStopId(),
                        "provider_to_stop_id", input.toStopId(), "from_stop_sequence", input.sequence(),
                        "service_date", input.date().toString(), "hour_slot", input.hour()));
            }
            body = json.writeValueAsString(Map.of("request_id", requestId, "segments", segments));
        } catch (Exception failure) { return allUnavailable(chunk, "ML_UNAVAILABLE"); }
        try {
            var request = HttpRequest.newBuilder(URI.create(baseUrl.replaceAll("/+$", "") + "/v1/bus/predictions:batch"))
                    .timeout(timeout).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return allUnavailable(chunk, "ML_HTTP_UNAVAILABLE");
            var value = json.readTree(response.body());
            if (!envelopeValid(value, requestId, contract, body)) return allUnavailable(chunk, "ML_INVALID_RESPONSE");
            var rows = value.path("results");
            if (!rows.isArray() || rows.size() != chunk.size()) return allUnavailable(chunk, "ML_INVALID_RESPONSE");
            var seen = new boolean[chunk.size()];
            var parsed = new Estimate[chunk.size()];
            for (JsonNode row : rows) {
                int index = keyIndex(row.path("segment_key").asText(""), chunk.size());
                if (index < 0 || seen[index]) return allUnavailable(chunk, "ML_INVALID_RESPONSE");
                seen[index] = true;
                parsed[index] = parseResult(value, row, chunk.get(index), contract);
            }
            for (int i = 0; i < chunk.size(); i++) out.put(chunk.get(i), parsed[i]);
            return out;
        } catch (HttpTimeoutException timeoutFailure) {
            return allUnavailable(chunk, "ML_TIMEOUT");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); return allUnavailable(chunk, "ML_INTERRUPTED");
        } catch (Exception failure) {
            return allUnavailable(chunk, "ML_UNAVAILABLE");
        }
    }

    private static Map<Input, Estimate> allUnavailable(List<Input> chunk, String reason) {
        var out = new LinkedHashMap<Input, Estimate>();
        for (Input i : chunk) out.put(i, Estimate.unavailable(reason));
        return out;
    }

    /** "e0".."eN-1" 형식만 허용한다. 배열 순서를 믿지 않고 키로 연결한다. */
    static int keyIndex(String key, int size) {
        if (key == null || key.length() < 2 || key.charAt(0) != 'e') return -1;
        try {
            int index = Integer.parseInt(key.substring(1));
            return index >= 0 && index < size && key.equals("e" + index) ? index : -1;
        } catch (NumberFormatException e) { return -1; }
    }

    private boolean envelopeValid(JsonNode value, String requestId, Contract contract, String requestBody) {
        if (!requestId.equals(value.path("request_id").asText())
                || !"hourly_mean_bus_segment_seconds".equals(value.path("target").asText())
                || !"lightgbm".equals(value.path("model_type").asText())
                || !value.path("production_ready").isBoolean()
                || value.path("model_version").asText("").isBlank()) return false;
        boolean productionReady = value.path("production_ready").asBoolean();
        if (contract == Contract.PREVIEW) return !productionReady;
        // PRODUCTION 계약: 운영 승인 표시와 특성 버전·기준시각·유효기간·입력 digest가 모두 있어야 한다.
        if (!productionReady || value.path("feature_version").asText("").isBlank()) return false;
        try {
            Instant featuresAsOf = Instant.parse(value.path("features_as_of").asText());
            Instant validUntil = Instant.parse(value.path("valid_until").asText());
            Instant computed = Instant.parse(value.path("computed_at").asText());
            if (featuresAsOf.isAfter(computed) || !validUntil.isAfter(Instant.now())) return false;
        } catch (Exception malformed) { return false; }
        return sha256(requestBody).equals(value.path("input_digest").asText());
    }

    private Estimate parseResult(JsonNode value, JsonNode result, Input input, Contract contract) {
        String status = result.path("status").asText();
        if ("UNAVAILABLE".equals(status)) {
            String reason = result.path("reason").asText();
            return Estimate.unavailable(PASS_THROUGH_UNAVAILABLE.contains(reason) ? reason : "ML_INVALID_RESPONSE");
        }
        var prediction = result.path("prediction");
        String version = value.path("model_version").asText();
        Instant computed;
        try { computed = Instant.parse(value.path("computed_at").asText()); } catch (Exception e) { return Estimate.unavailable("ML_INVALID_RESPONSE"); }
        String expectedStatus = contract == Contract.PREVIEW ? "PREVIEW" : "PRODUCTION";
        boolean expectedVerified = contract == Contract.PRODUCTION;
        try {
            if (!expectedStatus.equals(status)
                    || (contract == Contract.PREVIEW && !"MODEL_NOT_PRODUCTION_READY".equals(result.path("reason").asText()))
                    || !prediction.path("mean_seconds").isNumber() || version.isBlank()
                    || input.date().isBefore(LocalDate.parse(value.path("supported_from").asText()))
                    || input.date().isAfter(LocalDate.parse(value.path("supported_until").asText()))
                    || computed.isAfter(Instant.now().plusSeconds(5)) || computed.isBefore(Instant.now().minusSeconds(300))
                    || !prediction.path("training_ids_verified").isBoolean() || prediction.path("training_ids_verified").asBoolean() != expectedVerified
                    || !prediction.path("data_insufficient").isBoolean()
                    || !prediction.path("missing_feature_count").isIntegralNumber() || prediction.path("missing_feature_count").asInt(-1) < 0
                    || !prediction.path("history_sample_count").isIntegralNumber() || prediction.path("history_sample_count").asInt(-1) < 0)
                return Estimate.unavailable("ML_INVALID_RESPONSE");
        } catch (Exception malformed) { return Estimate.unavailable("ML_INVALID_RESPONSE"); }
        int missingCount = prediction.path("missing_feature_count").asInt();
        List<String> missing = null; Boolean truncated = null;
        JsonNode names = prediction.get("missing_features");
        if (names != null && !names.isNull()) {
            if (!names.isArray()) return Estimate.unavailable("ML_INVALID_RESPONSE");
            var kept = new ArrayList<String>();
            for (JsonNode name : names) {
                if (!name.isTextual() || !FEATURE_NAME.matcher(name.asText()).matches()) return Estimate.unavailable("ML_INVALID_RESPONSE");
                if (kept.size() < MISSING_FEATURES_LIMIT) kept.add(name.asText());
            }
            if (names.size() != missingCount) return Estimate.unavailable("ML_INVALID_RESPONSE");
            missing = kept; truncated = names.size() > MISSING_FEATURES_LIMIT;
        }
        double seconds = prediction.path("mean_seconds").asDouble();
        if (!Double.isFinite(seconds) || seconds < 0 || seconds > 86400) return Estimate.unavailable("ML_INVALID_DURATION");
        boolean insufficient = prediction.path("data_insufficient").asBoolean();
        if (contract == Contract.PRODUCTION && insufficient) return Estimate.unavailable("ML_HISTORY_INSUFFICIENT");
        return new Estimate(seconds, contract == Contract.PREVIEW ? "MODEL_NOT_PRODUCTION_READY" : "PRODUCTION_CONTRACT",
                version, computed, missingCount, prediction.path("history_sample_count").asInt(), insufficient,
                missing, truncated, expectedVerified, contract);
    }

    static String sha256(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            var hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) { return ""; }
    }
}
