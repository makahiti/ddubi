"""v13 독립 실험 출력 스키마. 현재 HTTP API 계약에 등록하지 않는다. 학습 관측 수와 모델 적용 승인을 독립된 필드로 전달한다."""
from datetime import date
from typing import Annotated, Literal
from pydantic import BaseModel, Field, AwareDatetime, model_validator


class StaticPrediction(BaseModel):
    """현재 공식 edge와 학습 표본의 교집합에 있는 인접 조합의 평균 예측 초다."""
    mean_seconds: Annotated[float, Field(gt=0, le=86400, allow_inf_nan=False)]
    training_ids_verified: Literal[True]
    training_sample_count: Annotated[int, Field(gt=0)]
    observed_from: date
    observed_until: date
    missing_feature_count: Literal[0]
    missing_features: list[str]
    unseen_id_count: Literal[0]
    data_insufficient: Literal[False]


class StaticResult(BaseModel):
    """실험의 개별 edge 성공·실패를 기록한다. Backend fallback과 아직 연결하지 않는다."""
    segment_key: str
    status: Literal['PREVIEW', 'UNAVAILABLE']
    reason: Literal['SHADOW_ONLY', 'UNSUPPORTED_PROVIDER', 'DATE_UNSUPPORTED',
                    'EDGE_NOT_VERIFIED', 'INVALID_DURATION']
    prediction: StaticPrediction | None

    @model_validator(mode='after')
    def consistent(self):
        """숫자가 없는 성공 또는 실패에 숫자를 끼워 넣는 계약 오류를 차단한다."""
        success = self.status == 'PREVIEW'
        if success != (self.prediction is not None) or success != (self.reason == 'SHADOW_ONLY'):
            raise ValueError('STATIC_RESULT_INCONSISTENT')
        return self


class StaticBatchResponse(BaseModel):
    """기술적 지원 날짜·버전·응답 TTL을 반환하며 운영 승인으로 해석하지 않는다."""
    request_id: str
    model_version: str
    feature_version: Literal['bus-request-static-v13.1']
    data_version: str
    master_version: str
    model_type: Literal['lightgbm']
    target: Literal['hourly_mean_bus_segment_seconds']
    production_ready: Literal[False]
    supported_from: date
    supported_until: date
    computed_at: AwareDatetime
    valid_until: AwareDatetime
    approval: Literal['SHADOW']
    results: list[StaticResult]
