package com.ssafy.ddubi.domain.recommendation.evaluation;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 한 번의 경로 평가에서 고정하는 입력 증거다. 서버 어댑터가 검증한 구성요소만 받으며,
 * 사용자 입력이나 provider ID를 canonical ID로 승격하지 않는다. 모든 소요시간은 초다.
 * 관측을 확보한 asOf와 실제 사용할 미래 시각을 분리하고 외부 I/O는 수행하지 않는다.
 */
public final class JourneyEvidence {
    private JourneyEvidence() { }

    /** API 총시간을 유지하는 경로와 대기·이동을 분리한 경로를 명시적으로 구분한다. */
    public enum Kind { PROVIDER_TOTAL, WALK, RIDE, TRANSFER, WAIT, UNASSIGNED }

    /**
     * 발생/공개 근거와 사용 가능한 대상 시간 범위다. validUntil은 이 시각 미만에만 유효하다.
     * 실시간은 짧은 유효범위, 시간표는 적용 운행일 범위로 어댑터에서 검증한다.
     */
    public record Window(String source, Instant availableAt, Instant validFrom, Instant validUntil) {
        public Window {
            Objects.requireNonNull(source);
            Objects.requireNonNull(availableAt);
            Objects.requireNonNull(validFrom);
            Objects.requireNonNull(validUntil);
            if (source.isBlank() || !validFrom.isBefore(validUntil)) {
                throw new IllegalArgumentException("근거 출처와 유효 범위가 필요합니다.");
            }
        }

        /** 미래에야 확보되는 정보 또는 대상시각에 만료된 정보는 사용하지 않는다. */
        public boolean covers(Instant asOf, Instant target) {
            return !availableAt.isAfter(asOf) && !target.isBefore(validFrom) && target.isBefore(validUntil);
        }
    }

    /**
     * 방향·행선지 검증을 마친 차량 또는 시간표의 탑승 시각 범위다.
     * earliest/latest는 검증된 오차 범위이며 근거가 없으면 같은 예측시각을 사용하되
     * validatedBounds=false로 전달한다. 차량 ID는 출처 내부 ID이고 공용 segmentId가 아니다.
     */
    public record Boarding(String vehicleId, Instant earliest, Instant latest, boolean validatedBounds) {
        public Boarding {
            Objects.requireNonNull(vehicleId);
            Objects.requireNonNull(earliest);
            Objects.requireNonNull(latest);
            if (vehicleId.isBlank() || latest.isBefore(earliest)) {
                throw new IllegalArgumentException("차량과 도착 시각 범위를 확인하세요.");
            }
        }
    }

    /**
     * 순서가 고정된 이동/대기 구성요소. fallbackSeconds는 해당 요소만의 시간이거나 null이다.
     * WAIT의 fallback은 대기 추정이지 차량 탑승 확인이 아니다. 다른 요소의 대기와 중복하지 않는다.
     * seconds는 해당 요소의 검증된 추정값이며 window가 있을 때만 덮어쓴다.
     */
    public record Leg(String key, Kind kind, List<String> segmentIds, Long fallbackSeconds,
                      Long seconds, Window window, List<Boarding> boardings, long entryUncertaintySeconds) {
        public Leg(String key, Kind kind, List<String> segmentIds, Long fallbackSeconds,
                   Long seconds, Window window, List<Boarding> boardings) {
            this(key, kind, segmentIds, fallbackSeconds, seconds, window, boardings, 0);
        }
        public Leg {
            Objects.requireNonNull(key);
            Objects.requireNonNull(kind);
            segmentIds = List.copyOf(segmentIds);
            boardings = List.copyOf(boardings);
            if (key.isBlank() || entryUncertaintySeconds < 0 || (fallbackSeconds != null && fallbackSeconds < 0)
                    || (seconds != null && seconds < 0)) {
                throw new IllegalArgumentException("구간 키와 0 이상 초 단위 시간이 필요합니다.");
            }
            if (kind != Kind.WAIT && !boardings.isEmpty()) {
                throw new IllegalArgumentException("차량 후보는 WAIT에만 연결합니다.");
            }
        }
    }

    /**
     * 위치·방향 매핑을 끝낸 통제다. 관측 확보시각과 통제 시작/종료를 구분한다.
     * 행사 예정이나 단순 지연은 이 타입으로 만들지 않는다. 통제 종료가 불명확하면 어댑터가
     * 현재 확인 가능한 유효 범위만 제공하고 무기한 통제로 추측하지 않는다.
     */
    public record Closure(String eventId, List<String> segmentIds, Window window) {
        public Closure {
            Objects.requireNonNull(eventId);
            Objects.requireNonNull(window);
            segmentIds = List.copyOf(segmentIds);
            if (eventId.isBlank() || segmentIds.isEmpty()) {
                throw new IllegalArgumentException("통제 이벤트와 검증된 영향 구간이 필요합니다.");
            }
        }
    }

    /**
     * 서버에서 만든 경로 후보. API 총시간을 쓰면 PROVIDER_TOTAL 한 요소만 넣는다.
     * 대기 포함 여부가 불명확한 총시간에 WAIT를 추가하는 이중 합산을 구조적으로 막는다.
     */
    public record Journey(String key, List<Leg> legs) {
        public Journey {
            Objects.requireNonNull(key);
            legs = List.copyOf(legs);
            if (key.isBlank() || legs.isEmpty()
                    || legs.stream().map(Leg::key).distinct().count() != legs.size()) {
                throw new IllegalArgumentException("경로와 고유한 구성요소가 필요합니다.");
            }
            if (legs.size() > 1 && legs.stream().anyMatch(l -> l.kind() == Kind.PROVIDER_TOTAL)) {
                throw new IllegalArgumentException("API 총시간에 대기·이동을 다시 더할 수 없습니다.");
            }
        }
    }
}
