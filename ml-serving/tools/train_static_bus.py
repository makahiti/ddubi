"""검증 가능한 기존 v12 정규화 정답에서 이력 독립 v13을 새로 학습한다.

날짜별 70/30 분할 및 train 내부 검증을 보존한다. 기존 model_features의 lag/좌표는
읽지 않으며 요청 특성을 공통 함수로 다시 만든다. 원천 전체 학습이 아니라 기존
정규화·샘플링 표본 재사용임을 source_usage에 명시한다. GPU 0만 사용하고 실패 시
CPU로 몰래 전환하지 않는다. 산출물은 새 폴더에만 쓰며 운영 승인값은 false다.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import sys
from datetime import datetime, timezone

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from static_bus_features import CAT, KEY, FEATURES, VERSION, matrix


def write(path, value):
    """실험 내부 JSON을 UTF-8로 기록한다. 외부 서비스/운영 DB에는 쓰지 않는다."""
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False), encoding='utf-8')


def sha(path):
    """대형 원천 파일을 청크 단위로 해시하여 메모리 사용량을 제한한다."""
    h = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(chunk)
    return h.hexdigest()


def metrics(y, prediction):
    """초 단위 평균 정답의 오차다. 개별 승객/차량 정확도로 해석하지 않는다."""
    import numpy as np
    e = np.asarray(prediction) - np.asarray(y)
    return dict(rows=len(e), mae_seconds=float(np.abs(e).mean()),
                rmse_seconds=float(np.sqrt((e * e).mean())), bias_seconds=float(e.mean()),
                p95_absolute_error_seconds=float(np.quantile(np.abs(e), .95)),
                within_20_seconds_rate=float((np.abs(e) <= 20).mean()))


def main():
    """1. 정답 품질/해시 → 2. 시간 분할 → 3. 내부 선택 → 4. 최종 학습·test·묶음.

    중복 키의 상충 정답은 해당 키 전체를 제외한다. 0·음수·비유한 값만 거절하며
    큰 지연을 1800초에서 자르지 않는다. 공식 마스터는 현재 서빙 edge 검증용이고,
    과거 노선 변경 이력이 검증됐다는 뜻은 아니다. test는 선택에 사용하지 않는다.
    """
    parser = argparse.ArgumentParser()
    for name in ('data', 'master', 'output', 'version', 'supported-from', 'supported-until'):
        parser.add_argument('--' + name, required=True)
    parser.add_argument('--device', choices=['cuda', 'cpu'], default='cuda')
    args = parser.parse_args()
    os.environ['CUDA_VISIBLE_DEVICES'] = '0'
    os.environ['OMP_NUM_THREADS'] = '16'
    if hasattr(os, 'sched_getaffinity'):
        os.sched_setaffinity(0, sorted(os.sched_getaffinity(0))[:16])
    import gzip
    import holidays
    import lightgbm as lgb
    import numpy as np
    import pandas as pd
    import pyarrow.parquet as pq
    out = Path(args.output)
    out.mkdir(parents=True, exist_ok=False)
    def progress(stage, **values):
        write(out / 'progress.json', dict(stage=stage, **values))
        print(stage, values, flush=True)
    source = Path(args.data)
    files = sorted(source.glob('*.parquet'))
    if not files:
        raise ValueError('NO_TRAINING_FILES')
    columns = ['service_date', *KEY, 'hour_slot', 'target_seconds']
    parts, sources, quality = [], [], dict(input_rows=0, invalid_rows=0, conflict_rows=0, duplicate_rows=0)
    for i, file in enumerate(files):
        frame = pd.read_parquet(file, columns=columns)
        quality['input_rows'] += len(frame)
        valid = np.isfinite(frame.target_seconds) & (frame.target_seconds > 0)
        for c in CAT:
            valid &= frame[c].astype('string').str.fullmatch(r'[0-9]{1,30}').fillna(False)
        valid &= frame.hour_slot.between(0, 23) & frame.from_stop_sequence.between(0, 10000)
        valid &= frame.from_stop_sequence.mod(1).eq(0) & frame.hour_slot.mod(1).eq(0)
        dates = pd.to_datetime(frame.service_date, errors='coerce')
        valid &= dates.notna() & dates.eq(dates.dt.normalize()) & dates.dt.strftime('%Y-%m-%d').eq(file.stem)
        quality['invalid_rows'] += int((~valid).sum())
        frame = frame.loc[valid].copy()
        identity = ['service_date', *KEY, 'hour_slot']
        conflict = frame.groupby(identity, observed=True).target_seconds.transform('nunique') > 1
        quality['conflict_rows'] += int(conflict.sum())
        frame = frame.loc[~conflict]
        n = len(frame)
        frame = frame.drop_duplicates(identity)
        quality['duplicate_rows'] += n - len(frame)
        parts.append(frame)
        sources.append(dict(path=str(file), sha256=sha(file), rows=pq.ParquetFile(file).metadata.num_rows))
        if i % 100 == 0:
            progress('reading', files=i + 1, total=len(files))
    data = pd.concat(parts, ignore_index=True)
    del parts
    dates = sorted(data.service_date.dt.strftime('%Y-%m-%d').unique())
    if len(dates) < 10:
        raise ValueError('INSUFFICIENT_DATE_COVERAGE')
    boundary = int(len(dates) * .7)
    inner = int(boundary * .8)
    day = data.service_date.dt.strftime('%Y-%m-%d')
    train = data.loc[day < dates[boundary]].copy()
    test = data.loc[day >= dates[boundary]].copy()
    del data
    fit = train.loc[train.service_date < pd.Timestamp(dates[inner])]
    validation = train.loc[train.service_date >= pd.Timestamp(dates[inner])]
    calendar = holidays.KR(years=range(int(dates[0][:4]), int(args.supported_until[:4]) + 1))
    def specification(rows):
        return dict(feature_version=VERSION, features=FEATURES,
                    vocab={c: sorted(rows[c].unique().tolist()) for c in CAT},
                    holidays=sorted(d.isoformat() for d in calendar))
    spec_inner = specification(fit)
    params = dict(objective='regression', n_estimators=800, learning_rate=.05, num_leaves=63,
                  min_child_samples=80, n_jobs=16, random_state=20260918, verbosity=-1,
                  device_type=args.device)
    if args.device == 'cuda':
        params.update(gpu_device_id=0, num_gpu=1)
    progress('inner_fit', train_rows=len(fit), validation_rows=len(validation), device=args.device)
    candidate = lgb.LGBMRegressor(**params)
    candidate.fit(matrix(fit, spec_inner), fit.target_seconds,
                  categorical_feature=CAT,
                  eval_set=[(matrix(validation, spec_inner), validation.target_seconds)],
                  callbacks=[lgb.early_stopping(40), lgb.log_evaluation(100)])
    rounds = int(candidate.best_iteration_)
    selection = dict(iterations=rounds, validation=metrics(validation.target_seconds,
                     candidate.predict(matrix(validation, spec_inner))), test_used_for_selection=False)
    del candidate, fit, validation
    spec = specification(train)
    params['n_estimators'] = rounds
    progress('final_fit', rows=len(train), iterations=rounds)
    model = lgb.LGBMRegressor(**params).fit(matrix(train, spec), train.target_seconds, categorical_feature=CAT)
    model.booster_.save_model(str(out / 'model.txt'))
    prediction = model.predict(matrix(test, spec))
    if not np.isfinite(prediction).all():
        raise ValueError('NONFINITE_PREDICTIONS')
    # 고정 통계는 비교 기준/관측 수 근거이며 모델 입력에 넣지 않아 target encoding 누수를 피한다.
    global_mean = float(train.target_seconds.mean())
    route_mean = train.groupby(CAT[0], observed=True).target_seconds.mean()
    edge_hour = train.groupby(KEY + ['hour_slot'], observed=True).target_seconds.mean().rename('mean')
    edge_stats = train.groupby(KEY, observed=True).agg(training_sample_count=('target_seconds', 'size'),
                 observed_from=('service_date', 'min'), observed_until=('service_date', 'max')).reset_index()
    baseline = test.merge(edge_hour.reset_index(), on=KEY + ['hour_slot'], how='left')['mean'].fillna(global_mean)
    report = dict(lightgbm=metrics(test.target_seconds, prediction),
                  global_mean=metrics(test.target_seconds, np.full(len(test), global_mean)),
                  route_mean=metrics(test.target_seconds, test[CAT[0]].map(route_mean).fillna(global_mean)),
                  edge_hour_mean=metrics(test.target_seconds, baseline), selection=selection)
    test['prediction_seconds'] = prediction
    report['by_hour'] = {str(k): metrics(g.target_seconds, g.prediction_seconds) for k, g in test.groupby('hour_slot')}
    report['by_route'] = {str(k): metrics(g.target_seconds, g.prediction_seconds) for k, g in test.groupby(CAT[0])}
    unseen = np.zeros(len(test), dtype=bool)
    for c in CAT:
        unseen |= ~test[c].isin(spec['vocab'][c]).to_numpy()
    report['unseen_ids'] = metrics(test.target_seconds[unseen], prediction[unseen]) if unseen.any() else dict(rows=0)
    report['nonpositive_predictions'] = int((prediction <= 0).sum())
    with gzip.open(args.master, 'rt', encoding='utf-8') as stream:
        master = json.load(stream)
    official = []
    for route in master['routes']:
        if route['namespace'] != 'SEOUL_BUS':
            continue
        stops = sorted(route['stops'], key=lambda s: s['sequence'])
        for a, b in zip(stops, stops[1:]):
            if b['sequence'] != a['sequence'] + 1:
                continue
            official.append([route['routeId'], a['stopId'], b['stopId'], a['sequence'], a.get('direction')])
    topology = pd.DataFrame(official, columns=KEY + ['direction']).drop_duplicates()
    if topology.duplicated(KEY).any():
        raise ValueError('AMBIGUOUS_MASTER_EDGE')
    verified = topology.merge(edge_stats, on=KEY, how='inner', validate='one_to_one')
    verified.to_parquet(out / 'edges.parquet', index=False)
    edge_hour.reset_index().to_parquet(out / 'fixed_statistics.parquet', index=False)
    test.to_parquet(out / 'test_predictions.parquet', index=False)
    coverage = dict(current_master_edges=len(topology), trained_current_edges=len(verified),
                    fraction=len(verified) / len(topology), training_edges=len(edge_stats),
                    historical_topology_verified=False, route_aggregate_accuracy='NOT_MEASURED_NO_JOURNEY_LABELS')
    split = dict(train_from=dates[0], train_until=dates[boundary - 1], test_from=dates[boundary],
                 test_until=dates[-1], inner_validation_from=dates[inner], train_rows=len(train), test_rows=len(test),
                 train_fraction=len(train) / (len(train) + len(test)), final_test_reuses_historical_v12_period=True)
    write(out / 'input_spec.json', spec)
    write(out / 'feature_schema.json', dict(version=VERSION, features=FEATURES, excluded='lags,demand,weather,events,coordinates,straight_line_distance'))
    write(out / 'preprocessing.json', dict(calendar='bundled KR holidays', unseen_code=0, date_timezone='Asia/Seoul', numeric_imputation=None))
    write(out / 'metrics.json', report)
    write(out / 'coverage.json', coverage)
    write(out / 'source_usage.json', dict(source='v12 normalized sampled OA-21217 hourly mean labels',
          files=sources, quality=quality, split=split, master_sha256=sha(Path(args.master)),
          not_used=['server2 ACTUAL_TRAVERSAL_MEAN', 'weekly/monthly overlapping aggregates', 'live histories'],
          limitation='Existing normalization/sampling reused; raw source publication times and historical topology not newly certified'))
    manifest = dict(model_version=args.version, feature_version=VERSION, data_version=args.version + '-labels',
                    master_version=master['version'], model_type='lightgbm', target='hourly_mean_bus_segment_seconds',
                    production_ready=False, approval='SHADOW', supported_from=args.supported_from,
                    supported_until=args.supported_until, training_until=split['train_until'],
                    created_at=datetime.now(timezone.utc).isoformat(), serving_external_io=False)
    write(out / 'serving_manifest.json', manifest)
    shutil.copyfile(Path(__file__).resolve().parents[1] / 'static_bus_features.py', out / 'static_bus_features.py')
    names = ['model.txt', 'edges.parquet', 'fixed_statistics.parquet', 'input_spec.json', 'feature_schema.json',
             'preprocessing.json', 'metrics.json', 'coverage.json', 'source_usage.json', 'serving_manifest.json', 'static_bus_features.py']
    write(out / 'bundle.json', dict(format='ddubi-static-bus-v13', files={n: sha(out / n) for n in names}))
    progress('complete', metrics=report['lightgbm'], coverage=coverage)


if __name__ == '__main__':
    main()
