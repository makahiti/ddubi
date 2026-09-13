"""검증한 공식 원천을 SHA로 재현한다. 기존 파일을 덮어쓰거나 최신으로 자동 교체하지 않는다."""
import argparse
import hashlib
import json
import urllib.parse
import urllib.request
from pathlib import Path


def download(destination):
    """고정 출처 목록을 받아 감사 도구가 읽는 sources.json을 기록한다. 원격 변경 시 실패한다."""
    root=Path(destination);root.mkdir(parents=True,exist_ok=True)
    manifest=Path(__file__).parent/'validation'/'sources.json'
    sources=json.loads(manifest.read_text(encoding='utf-8'))
    for source in sources:
        filename=source['path']
        if Path(filename).name!=filename:
            raise ValueError('source filename must be a basename')
        target=root/filename
        if target.exists():
            if hashlib.sha256(target.read_bytes()).hexdigest()!=source['sha256']:
                raise ValueError('existing source differs; choose a new destination')
            continue
        body=urllib.parse.urlencode(source['form']).encode() if source.get('method')=='POST' else None
        request=urllib.request.Request(source['downloadUrl'],data=body)
        with urllib.request.urlopen(request,timeout=90) as response:
            raw=response.read(100_000_001)
        if len(raw)>100_000_000 or hashlib.sha256(raw).hexdigest()!=source['sha256']:
            raise ValueError('official file changed; re-audit required: '+filename)
        target.write_bytes(raw)
    (root/'sources.json').write_bytes(manifest.read_bytes())


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--destination',required=True)
    download(parser.parse_args().destination)
