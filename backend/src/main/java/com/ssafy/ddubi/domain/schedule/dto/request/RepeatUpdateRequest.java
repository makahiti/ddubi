package com.ssafy.ddubi.domain.schedule.dto.request;

import com.ssafy.ddubi.domain.schedule.entity.Importance;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * REPEAT-004 부분 수정. presence 플래그 클래스({@code ScheduleUpdateRequest} 와 같은 이유).
 *
 * <p>endDate 만 명시적 null(무기한으로 변경)이 의미 있다. 나머지 규칙 필드는 null 을 거부한다.
 */
public class RepeatUpdateRequest {

    @Size(min = 1, max = 100, message = "일정 이름은 1~100자여야 합니다.")
    private String title;

    private boolean titlePresent;

    @Valid private LocationRequest origin;
    private boolean originPresent;

    @Valid private LocationRequest destination;
    private boolean destinationPresent;

    private LocalTime localTime;
    private boolean localTimePresent;

    private LocalTime departTime;
    private boolean departTimePresent;

    private LocalDate startDate;
    private boolean startDatePresent;

    private String rrule;
    private boolean rrulePresent;

    private LocalDate endDate;
    private boolean endDatePresent;

    private Importance importance;
    private boolean importancePresent;

    private Boolean considerLastTrip;
    private boolean considerLastTripPresent;

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

    public void setLocalTime(LocalTime localTime) {
        this.localTime = localTime;
        this.localTimePresent = true;
    }

    public void setDepartTime(LocalTime departTime) {
        this.departTime = departTime;
        this.departTimePresent = true;
    }

    public void setStartDate(LocalDate startDate) {
        this.startDate = startDate;
        this.startDatePresent = true;
    }

    public void setRrule(String rrule) {
        this.rrule = rrule;
        this.rrulePresent = true;
    }

    public void setEndDate(LocalDate endDate) {
        this.endDate = endDate;
        this.endDatePresent = true;
    }

    public void setImportance(Importance importance) {
        this.importance = importance;
        this.importancePresent = true;
    }

    public void setConsiderLastTrip(Boolean considerLastTrip) {
        this.considerLastTrip = considerLastTrip;
        this.considerLastTripPresent = true;
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

    public LocalTime getLocalTime() {
        return localTime;
    }

    public LocalTime getDepartTime() {
        return departTime;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public String getRrule() {
        return rrule;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public Importance getImportance() {
        return importance;
    }

    public Boolean getConsiderLastTrip() {
        return considerLastTrip;
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

    public boolean isLocalTimePresent() {
        return localTimePresent;
    }

    public boolean isDepartTimePresent() {
        return departTimePresent;
    }

    public boolean isStartDatePresent() {
        return startDatePresent;
    }

    public boolean isRrulePresent() {
        return rrulePresent;
    }

    public boolean isEndDatePresent() {
        return endDatePresent;
    }

    public boolean isImportancePresent() {
        return importancePresent;
    }

    public boolean isConsiderLastTripPresent() {
        return considerLastTripPresent;
    }

    public boolean hasNoChanges() {
        return !titlePresent
                && !originPresent
                && !destinationPresent
                && !localTimePresent
                && !departTimePresent
                && !startDatePresent
                && !rrulePresent
                && !endDatePresent
                && !importancePresent
                && !considerLastTripPresent;
    }

    /** 인스턴스를 다시 만들어야 하는 변경인가 — 제목·중요도만 바뀌면 재생성이 필요 없다. */
    public boolean affectsInstances() {
        return originPresent
                || destinationPresent
                || localTimePresent
                || departTimePresent
                || startDatePresent
                || rrulePresent
                || endDatePresent;
    }
}
