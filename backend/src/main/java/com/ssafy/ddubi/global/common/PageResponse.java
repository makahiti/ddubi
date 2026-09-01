package com.ssafy.ddubi.global.common;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/**
 * 목록 응답 공통 형식 (뚜비 API 명세서 v2.1.1 §2).
 * page 는 0부터, size 기본 20 / 최대 100.
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext
) {

    public static <T> PageResponse<T> of(Page<T> page) {
        return new PageResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.hasNext()
        );
    }

    /** 엔티티 Page 를 응답 DTO 로 변환하면서 감싼다. */
    public static <E, T> PageResponse<T> of(Page<E> page, Function<E, T> mapper) {
        return of(page.map(mapper));
    }

    /** 페이징 없이 전건을 내려주는 목록(예: /home 의 오늘 일정). */
    public static <T> PageResponse<T> single(List<T> content) {
        return new PageResponse<>(content, 0, content.size(), content.size(), 1, false);
    }
}
