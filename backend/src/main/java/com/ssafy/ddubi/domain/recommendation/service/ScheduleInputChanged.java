package com.ssafy.ddubi.domain.recommendation.service;

/** 일정 저장 트랜잭션이 성공한 뒤 평가를 깨우는 힌트. 유실되어도 DB due 복구가 남는다. */
public record ScheduleInputChanged(long scheduleId) { }
