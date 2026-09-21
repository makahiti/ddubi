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
 * <p>한 화면 안에서 수도권과 부산을 함께 보여 주되, 권역마다 고정된 페이지 몫을 사용한다. 첫 권역을 모두 소진한 뒤 다음 권역으로 넘기면 수도권 결과가 하나라도 있을 때
 * 부산이 첫 화면에서 영원히 밀린다. 반대로 매번 임의로 잘라 합치면 다음 페이지에서 누락·중복이 생기므로, 요청 size를 권역별로 고정 배분하고 각 권역의 같은 페이지를
 * 읽는다.
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
        List<SupportedServiceArea.Area> areas = SupportedServiceArea.areas();
        List<SearchPage> probed = new ArrayList<>();
        List<Integer> quotas = areaQuotas(kakaoSize, areas.size());
        try {
            for (int i = 0; i < areas.size(); i++) {
                int quota = quotas.get(i);
                // size=1은 한 화면에 두 권역을 동시에 담을 수 없으므로 페이지마다 권역을 번갈아
                // 선택한다. 나머지 권역도 1페이지를 조회해 total 메타데이터는 전체 권역 기준으로 유지한다.
                int areaPage =
                        kakaoSize == 1
                                ? (i == Math.floorMod(page, areas.size())
                                        ? page / areas.size() + 1
                                        : 1)
                                : page + 1;
                probed.add(
                        call(
                                query,
                                areas.get(i),
                                Math.min(areaPage, KAKAO_MAX_PAGE),
                                Math.max(quota, 1)));
            }
        } catch (RestClientException e) {
            log.warn("카카오 장소 검색 실패: {}", e.getClass().getSimpleName());
            throw new BusinessException(
                    ErrorCode.ROUTE_PROVIDER_UNAVAILABLE, "장소 검색 제공자 호출에 실패했습니다. 잠시 후 다시 시도해 주세요.");
        }

        // 경계 상자는 사각형이라 수도권 상자에 강원 서부 등이 함께 들어온다. 주소로 한 번 더 걸러낸다.
        LinkedHashMap<String, PlaceSearchItem> kept = new LinkedHashMap<>();
        int selectedArea = kakaoSize == 1 ? Math.floorMod(page, areas.size()) : -1;
        int maxRows = probed.stream().mapToInt(result -> result.items().size()).max().orElse(0);
        // 수도권 N건 뒤 부산 N건을 붙이는 대신 한 건씩 번갈아 넣어 첫 화면에서도 두 권역이
        // 바로 보이게 한다. providerPlaceId가 같으면 동일 장소로 보고 먼저 나온 한 건만 유지한다.
        for (int row = 0; row < maxRows; row++) {
            for (int areaIndex = 0; areaIndex < probed.size(); areaIndex++) {
                if (selectedArea >= 0 && areaIndex != selectedArea) continue;
                SearchPage area = probed.get(areaIndex);
                if (row >= area.items().size()) continue;
                PlaceSearchItem item = area.items().get(row);
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
        int totalPages;
        if (kakaoSize == 1) {
            totalPages = Math.toIntExact(Math.min(Integer.MAX_VALUE, total));
        } else {
            totalPages = 0;
            for (int i = 0; i < probed.size(); i++) {
                int areaPages = (int) Math.ceil((double) probed.get(i).total() / quotas.get(i));
                totalPages = Math.max(totalPages, areaPages);
            }
        }
        boolean hasNext = page + 1 < totalPages;

        return new PageResponse<>(items, page, kakaoSize, total, Math.max(totalPages, 1), hasNext);
    }

    /**
     * 페이지 크기를 권역별로 고정 배분한다. 홀수 크기의 남는 한 칸은 앞 권역부터 받는다. 고정 quota를 모든 페이지에 반복 적용하므로 첫 페이지에서 잘린 권역 항목이
     * 다음 페이지에서 건너뛰거나 중복되지 않는다.
     */
    private static List<Integer> areaQuotas(int size, int areaCount) {
        List<Integer> quotas = new ArrayList<>(areaCount);
        int base = size / areaCount;
        int remainder = size % areaCount;
        for (int i = 0; i < areaCount; i++) quotas.add(base + (i < remainder ? 1 : 0));
        return quotas;
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
