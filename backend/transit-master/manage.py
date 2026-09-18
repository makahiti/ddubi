"""공통 배포 묶음의 바이트 무결성과 버스 master/crosswalk 호환성을 검사한다.

manifest의 SHA는 압축 파일 자체 기준이다. 원천 기준일을 모르면 null을 유지하며
검증일을 기준일로 위장하지 않는다. check는 읽기 전용, build만 manifest를 갱신한다.
env는 검증 후 모든 소비자가 사용할 절대경로/고정 SHA를 출력한다. 운영 적용은 별도다.
"""
import argparse
import gzip
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent


def inventory(root=ROOT):
    """파일별 SHA와 출처 manifest를 묶고 실제 master SHA 관계를 검증한다."""
    bus = json.loads((root / 'bus/metropolitan-bus-v6.json.manifest.json').read_text(encoding='utf-8'))
    cw = json.loads(gzip.decompress((root / 'bus/metropolitan-bus-v6.crosswalk.json.gz').read_bytes()))
    metro = json.loads((root / 'subway/metropolitan-subway-master.sources.json').read_text(encoding='utf-8'))
    # 1. 개별 바이트 검증 → 2. crosswalk가 참조한 원본 검증 → 3. 배포 조합 기록.
    bus_sha = hashlib.sha256((root / 'bus/metropolitan-bus-v6.json.gz').read_bytes()).hexdigest()
    cw_sha = hashlib.sha256((root / 'bus/metropolitan-bus-v6.crosswalk.json.gz').read_bytes()).hexdigest()
    cw_manifest = json.loads((root / 'bus/metropolitan-bus-v6.crosswalk.json.manifest.json').read_text(encoding='utf-8'))
    if bus_sha != bus['sha256'] or cw['masterSha256'] != bus_sha or cw['masterVersion'] != bus['version']:
        raise ValueError('bus master/crosswalk version or SHA mismatch')
    if cw_sha != cw_manifest['sha256']:
        raise ValueError('crosswalk SHA mismatch')
    subway = json.loads(gzip.decompress((root / 'subway/metropolitan-subway-master.json.gz').read_bytes()))
    subway_sha = hashlib.sha256((root / 'subway/metropolitan-subway-master.json.gz').read_bytes()).hexdigest()
    if subway['dataVersion'] != metro['dataVersion'] or subway_sha != metro['artifactSha256']:
        raise ValueError('subway source version mismatch')
    files = []
    for folder, version, source in [('bus', bus['version'], 'bus/metropolitan-bus-v6.json.manifest.json'),
                                     ('subway', metro['dataVersion'], 'subway/metropolitan-subway-master.sources.json')]:
        for path in sorted((root / folder).glob('metropolitan-*')):
            files.append(dict(path=path.relative_to(root).as_posix(), version=version,
                              sha256=hashlib.sha256(path.read_bytes()).hexdigest(),
                              source=source, referenceDate=None))
    return dict(schemaVersion=1, files=files, compatibleSets=[dict(
        busMasterVersion=bus['version'], busMasterSha256=bus_sha,
        busCrosswalkSha256=cw_sha, idPolicy=cw['idPolicy'],
        subwayVersion=metro['dataVersion'], roadVersion=None)],
        road=dict(status='REVIEW_REQUIRED', artifact=None),
        referenceDateNote='출처별 기준일은 source 문서 참조. 단일 기준일이 검증되지 않아 null 사용.')


def check(root=ROOT):
    """저장된 manifest와 재계산한 결과가 다르면 배포를 중단한다. 파일 수정은 없다."""
    expected = json.loads((root / 'manifest.json').read_text(encoding='utf-8'))
    actual = inventory(root)
    if expected != actual:
        raise ValueError('transit master manifest mismatch; review artifacts before rebuilding')
    return actual


def main():
    """검증된 동일 묶음의 환경값을 출력한다. 컨테이너에서는 mount 경로를 지정한다."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['build', 'check', 'env'])
    parser.add_argument('--mount-root', help='소비자에서 보이는 공통 폴더 절대경로')
    args = parser.parse_args()
    if args.command == 'build':
        (ROOT / 'manifest.json').write_text(json.dumps(inventory(), ensure_ascii=False, indent=2)+'\n', encoding='utf-8', newline='\n')
        return
    manifest = check()
    if args.command == 'check':
        print('transit master manifest: OK')
        return
    base = (args.mount_root or ROOT.as_posix()).rstrip('/')
    combo = manifest['compatibleSets'][0]
    print('BUS_CROSSWALK_PATH='+base+'/bus/metropolitan-bus-v6.crosswalk.json.gz')
    print('BUS_CROSSWALK_SHA256='+combo['busCrosswalkSha256'])
    print('DDUBI_METROPOLITAN_BUS_ARTIFACT='+base+'/bus/metropolitan-bus-v6.json.gz')
    print('DDUBI_METROPOLITAN_BUS_SHA256='+combo['busMasterSha256'])
    print('DDUBI_TRANSIT_MAPPING_SUBWAY_MASTER_RESOURCE=file:'+base+'/subway/metropolitan-subway-master.json.gz')


if __name__ == '__main__':
    main()
