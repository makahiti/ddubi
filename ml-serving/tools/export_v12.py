"""GPU 서버의 v12 산출물을 CPU 추론용으로 묶는다. 학습·원본 파일은 변경하지 않는다.

버전이 고정된 학습 소스에서 날짜/이력/인코더 함수를 추출하여 import 시 학습이
실행되는 의존을 제거한다. 지정한 서울 날짜 범위에 필요한 77~126일 지연 자료만
복사하고, 원래 추론 함수로 만든 비교 결과와 SHA256을 함께 보존한다.
파일이 없는 날짜도 명시하여 실제 0과 수집 결측을 구분한다. DuckDB/GPU를 사용하지 않는다.
"""
import argparse
import ast
import hashlib
import importlib.metadata
import json
import os
from pathlib import Path
import sys
import zipfile


def export(root, start, end, destination):
    """자신의 실험 폴더만 읽어 새 ZIP을 배타 생성한다. 같은 이름은 덮어쓰지 않는다.

원래 모델의 production_ready 값은 그대로 두며 운영 승인으로 승격하지 않는다.
골든 결과는 정확도 평가가 아니라 전처리·추론 이동 후 동등성 비교용이다.
"""
    if hasattr(os, 'sched_getaffinity'):
        os.sched_setaffinity(0, sorted(os.sched_getaffinity(0))[:16])
    runtime = root / 'j-moo/experiments/runtime'
    run = root / 'j-moo/experiments/runs/20260908-v12'
    reference = root / 'data/normalized/20260908-v12'
    sys.path.insert(0, str(runtime))
    import pandas as pd
    import ddubi_train_v12 as training
    import ddubi_predict_v12 as original

    dates = pd.date_range(start, end)
    if not 1 <= len(dates) <= 31:
        raise ValueError('한 묶음은 1~31일만 허용한다.')
    # 1. 함수 몸체를 원문 그대로 복사한다. I/O 루트만 인수로 바꿔 서버 절대경로를 제거한다.
    source = (runtime / 'ddubi_train_v12.py').read_text()
    names = {'CAT', 'KEY', 'BASE', 'GEO', 'HISTORY', 'DEMAND', 'PASS', 'AUX', 'NUMERIC', 'LAGS'}
    functions = {'calendar_features', 'history_stats', 'attach_features', 'matrix'}
    pieces = []
    for node in ast.parse(source).body:
        if isinstance(node, ast.Assign) and any(isinstance(t, ast.Name) and t.id in names for t in node.targets):
            pieces.append(ast.get_source_segment(source, node))
        elif isinstance(node, ast.FunctionDef) and node.name in functions:
            fragment = ast.get_source_segment(source, node)
            if node.name == 'attach_features':
                fragment = fragment.replace('def attach_features(d):', 'def attach_features(d, reference_root):', 1)
                fragment = fragment.replace('    if d.empty:', '    OUT = Path(reference_root)\n    if d.empty:', 1)
            pieces.append(fragment)
    portable = ('"""v12 학습 소스에서 추출한 공통 특성 변환. tools/export_v12.py로 재생성한다.\n'
                '입력 ID는 서울 원천 ID이며 출력은 학습 인코더 순서의 행렬이다. 단위는 초, 날짜는 서울 기준이다.\n'
                '학습 실행·GPU 초기화·서버 절대경로 의존을 포함하지 않는다.\n"""\n'
                'from pathlib import Path\nfrom types import SimpleNamespace\n'
                'import numpy as np\nimport pandas as pd\nimport pyarrow.parquet as pq\nimport holidays\n'
                'core = SimpleNamespace(HOURS=' + repr(training.core.HOURS) + ')\n\n'
                + '\n\n'.join(pieces) + '\n')
    # 2. 날짜 범위에 필요한 원천 파일만 명시적으로 선택한다. 전량 학습 입력은 복사하지 않는다.
    paths = {reference / 'contexts/coordinates.parquet', reference / 'contexts/monthly_demand.parquet'}
    for date in dates:
        for lag in training.LAGS:
            paths.add(reference / 'bus_daily' / f'{(date-pd.Timedelta(days=lag)).date()}.parquet')
        for kind, lags in [('OA-21218', training.LAGS[:4]), ('OA-21219', [77]), ('OA-21220', [77])]:
            for lag in lags:
                paths.add(reference / 'aux_daily' / kind / f'{(date-pd.Timedelta(days=lag)).date()}.parquet')
    # 3. 원래 함수의 실예측과 입력 행렬을 보존한다. 정답이나 정확도 수치로 해석하지 않는다.
    sample_file = reference / 'bus_daily' / f'{(dates[0]-pd.Timedelta(days=77)).date()}.parquet'
    spec = json.loads((run / 'models/input_spec.json').read_text())
    sample = pd.read_parquet(sample_file, columns=training.KEY)
    # 원천 파일에는 학습 vocab 밖의 노선도 있다. 모두 거절되는 예시로는 수치 동등성을
    # 검증할 수 없으므로 등록 ID인 실관측 행을 선택하고 빈 표본은 명시적으로 실패시킨다.
    for name in training.CAT:
        sample = sample[sample[name].isin(spec['vocab'][name])]
    sample = sample.head(10).copy()
    if sample.empty:
        raise ValueError('수치 검증에 사용할 등록 ID 구간이 없다.')
    sample['service_date'] = dates[0].strftime('%Y-%m-%d')
    sample['hour_slot'] = 9
    records = sample.to_dict(orient='records')
    result = original.predict(records)
    manifest = json.loads((run / 'models/serving_manifest.json').read_text())
    golden = {'requests': records, 'predictions': result.prediction_seconds.tolist(),
              'missing_feature_counts': result.missing_feature_count.tolist(),
              'unseen_id_counts': result.unseen_id_count.tolist()}
    versions = {n: importlib.metadata.version(n) for n in
                ['numpy', 'pandas', 'scikit-learn', 'lightgbm', 'joblib', 'pyarrow', 'holidays']}
    metadata = {'version': manifest['version'], 'supported_from': str(dates[0].date()),
                'supported_until': str(dates[-1].date()), 'production_ready': manifest['production_ready'],
                'training_source_sha256': hashlib.sha256(source.encode()).hexdigest(),
                'files': {}, 'missing_files': [], 'runtime_versions': versions}
    destination.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(destination, 'x', zipfile.ZIP_DEFLATED, compresslevel=1) as archive:
        for path in sorted(paths):
            relative = path.relative_to(reference).as_posix()
            if not path.exists():
                metadata['missing_files'].append(relative)
                continue
            archive.write(path, 'references/' + relative)
            with path.open('rb') as handle:
                metadata['files']['references/' + relative] = hashlib.file_digest(handle, 'sha256').hexdigest()
        archive.writestr('portable_features.py', portable)
        archive.writestr('golden.json', json.dumps(golden, ensure_ascii=False, indent=2))
        for name in ['input_spec.json', 'serving_manifest.json']:
            archive.write(run / 'models' / name, name)
        for name in ['selected_model.joblib', 'input_spec.json', 'serving_manifest.json']:
            with (run / 'models' / name).open('rb') as handle:
                metadata['files'][name] = hashlib.file_digest(handle, 'sha256').hexdigest()
        metadata['files']['portable_features.py'] = hashlib.sha256(portable.encode()).hexdigest()
        archive.writestr('bundle.json', json.dumps(metadata, ensure_ascii=False, indent=2))
    print(json.dumps({'export': str(destination), 'bytes': destination.stat().st_size,
                      'predictions': golden['predictions'], 'runtime_versions': versions}))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, required=True)
    parser.add_argument('--start', required=True)
    parser.add_argument('--end', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    export(args.root, args.start, args.end, args.output)
