package com.ssafy.ddubi.domain.place.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.ssafy.ddubi.domain.place.dto.response.PlaceSearchItem;
import com.ssafy.ddubi.global.common.PageResponse;
import com.ssafy.ddubi.global.exception.BusinessException;
import com.ssafy.ddubi.global.exception.ErrorCode;
import com.ssafy.ddubi.global.util.SeoulArea;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.util.List;

/**
 * PLACE-001 서울 장소 검색 — 카카오 로컬 키워드 검색 프록시.
 *
 * 서울 한정은 {@code rect} 파라미터로 **카카오 쪽에서** 거른다. 받아와서 거르면
 * 페이지가 듬성듬성해지고(45건 중 서울만 남기면 페이지당 개수가 들쭉날쭉),
 * totalElements 도 못 맞춘다.
 *
 * 카카오 제약이 우리 계약과 달라 맞춘 부분:
 *  - 카카오 page 는 1부터(1~45) → 우리 0-base 에서 +1
 *  - 카카오 size 최대 15 → 요청 size 를 15 로 상한. 명세서 기본 20 은 카카오가 지원하지 않는다
 */
@Component
public class KakaoLocalClient {

    private static final Logger log = LoggerFactory.getLogger(KakaoLocalClient.class);

    private static final String KEYWORD_PATH = "/v2/local/search/keyword.json";
    /** 카카오 로컬 검색의 하드 리밋. */
    private static final int KAKAO_MAX_SIZE = 15;
    private static final int KAKAO_MAX_PAGE = 45;

    private final RestClient client;
    private final String restApiKey;

    public KakaoLocalClient(RestClient.Builder builder,
                            @Value("${ddubi.external.kakao.base-url}") String baseUrl,
                            @Value("${ddubi.external.kakao.rest-api-key}") String restApiKey) {
        this.client = builder.clone().baseUrl(baseUrl).build();
        this.restApiKey = restApiKey;
    }

    /** @param page 0-base. @param size 15 초과분은 15 로 잘린다. */
    public PageResponse<PlaceSearchItem> search(String query, int page, int size) {
        if (!StringUtils.hasText(restApiKey)) {
            log.error("KAKAO_REST_API_KEY 미설정 — 장소 검색 불가");
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
        int kakaoSize = Math.min(Math.max(size, 1), KAKAO_MAX_SIZE);
        int kakaoPage = Math.min(page + 1, KAKAO_MAX_PAGE);

        JsonNode body;
        try {
            body = client.get()
                    .uri(uri -> uri.path(KEYWORD_PATH)
                            .queryParam("query", query)
                            .queryParam("page", kakaoPage)
                            .queryParam("size", kakaoSize)
                            .queryParam("rect", SeoulArea.kakaoRect())
                            .build())
                    .header(HttpHeaders.AUTHORIZATION, "KakaoAK " + restApiKey)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException e) {
            log.warn("카카오 장소 검색 실패: {}", e.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.ROUTE_PROVIDER_UNAVAILABLE,
                    "장소 검색 제공자 호출에 실패했습니다. 잠시 후 다시 시도해 주세요.");
        }
        if (body == null) {
            throw new BusinessException(ErrorCode.ROUTE_PROVIDER_UNAVAILABLE,
                    "장소 검색 제공자 호출에 실패했습니다. 잠시 후 다시 시도해 주세요.");
        }

        List<PlaceSearchItem> items = java.util.stream.StreamSupport
                .stream(body.path("documents").spliterator(), false)
                .map(doc -> new PlaceSearchItem(
                        doc.path("id").asText(null),
                        doc.path("place_name").asText(null),
                        // 도로명 주소가 없는 장소(공원 등)는 지번 주소로 대신한다.
                        StringUtils.hasText(doc.path("road_address_name").asText(""))
                                ? doc.path("road_address_name").asText()
                                : doc.path("address_name").asText(null),
                        decimal(doc, "y"),   // 카카오는 y=위도
                        decimal(doc, "x")))  // x=경도
                .toList();

        long total = body.path("meta").path("pageable_count").asLong(items.size());
        int totalPages = (int) Math.ceil((double) total / kakaoSize);
        boolean hasNext = !body.path("meta").path("is_end").asBoolean(true);

        return new PageResponse<>(items, page, kakaoSize, total, Math.max(totalPages, 1), hasNext);
    }

    private static BigDecimal decimal(JsonNode doc, String field) {
        String value = doc.path(field).asText(null);
        return value == null ? null : new BigDecimal(value);
    }
}
