"""이력 독립 모델의 잘못된 인접 매핑·온라인 파일 조회·승인 오인 회귀를 방어한다."""
import hashlib
import json
from pathlib import Path
import shutil
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import lightgbm as lgb
import pandas as pd
import pytest
from predictor import BundleError
from schemas import BatchRequest
from static_bus_features import FEATURES, VERSION, matrix
from static_bus_predictor import StaticBusPredictor
from static_bus_schema import StaticBatchResponse
from pydantic import ValidationError


def payload(**changes):
    """개별 ID가 모두 vocab에 있어도 순번이 틀리면 거절해야 하는 최소 경로다."""
    row = dict(segment_key='edge', provider_route_id='001', provider_from_stop_id='002',
               provider_to_stop_id='003', from_stop_sequence=1, service_date='2026-09-18', hour_slot=9)
    row.update(changes)
    return dict(request_id='test', segments=[row])


@pytest.fixture
def bundle(tmp_path):
    """시간 60초인 작은 모델은 정확도 시험이 아니라 묶음·독립 추론 경계 재현용이다."""
    spec = dict(feature_version=VERSION, features=FEATURES,
                vocab=dict(provider_route_id=['001'], provider_from_stop_id=['002'], provider_to_stop_id=['003']),
                holidays=['2026-09-18'])
    frame = pd.DataFrame([payload()['segments'][0]] * 8)
    lgb.LGBMRegressor(n_estimators=2, n_jobs=1, verbosity=-1).fit(matrix(frame, spec), [60] * 8).booster_.save_model(str(tmp_path / 'model.txt'))
    edges = frame.iloc[:1][['provider_route_id', 'provider_from_stop_id', 'provider_to_stop_id', 'from_stop_sequence']].copy()
    edges['training_sample_count'] = 8
    edges['observed_from'] = pd.Timestamp('2025-01-01')
    edges['observed_until'] = pd.Timestamp('2025-05-01')
    edges.to_parquet(tmp_path / 'edges.parquet')
    edges.to_parquet(tmp_path / 'fixed_statistics.parquet')
    manifest = dict(model_version='v13-test', feature_version=VERSION, data_version='labels-test', master_version='master-test',
                    model_type='lightgbm', target='hourly_mean_bus_segment_seconds', production_ready=False,
                    approval='SHADOW', supported_from='2026-09-18', supported_until='2026-12-31')
    for name, content in [('input_spec', spec), ('serving_manifest', manifest), ('feature_schema', {}),
                          ('preprocessing', {}), ('metrics', {}), ('coverage', {}), ('source_usage', {})]:
        (tmp_path / (name + '.json')).write_text(json.dumps(content), encoding='utf-8')
    shutil.copyfile(Path(__file__).resolve().parents[1] / 'static_bus_features.py', tmp_path / 'static_bus_features.py')
    files = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in tmp_path.iterdir()}
    (tmp_path / 'bundle.json').write_text(json.dumps(dict(format='ddubi-static-bus-v13', files=files)))
    return tmp_path


def test_shared_calendar_and_feature_order():
    data = pd.DataFrame([payload()['segments'][0]])
    spec = dict(vocab={name: [data.iloc[0][name]] for name in FEATURES[:3]}, holidays=['2026-09-18'])
    result = matrix(data, spec)
    assert list(result) == FEATURES
    assert result.iloc[0]['spark_dow'] == 6  # 금요일: Spark 규칙 일요일=1.
    assert result.iloc[0]['is_public_holiday'] == 1
    assert not any('history' in name for name in result)


def test_hot_path_never_reads_history_or_bundle(bundle, monkeypatch):
    model = StaticBusPredictor(bundle)
    def forbidden(*args, **kwargs):
        raise AssertionError('online file access')
    monkeypatch.setattr(Path, 'read_text', forbidden)
    monkeypatch.setattr(pd, 'read_parquet', forbidden)
    value = model.predict(BatchRequest.model_validate(payload()))
    result = value['results'][0]
    assert result['prediction']['mean_seconds'] == pytest.approx(60)
    assert result['prediction']['training_sample_count'] == 8
    assert 'history_sample_count' not in result['prediction']
    assert result['reason'] == 'SHADOW_ONLY'
    assert value['production_ready'] is False


@pytest.mark.parametrize('changes,reason', [({'from_stop_sequence': 2}, 'EDGE_NOT_VERIFIED'),
    ({'provider_namespace': 'GBIS'}, 'UNSUPPORTED_PROVIDER'),
    ({'service_date': '2027-01-01'}, 'DATE_UNSUPPORTED'),
    ({'provider_to_stop_id': '002'}, 'EDGE_NOT_VERIFIED')])
def test_unverified_or_out_of_range_edge_is_unavailable(bundle, changes, reason):
    value = StaticBusPredictor(bundle).predict(BatchRequest.model_validate(payload(**changes)))
    assert value['results'][0]['reason'] == reason
    assert value['results'][0]['prediction'] is None


def test_sha_tampering_rejected_before_model_load(bundle):
    (bundle / 'model.txt').write_text('altered')
    with pytest.raises(BundleError, match='SHA_MISMATCH'):
        StaticBusPredictor(bundle)


def test_shadow_output_schema_never_approves_production(bundle):
    """HTTP 앱 없이 직접 예측하며 운영 승인·시험 적용으로 해석되는 출력을 거절한다."""
    result = StaticBusPredictor(bundle).predict(BatchRequest.model_validate(payload()))
    parsed = StaticBatchResponse.model_validate(result)
    assert parsed.approval == 'SHADOW' and parsed.production_ready is False
    for changes in ({'production_ready': True}, {'approval': 'TEST_APPLY'}):
        with pytest.raises(ValidationError):
            StaticBatchResponse.model_validate(dict(result, **changes))
