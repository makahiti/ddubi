"""확보한 카카오 응답의 시간 차이를 재현 가능하게 감사한다.

총시간과 step 합을 비교할 뿐 차이를 대기시간으로 해석하지 않는다. 이름·좌표·랜딩 URL을
보고서에 쓰지 않고 파일 해시, 후보 순번, 초 단위 값만 기록한다. 외부 API 호출은 없다.
"""
import argparse
import collections
import hashlib
import json
from pathlib import Path


def audit(paths):
    """입력 파일별 원문 해시와 후보별 잔여시간을 반환한다. 음수/잘못된 단위는 실패한다."""
    rows, files = [], []
    route_keys, step_keys, modes = collections.Counter(), collections.Counter(), collections.Counter()
    for path in paths:
        raw = path.read_bytes()
        data = json.loads(raw.decode('utf-8-sig'))
        if data.get('status') != 'OK' or not data.get('routes'):
            raise ValueError('KAKAO_RESPONSE_NOT_OK')
        files.append(dict(file=path.name, sha256=hashlib.sha256(raw).hexdigest()))
        for index, route in enumerate(data['routes']):
            total = route['properties']['totalTime']
            times = [step['properties']['time'] for step in route['steps']]
            if any(type(v) is not int or v < 0 for v in [total] + times):
                raise ValueError('INVALID_SECONDS')
            route_keys.update(route['properties'].keys())
            for step in route['steps']:
                step_keys.update(step['properties'].keys())
                modes[step['properties']['type']] += 1
            rows.append(dict(file=path.name, candidateIndex=index, totalSeconds=total,
                             stepSeconds=sum(times), residualSeconds=total-sum(times)))
    if not rows:
        raise ValueError('NO_CANDIDATES')
    residuals = [r['residualSeconds'] for r in rows]
    return dict(files=files, candidates=len(rows), stepsByMode=dict(modes),
                positiveResidual=sum(v > 0 for v in residuals), zeroResidual=residuals.count(0),
                negativeResidual=sum(v < 0 for v in residuals), minResidualSeconds=min(residuals),
                maxResidualSeconds=max(residuals), routeFields=sorted(route_keys), stepFields=sorted(step_keys),
                decompositionVerified=False, reason='RESIDUAL_HAS_NO_TYPED_COMPONENTS', rows=rows)


def main():
    """명시한 디렉터리의 번호.json만 검사해 다른 JSON 설정이나 비밀 파일을 읽지 않는다."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--responses', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    paths = sorted((p for p in args.responses.glob('*.json') if p.stem.isdigit()), key=lambda p: int(p.stem))
    report = audit(paths)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps({k: v for k, v in report.items() if k not in ('rows', 'files')}, ensure_ascii=True))


if __name__ == '__main__':
    main()
