"""추가 표준링크 XLSX의 ID 대응 범위를 검토한다. 완전 통제/방향 검증으로 승격하지 않는다.

서비스 링크↔표준 링크 두 열은 시간 유효성·AccInfo namespace·차로 전체 통제 증거가 아니다.
기존 BIS 방향 경로 후보와 표준 ID가 연결되는지만 세며, 모호한 대응은 제외한다.
"""
import argparse
from collections import defaultdict,Counter
import hashlib
import json
from pathlib import Path
import openpyxl


def audit(workbook,candidates):
    """원본 ID를 문자열로 보존하고 모든 표준 링크가 유일한 서비스 링크로 연결되는 후보를 집계한다."""
    book=openpyxl.load_workbook(workbook,read_only=True,data_only=True)
    lookup=defaultdict(set);pairs=set()
    try:
        rows=iter(book.active.values);header=next(rows)
        if tuple(header)!=('서비스링크','표준링크아이디'):raise ValueError('source header mismatch')
        for service,standard in rows:
            service,standard=str(service),str(standard)
            if not service.isdigit() or not standard.isdigit():raise ValueError('invalid provider ID')
            lookup[standard].add(service);pairs.add((service,standard))
    finally:book.close()
    counts=Counter()
    with Path(candidates).open(encoding='utf-8') as stream:
        for line in stream:
            row=json.loads(line);options=[lookup.get(x,set()) for x in row['bisLinkIds']]
            counts['completeUniqueIdBridge' if options and all(len(o)==1 for o in options) else 'missingOrAmbiguousIdBridge']+=1
    return dict(sourceFile=Path(workbook).name,sourceSha256=hashlib.sha256(Path(workbook).read_bytes()).hexdigest(),
        candidateFileSha256=hashlib.sha256(Path(candidates).read_bytes()).hexdigest(),
        sourceReference='filename: 2026-03; publication validity unverified',pairCount=len(pairs),
        standardLinks=len(lookup),ambiguousStandardLinks=sum(len(x)>1 for x in lookup.values()),counts=dict(counts),
        operationalMappings=0,status='REVIEW_REQUIRED',
        missingEvidence=['current directed bus path validity','AccInfo link namespace equivalence','full closure vs partial restriction with effective interval'])


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    for name in ('workbook','candidates','output'):p.add_argument('--'+name,type=Path,required=True)
    a=p.parse_args();result=audit(a.workbook,a.candidates);a.output.parent.mkdir(parents=True,exist_ok=True)
    a.output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8');print(json.dumps(result,ensure_ascii=True))
