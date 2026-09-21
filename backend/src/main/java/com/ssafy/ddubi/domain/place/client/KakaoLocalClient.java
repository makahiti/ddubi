package com.ssafy.ddubi.domain.place.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.ssafy.ddubi.domain.place.dto.response.PlaceSearchItem;
import com.ssafy.ddubi.global.common.PageResponse;
import com.ssafy.ddubi.global.exception.BusinessException;
import com.ssafy.ddubi.global.exception.ErrorCode;
import com.ssafy.ddubi.global.util.SupportedServiceArea;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * PLACE-001 서비스 권역 장소 검색 — 카카오 로컬 키워드 검색 프록시.
 *
 * <p>수도권과 부산은 서로 떨어져 있으므로 두 권역의 {@code rect}를 독립 호출한다. 전국을 덮는 하나의 사각형을 쓰면 미지원 지역 결과가 섞이고 정확한 장소가
 * 밀려나기 때문이다. 한 권역 장애를 성공처럼 숨기면 검색 결과가 조용히 사라지므로 호출 하나라도 실패하면 기존 제공자 장애 응답을 반환한다.
 *
 * <p>권역 결과는 순서대로 이어붙인다. 같은 페이지에서 권역별 결과를 잘라 합치면 잘려나간 항목이 다음 페이지에도 나오지 않아 그대로 검색 누락이 된다. 카카오가 한 질의에
 * 최대 45건만 주기 때문에 손실을 뒤에서 만회할 방법도 없다.
 *
 * <p>경계 상자는 사각형이라 수도권 상자에 강원 서부 등 미지원 지역이 함께 들어온다. 카카오가 함께 주는 주소의 시도 이름으로 한 번 더 걸러낸다. 추가 호출은 필요하지
 * 않다.
 *
 * <p>카카오 제약이 우리 계약과 달라 맞춘 부분: - 카카오 page 는 1부터(1~45) → 우리 0-base 에서 +1 - 카카오 size 최대 15 → 요청 size 를
 * 15 로 상한. 명세서 기본 20 은 카카오가 지원하지 않는다
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

    public KakaoLocalClient(
            RestClient.Builder builder,
            @Value("${ddubi.external.kakao.base-url}") String baseUrl,
            @Value("${ddubi.external.kakao.rest-api-key}") String restApiKey) {
        this.client = builder.clone().baseUrl(baseUrl).build();
        this.restApiKey = restApiKey;
    }

    /**
     * @param page 0-base. @param size 15 초과분은 15 로 잘린다.
     */
    public PageResponse<PlaceSearchItem> search(String query, int page, int size) {
        if (!StringUtils.hasText(restApiKey)) {
            log.error("KAKAO_REST_API_KEY 미설정 — 장소 검색 불가");
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
        int kakaoSize = Math.min(Math.max(size, 1), KAKAO_MAX_SIZE);
        int kakaoPage = Math.min(page + 1, KAKAO_MAX_PAGE);

        // 권역별 결과를 같은 페이지에서 잘라 합치면 잘려나간 항목이 다음 페이지에도 나오지 않는다.
        // 카카오는 한 질의에 최대 45건만 주므로 그 손실이 그대로 검색 누락이 된다.
        // 그래서 권역을 순서대로 이어붙인다. 앞 권역의 페이지를 모두 소진한 뒤 다음 권역으로 넘어간다.
        List<SearchPage> probed = new ArrayList<>();
        SearchPage owner = null;
        int remaining = page;
        try {
            for (SupportedServiceArea.Area area : SupportedServiceArea.areas()) {
                int areaPage = Math.min(Math.max(remaining, 0) + 1, KAKAO_MAX_PAGE);
                SearchPage result = call(query, area, areaPage, kakaoSize);
                probed.add(result);
                int areaPages = (int) Math.ceil((double) result.total() / kakaoSize);
                if (owner == null && remaining < areaPages) {
                    owner = result;
                }
                if (owner == null) {
                    remaining -= areaPages;
                }
            }
        } catch (RestClientException e) {
            log.warn("카카오 장소 검색 실패: {}", e.getClass().getSimpleName());
            throw new BusinessException(
                    ErrorCode.ROUTE_PROVIDER_UNAVAILABLE, "장소 검색 제공자 호출에 실패했습니다. 잠시 후 다시 시도해 주세요.");
        }

        // 경계 상자는 사각형이라 수도권 상자에 강원 서부 등이 함께 들어온다. 주소로 한 번 더 걸러낸다.
        LinkedHashMap<String, PlaceSearchItem> kept = new LinkedHashMap<>();
        if (owner != null) {
            for (PlaceSearchItem item : owner.items()) {
                if (SupportedServiceArea.addressClearlyOutside(item.address())) continue;
                String key =
                        StringUtils.hasText(item.providerPlaceId())
                                ? item.providerPlaceId()
                                : item.name() + ":" + item.lat() + ":" + item.lng();
                kept.putIfAbsent(key, item);
            }
        }

        List<PlaceSearchItem> items = List.copyOf(kept.values());
        long total = probed.stream().mapToLong(SearchPage::total).sum();
        // 권역을 이어붙이므로 전체 페이지 수는 권역별 페이지 수의 합이다. 전체 건수를 한 번에 나누면
        // 마지막 페이지가 덜 찬 앞 권역만큼 페이지가 모자라 뒤 권역 결과를 못 보게 된다.
        int totalPages =
                probed.stream()
                        .mapToInt(area -> (int) Math.ceil((double) area.total() / kakaoSize))
                        .sum();
        boolean hasNext = page + 1 < totalPages;

        return new PageResponse<>(items, page, kakaoSize, total, Math.max(totalPages, 1), hasNext);
    }

    private SearchPage call(
            String query, SupportedServiceArea.Area area, int kakaoPage, int kakaoSize) {
        JsonNode body =
                client.get()
                        .uri(
                                uri ->
                                        uri.path(KEYWORD_PATH)
                                                .queryParam("query", query)
                                                .queryParam("page", kakaoPage)
                                                .queryParam("size", kakaoSize)
                                                .queryParam("rect", area.kakaoRect())
                                                .build())
                        .header(HttpHeaders.AUTHORIZATION, "KakaoAK " + restApiKey)
                        .retrieve()
                        .body(JsonNode.class);
        if (body == null) {
            throw new BusinessException(
                    ErrorCode.ROUTE_PROVIDER_UNAVAILABLE, "장소 검색 제공자 호출에 실패했습니다. 잠시 후 다시 시도해 주세요.");
        }
        return parse(body);
    }

    private static SearchPage parse(JsonNode body) {
        List<PlaceSearchItem> items =
                java.util.stream.StreamSupport.stream(body.path("documents").spliterator(), false)
                        .map(
                                doc ->
                                        new PlaceSearchItem(
                                                doc.path("id").asText(null),
                                                doc.path("place_name").asText(null),
                                                // 도로명 주소가 없는 장소(공원 등)는 지번 주소로 대신한다.
                                                StringUtils.hasText(
                                                                doc.path("road_address_name")
                                                                        .asText(""))
                                                        ? doc.path("road_address_name").asText()
                                                        : doc.path("address_name").asText(null),
                                                decimal(doc, "y"), // 카카오는 y=위도
                                                decimal(doc, "x"))) // x=경도
                        .toList();
        return new SearchPage(
                items,
                body.path("meta").path("pageable_count").asLong(items.size()),
                !body.path("meta").path("is_end").asBoolean(true));
    }

    private record SearchPage(List<PlaceSearchItem> items, long total, boolean hasNext) {}

    private static BigDecimal decimal(JsonNode doc, String field) {
        String value = doc.path(field).asText(null);
        return value == null ? null : new BigDecimal(value);
    }
}
