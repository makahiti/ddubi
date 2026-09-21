"""v13 버스 모델의 학습·서빙 공통 변환: 요청 값과 고정 달력만 사용한다.

서울 provider ID 세 개와 노선 내 순번을 입력으로 받는다. 현재 DB/Redis/HDFS나
날짜별 과거 파일을 읽지 않는다. 직선거리, 수요, lag, 사후 날씨는 특성에서 제외한다.
달력과 vocab은 학습 산출물에 고정하며 미등록 ID를 임의 기존 ID로 대체하지 않는다.
"""

import numpy as np
import pandas as pd

VERSION = "bus-request-static-v13.1"
CAT = ["provider_route_id", "provider_from_stop_id", "provider_to_stop_id"]
KEY = CAT + ["from_stop_sequence"]
NUM = ["from_stop_sequence", "hour_slot", "spark_dow", "is_weekend", "month", "day_of_year", "is_public_holiday"]
FEATURES = CAT + NUM


def matrix(frame, spec):
    """학습 vocab은 1부터, 미등록은 0으로 변환한다. 날짜는 KST 달력 날짜다.

    미등록 0은 평가에서 오차를 측정하기 위한 값이며 서빙 적용 승인은 별도 edge 검증이
    담당한다. 정답이나 과거 관측으로 결측을 채우는 단계는 없다.
    """
    dates = pd.to_datetime(frame["service_date"])
    out = pd.DataFrame(index=frame.index)
    for name in CAT:
        codes = {value: i + 1 for i, value in enumerate(spec["vocab"][name])}
        out[name] = frame[name].map(codes).fillna(0).astype("int32")
    out["from_stop_sequence"] = frame["from_stop_sequence"].astype("int32")
    out["hour_slot"] = frame["hour_slot"].astype("int32")
    out["spark_dow"] = ((dates.dt.dayofweek + 1) % 7 + 1).astype("int32")
    out["is_weekend"] = (dates.dt.dayofweek >= 5).astype("int32")
    out["month"] = dates.dt.month.astype("int32")
    out["day_of_year"] = dates.dt.dayofyear.astype("int32")
    out["is_public_holiday"] = dates.dt.strftime("%Y-%m-%d").isin(spec["holidays"]).astype("int32")
    if list(out) != FEATURES or not np.isfinite(out.to_numpy()).all():
        raise ValueError("STATIC_FEATURE_INVALID")
    return out
