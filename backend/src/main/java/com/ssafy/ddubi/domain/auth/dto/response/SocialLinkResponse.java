package com.ssafy.ddubi.domain.auth.dto.response;

import com.ssafy.ddubi.domain.auth.entity.AuthProvider;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 연결 후 그 계정의 <b>전체</b> 로그인 수단을 돌려준다.
 *
 * <p>방금 붙인 것만 주면 프론트가 화면을 다시 그리려고 또 조회해야 한다. 그리고 이 값은
 * "이 계정으로 들어올 수 있는 문이 몇 개인가" 이기도 해서, 해제 버튼을 보여줄지 판단하는
 * 근거가 된다 — 문이 하나뿐이면 그걸 떼면 못 들어온다.
 */
@Schema(description = "계정에 연결된 로그인 수단")
public record SocialLinkResponse(

        @Schema(description = "LOCAL 포함, 이 계정의 모든 로그인 수단")
        List<AuthProvider> providers
) {

    public static SocialLinkResponse of(List<AuthProvider> providers) {
        return new SocialLinkResponse(List.copyOf(providers));
    }
}
