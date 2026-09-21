package com.ssafy.ddubi.domain.place.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestToUriTemplate;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.ssafy.ddubi.domain.place.dto.response.PlaceSearchItem;
import com.ssafy.ddubi.global.common.PageResponse;
import com.ssafy.ddubi.global.exception.BusinessException;
import com.ssafy.ddubi.global.exception.ErrorCode;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** PLACE-001 카카오 로컬 검색 프록시 — 요청 파라미터 변환과 응답 매핑을 고정한다. */
class KakaoLocalClientTest {

    private static final String BASE_URL = "https://dapi.test";

    private RestClient.Builder builder;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
    }

    private KakaoLocalClient client(String key) {
        return new KakaoLocalClient(builder, BASE_URL, key);
    }

    @Test
    @DisplayName("0-base page를 바꾸고 앞 권역 결과를 자르지 않고 그대로 준다")
    void translatesPagingAndRect() {
        KakaoLocalClient client = client("local-key");
        server.expect(
                        requestToUriTemplate(
                                BASE_URL
                                        + "/v2/local/search/keyword.json?query={q}&page={p}&size={s}&rect={r}",
                                "강남역",
                                1,
                                15,
                                "125.8,36.85,127.9,38.3"))
                .andExpect(header("Authorization", "KakaoAK local-key"))
                .andRespond(
                        withSuccess(
                                """
                        {"documents":[{"id":"123","place_name":"강남역 2호선",
                          "road_address_name":"서울 강남구 강남대로 396","address_name":"역삼동",
                          "x":"127.02761","y":"37.49795"}],
                         "meta":{"is_end":false,"pageable_count":45,"total_count":1200}}
                        """,
                                MediaType.APPLICATION_JSON));
        server.expect(
                        requestToUriTemplate(
                                BASE_URL
                                        + "/v2/local/search/keyword.json?query={q}&page={p}&size={s}&rect={r}",
                                "강남역",
                                1,
                                15,
                                "128.7,34.8,129.4,35.45"))
                .andExpect(header("Authorization", "KakaoAK local-key"))
                .andRespond(
                        withSuccess(
                                """
                        {"documents":[{"id":"456","place_name":"부산 강남역 버스정류장",
                          "road_address_name":"부산광역시","address_name":"부산광역시",
                          "x":"129.01","y":"35.17"}],
                         "meta":{"is_end":true,"pageable_count":1,"total_count":1}}
                        """,
                                MediaType.APPLICATION_JSON));

        // 명세서 기본 size=20 은 카카오 상한 15 로 잘린다.
        PageResponse<PlaceSearchItem> result = client.search("강남역", 0, 20);

        // 예전에는 두 권역 결과를 합쳐 15건으로 자르면서 잘린 항목이 다음 페이지에도 나오지 않았다.
        // 이제 앞 권역을 모두 소진한 뒤 다음 권역으로 넘어가므로 첫 페이지는 수도권 결과만 준다.
        assertThat(result.content()).hasSize(1);
        PlaceSearchItem item = result.content().get(0);
        assertThat(item.providerPlaceId()).isEqualTo("123");
        assertThat(item.name()).isEqualTo("강남역 2호선");
        assertThat(item.address()).isEqualTo("서울 강남구 강남대로 396");
        assertThat(item.lat()).isEqualByComparingTo(new BigDecimal("37.49795"));
        assertThat(item.lng()).isEqualByComparingTo(new BigDecimal("127.02761"));
        assertThat(result.hasNext()).isTrue();
        assertThat(result.totalElements()).isEqualTo(46);
        server.verify();
    }

    @Test
    @DisplayName("도로명 주소가 없으면 지번 주소로 대신한다")
    void fallsBackToLotAddress() {
        KakaoLocalClient client = client("local-key");
        server.expect(
                        requestToUriTemplate(
                                BASE_URL
                                        + "/v2/local/search/keyword.json?query={q}&page={p}&size={s}&rect={r}",
                                "서울숲",
                                1,
                                15,
                                "125.8,36.85,127.9,38.3"))
                .andRespond(
                        withSuccess(
                                """
                        {"documents":[{"id":"9","place_name":"서울숲",
                          "road_address_name":"","address_name":"성동구 성수동1가",
                          "x":"127.0374","y":"37.5443"}],
                         "meta":{"is_end":true,"pageable_count":1,"total_count":1}}
                        """,
                                MediaType.APPLICATION_JSON));
        server.expect(
                        requestToUriTemplate(
                                BASE_URL
                                        + "/v2/local/search/keyword.json?query={q}&page={p}&size={s}&rect={r}",
                                "서울숲",
                                1,
                                15,
                                "128.7,34.8,129.4,35.45"))
                .andRespond(
                        withSuccess(
                                """
                        {"documents":[],"meta":{"is_end":true,"pageable_count":0,"total_count":0}}
                        """,
                                MediaType.APPLICATION_JSON));

        assertThat(client.search("서울숲", 0, 15).content().get(0).address()).isEqualTo("성동구 성수동1가");
    }

    @Test
    @DisplayName("앞 권역 페이지를 모두 쓰면 다음 권역의 첫 페이지로 넘어간다")
    void movesToNextAreaAfterExhaustingTheFirst() {
        KakaoLocalClient client = client("local-key");
        // 수도권은 45건(3페이지)이다. 우리 page=3 은 수도권 범위를 넘어 부산 첫 페이지여야 한다.
        server.expect(
                        requestToUriTemplate(
                                BASE_URL
                                        + "/v2/local/search/keyword.json?query={q}&page={p}&size={s}&rect={r}",
                                "카페",
                                4,
                                15,
                                "125.8,36.85,127.9,38.3"))
                .andRespond(
                        withSuccess(
                                """
                        {"documents":[],"meta":{"is_end":true,"pageable_count":45,"total_count":900}}
                        """,
                                MediaType.APPLICATION_JSON));
        server.expect(
                        requestToUriTemplate(
                                BASE_URL
                                        + "/v2/local/search/keyword.json?query={q}&page={p}&size={s}&rect={r}",
                                "카페",
                                1,
                                15,
                                "128.7,34.8,129.4,35.45"))
                .andRespond(
                        withSuccess(
                                """
                        {"documents":[{"id":"b1","place_name":"부산 카페",
                          "road_address_name":"부산 해운대구 구남로 1","address_name":"부산 해운대구",
                          "x":"129.16","y":"35.16"}],
                         "meta":{"is_end":true,"pageable_count":1,"total_count":1}}
                        """,
                                MediaType.APPLICATION_JSON));

        PageResponse<PlaceSearchItem> result = client.search("카페", 3, 15);

        assertThat(result.content()).hasSize(1);
        assertThat(result.content().get(0).providerPlaceId()).isEqualTo("b1");
        server.verify();
    }

    @Test
    @DisplayName("경계 상자에 들어온 미지원 지역은 주소로 걸러낸다")
    void dropsResultsOutsideServiceAreaByAddress() {
        KakaoLocalClient client = client("local-key");
        // 수도권 사각형에는 강원 서부(춘천 등)가 함께 들어온다. 좌표만으로는 거를 수 없다.
        server.expect(
                        requestToUriTemplate(
                                BASE_URL
                                        + "/v2/local/search/keyword.json?query={q}&page={p}&size={s}&rect={r}",
                                "시청",
                                1,
                                15,
                                "125.8,36.85,127.9,38.3"))
                .andRespond(
                        withSuccess(
                                """
                        {"documents":[
                          {"id":"s1","place_name":"서울시청","road_address_name":"서울 중구 세종대로 110",
                           "address_name":"서울 중구 태평로1가","x":"126.9779","y":"37.5663"},
                          {"id":"c1","place_name":"춘천시청","road_address_name":"강원특별자치도 춘천시 시청길 11",
                           "address_name":"강원특별자치도 춘천시 옥천동","x":"127.7298","y":"37.8813"}],
                         "meta":{"is_end":true,"pageable_count":2,"total_count":2}}
                        """,
                                MediaType.APPLICATION_JSON));
        server.expect(
                        requestToUriTemplate(
                                BASE_URL
                                        + "/v2/local/search/keyword.json?query={q}&page={p}&size={s}&rect={r}",
                                "시청",
                                1,
                                15,
                                "128.7,34.8,129.4,35.45"))
                .andRespond(
                        withSuccess(
                                """
                        {"documents":[],"meta":{"is_end":true,"pageable_count":0,"total_count":0}}
                        """,
                                MediaType.APPLICATION_JSON));

        PageResponse<PlaceSearchItem> result = client.search("시청", 0, 15);

        assertThat(result.content())
                .extracting(PlaceSearchItem::providerPlaceId)
                .containsExactly("s1");
        server.verify();
    }

    @Test
    @DisplayName("앞 권역이 한 페이지를 덜 채워도 뒤 권역 페이지가 사라지지 않는다")
    void countsPagesPerAreaSoTrailingAreaStaysReachable() {
        KakaoLocalClient client = client("local-key");
        // 수도권 5건, 부산 5건. 전체 10건을 한 번에 나누면 1페이지가 되어 부산 결과를 영영 못 본다.
        server.expect(
                        requestToUriTemplate(
                                BASE_URL
                                        + "/v2/local/search/keyword.json?query={q}&page={p}&size={s}&rect={r}",
                                "도서관",
                                1,
                                15,
                                "125.8,36.85,127.9,38.3"))
                .andRespond(
                        withSuccess(
                                """
                        {"documents":[{"id":"a1","place_name":"서울도서관",
                          "road_address_name":"서울 중구 세종대로 110","address_name":"서울 중구",
                          "x":"126.9779","y":"37.5663"}],
                         "meta":{"is_end":true,"pageable_count":5,"total_count":5}}
                        """,
                                MediaType.APPLICATION_JSON));
        server.expect(
                        requestToUriTemplate(
                                BASE_URL
                                        + "/v2/local/search/keyword.json?query={q}&page={p}&size={s}&rect={r}",
                                "도서관",
                                1,
                                15,
                                "128.7,34.8,129.4,35.45"))
                .andRespond(
                        withSuccess(
                                """
                        {"documents":[{"id":"b1","place_name":"부산도서관",
                          "road_address_name":"부산 사상구 대학로 1","address_name":"부산 사상구",
                          "x":"128.98","y":"35.15"}],
                         "meta":{"is_end":true,"pageable_count":5,"total_count":5}}
                        """,
                                MediaType.APPLICATION_JSON));

        PageResponse<PlaceSearchItem> result = client.search("도서관", 0, 15);

        assertThat(result.totalPages()).isEqualTo(2);
        assertThat(result.hasNext()).isTrue();
        server.verify();
    }

    @Test
    @DisplayName("REST API 키가 없으면 카카오를 호출하지 않고 500 으로 끊는다")
    void missingKeyFailsFast() {
        KakaoLocalClient client = client("");

        assertThatThrownBy(() -> client.search("강남역", 0, 15))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.INTERNAL_ERROR);
        server.verify();
    }
}
