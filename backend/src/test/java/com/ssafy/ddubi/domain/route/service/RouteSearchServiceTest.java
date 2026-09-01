package com.ssafy.ddubi.domain.route.service;

import com.ssafy.ddubi.domain.route.client.PublicTransitRouteClient;
import com.ssafy.ddubi.domain.route.dto.RouteSearchRequest;
import com.ssafy.ddubi.domain.route.dto.RouteSearchResponse;
import com.ssafy.ddubi.domain.route.model.RouteSearchQuery;
import com.ssafy.ddubi.domain.route.model.RouteSearchResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RouteSearchServiceTest {

    @Test
    void convertsApiRequestCoordinatesAndDelegatesToRouteClient() {
        // 실제 카카오 서버를 부르지 않고 가짜 Client로 Service의 연결 역할만 검사한다.
        PublicTransitRouteClient client = mock(PublicTransitRouteClient.class);
        RouteSearchResult clientResult = new RouteSearchResult(
                "KAKAO", Instant.parse("2026-08-31T00:00:00Z"), null, List.of());
        when(client.search(org.mockito.ArgumentMatchers.any())).thenReturn(clientResult);
        RouteSearchService service = new RouteSearchService(client);

        RouteSearchResponse response = service.search(new RouteSearchRequest(
                127.11119217, 37.39477123,
                127.12628814, 37.41993056
        ));

        ArgumentCaptor<RouteSearchQuery> queryCaptor = ArgumentCaptor.forClass(RouteSearchQuery.class);
        verify(client).search(queryCaptor.capture());
        assertThat(queryCaptor.getValue().start().longitude()).isEqualTo(127.11119217);
        assertThat(queryCaptor.getValue().end().latitude()).isEqualTo(37.41993056);
        assertThat(response.provider()).isEqualTo("KAKAO");
    }
}
