"""HTTP 배치 입력 계약. 제공자 ID와 요청 상관키를 분리하고 초 단위 결과를 반환한다.

요청자는 인접 구간마다 서울 기준 진입 날짜/시간대를 보낸다. 숫자형 ID의 자동 변환을
금지해 선행 0을 보존한다. 경로 합산·대기·환승·출발시각 결정은 이 서비스의 책임이 아니다.
"""

from datetime import date
from typing import Annotated, Literal

from pydantic import (
    AwareDatetime,
    BaseModel,
    ConfigDict,
    Field,
    StrictInt,
    StringConstraints,
    field_validator,
    model_validator,
)

ProviderId = Annotated[str, StringConstraints(strict=True, pattern=r"^[0-9]{1,30}$")]
CorrelationKey = Annotated[str, StringConstraints(strict=True, min_length=1, max_length=100)]


class InvalidField(BaseModel):
    """원본 입력 대신 JSON 내 위치와 검증 오류 종류만 공개한다."""

    location: list[str | int]
    type: str


class InvalidRequest(BaseModel):
    """잘못된 요청의 422 계약. 입력값 자체를 로그·응답에 복제하지 않는다."""

    error: Literal["INVALID_REQUEST"]
    fields: list[InvalidField]


class ServiceUnavailable(BaseModel):
    """모델 미준비·대기열 초과/만료·추론 실패를 구분하는 503 응답이다."""

    detail: Literal[
        "MODEL_NOT_READY",
        "MODEL_BUSY",
        "MODEL_UNAVAILABLE",
        "FEATURE_STORE_NOT_READY",
        "FEATURE_STORE_UNAVAILABLE",
    ]


class SegmentRequest(BaseModel):
    """한 인접 버스 구간. 순번은 provider 노선 순번이며 경로 내 배열 인덱스가 아니다.

    각 날짜는 Asia/Seoul의 달력 날짜, hour_slot은 0~23이다. namespace 미지원은
    전체 요청 오류 대신 개별 UNAVAILABLE 결과로 처리한다. 호출자는 인접성을 검증해야 한다.
    """

    model_config = ConfigDict(extra="forbid")
    segment_key: CorrelationKey
    provider_namespace: CorrelationKey = "SEOUL_BUS"
    provider_route_id: ProviderId
    provider_from_stop_id: ProviderId
    provider_to_stop_id: ProviderId
    from_stop_sequence: Annotated[StrictInt, Field(ge=0, le=10000)]
    service_date: date
    hour_slot: Annotated[StrictInt, Field(ge=0, le=23)]

    @field_validator("service_date", mode="before")
    @classmethod
    def calendar_date_only(cls, value):
        """Unix timestamp나 시간 없는 임의 숫자를 날짜로 자동 해석하지 않는다."""
        if isinstance(value, str):
            parsed = date.fromisoformat(value)
            if parsed.isoformat() != value:
                raise ValueError("service_date는 YYYY-MM-DD 형식이어야 합니다.")
            return parsed
        if type(value) is date:
            return value
        raise ValueError("service_date는 YYYY-MM-DD 형식이어야 합니다.")


class BatchRequest(BaseModel):
    """최대 1000개 구간을 한 번에 처리한다. 상관키 중복은 응답 연결 오류라 거절한다.

    동일 물리 구간이 여러 경로에 등장하는 것은 허용하되 segment_key는 각각 달라야 한다.
    1000은 초기 메모리 보호 한도이며 성능 보장을 뜻하지 않는다.
    """

    model_config = ConfigDict(extra="forbid")
    request_id: CorrelationKey
    segments: Annotated[list[SegmentRequest], Field(min_length=1, max_length=1000)]

    @model_validator(mode="after")
    def unique_keys(self):
        """배치 결과는 요청 순서를 보존하며 키로도 안전하게 연결할 수 있어야 한다."""
        keys = [row.segment_key for row in self.segments]
        if len(set(keys)) != len(keys):
            raise ValueError("segment_key가 중복되었습니다.")
        return self


class Prediction(BaseModel):
    """실제 모델이 계산한 평균 초와 입력 품질. 운영 사용 승인은 이 값에 포함되지 않는다."""

    mean_seconds: Annotated[float, Field(gt=0, le=86400, allow_inf_nan=False)]
    missing_feature_count: Annotated[int, Field(ge=0)]
    missing_features: list[str]
    unseen_id_count: Literal[0]
    history_sample_count: Annotated[int, Field(ge=0, le=8)]
    data_insufficient: bool
    training_ids_verified: bool


class SegmentResult(BaseModel):
    """미지원 입력은 null이다. 운영 승인 묶음만 PRODUCTION 상태를 사용할 수 있다."""

    segment_key: str
    status: Literal["PREVIEW", "PRODUCTION", "UNAVAILABLE"]
    reason: (
        Literal[
            "MODEL_NOT_PRODUCTION_READY",
            "UNSUPPORTED_PROVIDER",
            "REFERENCE_DATE_UNSUPPORTED",
            "UNSEEN_PROVIDER_ID",
            "INVALID_DURATION",
        ]
        | None
    )
    prediction: Prediction | None

    @model_validator(mode="after")
    def result_consistency(self):
        """모델 실패가 성공 상태나 null 없는 결과로 직렬화되는 것을 차단한다."""
        successful = self.status in {"PREVIEW", "PRODUCTION"}
        if successful != (self.prediction is not None):
            raise ValueError("결과 상태와 prediction 유무가 일치하지 않습니다.")
        if self.status == "PREVIEW" and self.reason != "MODEL_NOT_PRODUCTION_READY":
            raise ValueError("PREVIEW 결과에는 미승인 사유가 필요합니다.")
        if self.status == "PRODUCTION" and self.reason is not None:
            raise ValueError("PRODUCTION 결과에는 실패 사유를 넣지 않습니다.")
        return self


class BatchResponse(BaseModel):
    """배치 공통 버전·서울 계산시각·지원 날짜와 요청 순서대로 정렬된 구간 결과."""

    request_id: str
    model_version: str
    model_type: Literal["lightgbm"]
    target: Literal["hourly_mean_bus_segment_seconds"]
    production_ready: bool
    bundle_production_ready: bool
    feature_version: str | None = None
    features_as_of: AwareDatetime | None = None
    valid_until: AwareDatetime
    input_digest: str | None = None
    supported_from: date
    supported_until: date
    computed_at: AwareDatetime
    results: list[SegmentResult]
