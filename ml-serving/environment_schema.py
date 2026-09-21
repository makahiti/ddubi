"""내부 환경 전처리 입력 계약. 마스터 매핑과 원본 시각을 보존하고 요청 크기를 제한한다."""

from typing import Annotated, Any, Literal

from pydantic import (
    AwareDatetime,
    BaseModel,
    ConfigDict,
    Field,
    StrictBool,
    StrictFloat,
    StrictInt,
    StrictStr,
    model_validator,
)


class EnvironmentPoint(BaseModel):
    """Backend가 확정한 canonical 인접 구간과 예측 기준/예상 통과 시각이다."""

    model_config = ConfigDict(extra="forbid")
    segmentId: Annotated[StrictStr, Field(min_length=9, max_length=180)]
    masterVersion: Annotated[StrictStr, Field(min_length=1, max_length=64)]
    asOf: AwareDatetime
    passageAt: AwareDatetime


class EnvironmentObservation(BaseModel):
    """공간 매핑이 검증된 한 이력 revision. 발표·수집·대상 시각을 서로 대체하지 않는다."""

    model_config = ConfigDict(extra="forbid")
    segmentId: Annotated[StrictStr, Field(min_length=9, max_length=180)]
    masterVersion: Annotated[StrictStr, Field(min_length=1, max_length=64)]
    kind: Literal["weather", "road", "events"]
    eventId: Annotated[StrictStr, Field(min_length=1, max_length=180)]
    source: Annotated[StrictStr, Field(min_length=1, max_length=100)]
    observedAt: AwareDatetime
    publishedAt: AwareDatetime
    collectedAt: AwareDatetime
    validFrom: AwareDatetime
    validUntil: AwareDatetime
    isForecast: StrictBool = False
    feedComplete: StrictBool = False
    values: Annotated[dict[str, StrictFloat | StrictInt | None], Field(max_length=20)]


class EnvironmentRequest(BaseModel):
    """최대 1,000개 구간 이력 revision을 공통 전처리에 전달한다. 정답 필드는 받지 않는다."""

    model_config = ConfigDict(extra="forbid")
    request: EnvironmentPoint
    observations: Annotated[list[EnvironmentObservation], Field(max_length=1000)]


class OnlineFeatureBatchRequest(BaseModel):
    """한 경로의 인접 버스 구간 Snapshot을 Redis MGET 한 번으로 읽는다."""

    model_config = ConfigDict(extra="forbid")
    requestId: Annotated[StrictStr, Field(min_length=1, max_length=100)]
    masterVersion: Annotated[StrictStr, Field(min_length=1, max_length=64)]
    asOf: AwareDatetime
    segmentIds: Annotated[list[StrictStr], Field(min_length=1, max_length=1000)]

    @model_validator(mode="after")
    def unique_segments(self):
        if len(set(self.segmentIds)) != len(self.segmentIds):
            raise ValueError("segmentIds must be unique")
        return self


class OnlineFeatureBatchItem(BaseModel):
    """한 구간의 검증 결과. 사용 불가 상태에서는 원문 Snapshot을 노출하지 않는다."""

    segmentId: StrictStr
    status: Literal["AVAILABLE", "MISSING", "STALE", "INVALID"]
    snapshot: dict[str, Any] | None

    @model_validator(mode="after")
    def snapshot_matches_status(self):
        if (self.status == "AVAILABLE") != (self.snapshot is not None):
            raise ValueError("snapshot must exist only for AVAILABLE")
        return self


class OnlineFeatureBatchResponse(BaseModel):
    """입력 순서를 보존한 구간별 현재 Snapshot 조회 결과다."""

    requestId: StrictStr
    featureVersion: Literal["bus-segment-feature-v1"]
    masterVersion: StrictStr
    asOf: AwareDatetime
    results: list[OnlineFeatureBatchItem]
