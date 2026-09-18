"""로컬 가공 결과의 전송 전 무결성 검사. 무결성 통과는 운영 승인과 다르다."""
import argparse
import hashlib
import json
from pathlib import Path


def digest(path):
    value = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1048576), b''):
            value.update(chunk)
    return value.hexdigest()


def valid_digest(value):
    return isinstance(value, str) and len(value) == 64 and all(c in '0123456789abcdef' for c in value)


def provenance_failures(manifest):
    # 초기 가공, 버스, 지하철, 도로의 실제 완료 계약을 각각 수용한다.
    # 원본 해시의 기록 형식만 검사한다. 현재 원본 재검증이나 운영 승인이 아니다.
    if 'sourceSha256' in manifest:
        return ['INVALID_' + field for field in ('sourceSha256', 'codeSha256')
                if not valid_digest(manifest.get(field))]
    inputs = manifest.get('inputs')
    if not isinstance(inputs, dict) or not inputs:
        return ['INPUT_PROVENANCE_MISSING']
    hashes = inputs.get('sha256', inputs)
    if not isinstance(hashes, dict) or not hashes or not all(valid_digest(v) for v in hashes.values()):
        return ['INPUT_HASHES_INVALID']
    # 코드 해시는 별도 필드 또는 입력 파일 목록의 Python 스크립트에 기록된다.
    code = manifest.get('codeSha256')
    code_map_valid = isinstance(code, dict) and bool(code) and all(
        isinstance(k, str) and k.endswith('.py') and valid_digest(v) for k, v in code.items())
    if not valid_digest(code) and not code_map_valid and not any(str(k).endswith('.py') for k in hashes):
        return ['CODE_PROVENANCE_MISSING']
    return []


def verify(folder):
    root = folder.resolve()
    failures = []
    try:
        manifest = json.loads((root / 'complete.json').read_text(encoding='utf-8'))
    except (OSError, ValueError):
        return dict(integrityVerified=False, productionApproved=False,
                    failures=['MISSING_OR_INVALID_COMPLETION_MANIFEST'])
    if not isinstance(manifest, dict):
        return dict(integrityVerified=False, productionApproved=False,
                    failures=['MANIFEST_OBJECT_REQUIRED'])
    outputs = manifest.get('outputs')
    if not isinstance(outputs, dict) or not outputs:
        failures.append('OUTPUTS_MISSING')
    else:
        for name, expected in outputs.items():
            if not isinstance(name, str) or not name or not valid_digest(expected):
                failures.append('INVALID_OUTPUT_ENTRY')
                continue
            path = (root / name).resolve()
            if not path.is_relative_to(root) or path == root:
                failures.append('OUTPUT_PATH_ESCAPE')
            elif not path.is_file():
                failures.append('OUTPUT_FILE_MISSING:' + name)
            elif digest(path) != expected:
                failures.append('OUTPUT_CHECKSUM_MISMATCH:' + name)
    failures.extend(provenance_failures(manifest))
    summary = manifest.get('summary', {})
    return dict(integrityVerified=not failures, productionApproved=False,
                failures=failures, operationalEligibilityAssessed=False,
                blockedBy=manifest.get('blockedBy', summary.get('blockedBy', []) if isinstance(summary, dict) else []),
                result=manifest.get('result', manifest.get('summary', {})))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('folder', type=Path)
    args = parser.parse_args()
    result = verify(args.folder)
    print(json.dumps(result, ensure_ascii=False))
    raise SystemExit(0 if result['integrityVerified'] else 1)
