package com.ssafy.ddubi.global.common;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * 목록 API 공통 쿼리 파라미터. page 0부터, size 기본 20 / 최대 100 (명세서 v2.1.1 §2).
 *
 * Spring 의 Pageable 을 컨트롤러에서 바로 받지 않고 이걸 거치는 이유는
 * size 상한(100)을 강제하기 위해서다 — 안 막으면 size=100000 요청 하나로 DB 가 흔들린다.
 */
public class PageRequestParam {

    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 100;

    @Min(value = 0, message = "page 는 0 이상이어야 합니다.")
    private int page = 0;

    @Min(value = 1, message = "size 는 1 이상이어야 합니다.")
    @Max(value = MAX_SIZE, message = "size 는 " + MAX_SIZE + " 이하여야 합니다.")
    private int size = DEFAULT_SIZE;

    public int getPage() {
        return page;
    }

    public void setPage(int page) {
        this.page = page;
    }

    public int getSize() {
        return size;
    }

    public void setSize(int size) {
        this.size = size;
    }

    public Pageable toPageable() {
        return PageRequest.of(page, size);
    }

    public Pageable toPageable(Sort sort) {
        return PageRequest.of(page, size, sort);
    }
}
