"""새 v13 ZIP을 별도 디렉터리에 설치하고 SHA·golden 예측을 확인한다.

기존 모델/활성 포인터를 변경하지 않는다. ZIP 경로 이탈, 중복 이름, 비정상 크기를
먼저 거절한다. 검증 실패한 새 폴더는 활성화하지 않으며 조사용으로 남긴다.
"""
import argparse
import hashlib
import json
from pathlib import Path
import sys
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from predictor import checked_path
from schemas import BatchRequest
from static_bus_predictor import StaticBusPredictor


def main():
    """다운로드 SHA → 경로·파일 SHA → 동일 CPU 예측 순으로 확인 후 설치 결과를 기록한다."""
    parser = argparse.ArgumentParser()
    for name in ('archive', 'sha256', 'destination'):
        parser.add_argument('--' + name, required=True)
    args = parser.parse_args()
    source = Path(args.archive)
    if hashlib.sha256(source.read_bytes()).hexdigest() != args.sha256.lower():
        raise ValueError('ARCHIVE_SHA_MISMATCH')
    root = Path(args.destination).resolve()
    root.mkdir(parents=True, exist_ok=False)
    with zipfile.ZipFile(source) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)) or sum(x.file_size for x in archive.infolist()) > 1024 ** 3:
            raise ValueError('ARCHIVE_LIMIT_INVALID')
        for name in names:
            target = checked_path(root, name)
            if target.parent != root or not name or name.endswith('/'):
                raise ValueError('ARCHIVE_PATH_INVALID')
        for name in names:
            (root / name).write_bytes(archive.read(name))
    model = StaticBusPredictor(root)
    golden = json.loads((root / 'golden.json').read_text(encoding='utf-8'))
    rows = [dict(row, segment_key=str(i)) for i, row in enumerate(golden['requests'])]
    result = model.predict(BatchRequest.model_validate(dict(request_id='installation-golden', segments=rows)))
    actual = [r['prediction']['mean_seconds'] for r in result['results'] if r['prediction'] is not None]
    if len(actual) != len(golden['predictions']) or not actual:
        raise ValueError('GOLDEN_COVERAGE_MISMATCH')
    error = max(abs(a - b) for a, b in zip(actual, golden['predictions'], strict=True))
    if error > 1e-6:
        raise ValueError('GOLDEN_PREDICTION_MISMATCH')
    report = dict(model=model.metadata(), archive_sha256=args.sha256, golden_rows=len(actual), max_difference_seconds=error)
    (root / 'installation-verification.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
    print(json.dumps(report))


if __name__ == '__main__':
    main()
