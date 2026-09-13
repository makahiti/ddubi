package com.ssafy.ddubi.domain.schedule.dto.request;

import com.ssafy.ddubi.domain.schedule.entity.Importance;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * SCH-004 부분 수정.
 *
 * presence 플래그 클래스인 이유는 {@code UserUpdateRequest} 와 같다 —
 * "안 보낸 필드"와 "명시적 null" 을 구분해야 한다.
 *
 * {@code updatedAt} 은 낙관적 잠금 비교값(필수). 조회 시점 값을 그대로 되돌려 보내야 하며,
 * 그 사이 다른 기기가 수정했으면 409 INVALID_STATE 가 난다(도메인 규칙 12).
 */
public class ScheduleUpdateRequest {

    @Size(min = 1, max = 100, message = "일정 이름은 1~100자여야 합니다.")
    private String title;
    private boolean titlePresent;

    @Valid
    private LocationRequest origin;
    private boolean originPresent;

    @Valid
    private LocationRequest destination;
    private boolean destinationPresent;

    private LocalDateTime arriveBy;
    private boolean arriveByPresent;

    private LocalDateTime plannedDepartAt;
    private boolean plannedDepartAtPresent;

    /** PATCH 생략은 기존 값 유지, 명시적 null은 감시 기준 제거다. */
    public void setPlannedDepartAt(LocalDateTime value) {
        this.plannedDepartAt = value;
        this.plannedDepartAtPresent = true;
    }
    public LocalDateTime getPlannedDepartAt() { return plannedDepartAt; }
    public boolean isPlannedDepartAtPresent() { return plannedDepartAtPresent; }

    private Importance importance;
    private boolean importancePresent;

    private Boolean considerLastTrip;
    private boolean considerLastTripPresent;

    @NotNull(message = "updatedAt 은 필수입니다. 조회한 일정의 updatedAt 을 그대로 보내 주세요.")
    private LocalDateTime updatedAt;

    public void setTitle(String title) {
        this.title = title;
        this.titlePresent = true;
    }

    public void setOrigin(LocationRequest origin) {
        this.origin = origin;
        this.originPresent = true;
    }

    public void setDestination(LocationRequest destination) {
        this.destination = destination;
        this.destinationPresent = true;
    }

    public void setArriveBy(LocalDateTime arriveBy) {
        this.arriveBy = arriveBy;
        this.arriveByPresent = true;
    }

    public void setImportance(Importance importance) {
        this.importance = importance;
        this.importancePresent = true;
    }

    public void setConsiderLastTrip(Boolean considerLastTrip) {
        this.considerLastTrip = considerLastTrip;
        this.considerLastTripPresent = true;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getTitle() {
        return title;
    }

    public LocationRequest getOrigin() {
        return origin;
    }

    public LocationRequest getDestination() {
        return destination;
    }

    public LocalDateTime getArriveBy() {
        return arriveBy;
    }

    public Importance getImportance() {
        return importance;
    }

    public Boolean getConsiderLastTrip() {
        return considerLastTrip;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public boolean isTitlePresent() {
        return titlePresent;
    }

    public boolean isOriginPresent() {
        return originPresent;
    }

    public boolean isDestinationPresent() {
        return destinationPresent;
    }

    public boolean isArriveByPresent() {
        return arriveByPresent;
    }

    public boolean isImportancePresent() {
        return importancePresent;
    }

    public boolean isConsiderLastTripPresent() {
        return considerLastTripPresent;
    }

    public boolean hasNoChanges() {
        return !titlePresent && !originPresent && !destinationPresent
                && !arriveByPresent && !importancePresent && !considerLastTripPresent && !plannedDepartAtPresent;
    }
}
