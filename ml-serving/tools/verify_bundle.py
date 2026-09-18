"""GPU 원본 추론과 로컬 HTTP 응답을 같은 요청으로 대조하고 실제 지연을 기록한다.

합성 모델을 사용하지 않는다. 모델/참조가 없으면 실패한다. 결과 보고서는 소요시간
모델의 정확도 평가가 아니라 이식 동등성·배치 계약 확인이며 production_ready를 바꾸지 않는다.
"""
import argparse
import json
from pathlib import Path
import sys
import time

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from fastapi.testclient import TestClient
from app import create_app
from predictor import Predictor


def verify(bundle_dir, output_dir, golden_path=None):
    """같은 실제 입력을 두 번 실행해 원본 값, 순서, 결측 수, 재현성을 대조한다."""
    root = Path(bundle_dir)
    golden = json.loads((Path(golden_path) if golden_path else root / 'golden.json').read_text(encoding='utf-8'))
    payload = {'request_id': 'v12-server-parity', 'segments': [
        {'segment_key': f'edge-{i+1}', 'provider_namespace': 'SEOUL_BUS', **row}
        for i, row in enumerate(golden['requests'])]}
    start = time.perf_counter()
    predictor = Predictor(root)
    load_seconds = time.perf_counter()-start
    timings = []
    responses = []
    with TestClient(create_app(predictor=predictor)) as client:
        for _ in range(2):
            start = time.perf_counter()
            result = client.post('/v1/bus/predictions:batch', json=payload)
            timings.append(time.perf_counter()-start)
            result.raise_for_status()
            responses.append(result.json())
    errors = []
    for index, (first, second) in enumerate(zip(responses[0]['results'], responses[1]['results'])):
        assert first == second, '같은 입력에서 예측 결과가 달라짐'
        if golden['unseen_id_counts'][index]:
            assert first['reason'] == 'UNSEEN_PROVIDER_ID'
            continue
        assert first['prediction'] is not None, '등록 ID에서 예측 실패'
        error = abs(first['prediction']['mean_seconds']-golden['predictions'][index])
        assert error <= 1e-5, 'GPU 서버 원본과 예측값 불일치'
        assert first['prediction']['missing_feature_count'] == golden['missing_feature_counts'][index]
        errors.append(error)
    assert errors, '실제 비교 가능한 예측이 0건'
    output = Path(output_dir)
    output.mkdir(parents=True, exist_ok=True)
    report = {'model_version': predictor.manifest['version'], 'compared_rows': len(errors),
              'max_absolute_difference_seconds': max(errors), 'load_seconds': load_seconds,
              'batch_seconds': timings, 'production_ready': False,
              'purpose': '서버 원본과 로컬 HTTP 이식 동등성; 모델 정확도 평가 아님'}
    for name, content in [('request.json', payload), ('response.json', responses[0]), ('verification.json', report)]:
        (output / name).write_text(json.dumps(content, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(report, ensure_ascii=False))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bundle', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--golden', type=Path, help='서버 원본 검증 파일을 명시하면 ZIP 내 표본 대신 사용')
    args = parser.parse_args()
    verify(args.bundle, args.output, args.golden)
