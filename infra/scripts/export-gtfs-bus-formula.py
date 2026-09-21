"""프로젝트 ID로 매핑이 끝난 GTFS 버스 수식 결과를 업로드용 gzip 청크로 만든다.

입력은 한 줄에 한 JSON 객체인 JSONL이다. 이 도구는 GTFS 원본을 다시 계산하거나
정류장 이름으로 매핑하지 않는다. 별도 분석 배치가 만든 최종 초 값을 현재 프로젝트
마스터의 canonical route/stop component와 양쪽 순번에 연결한 결과만 받는다.

필수 입력 필드:
namespace, routeId, fromStopId, toStopId, fromOrder, toOrder, hourSlot,
meanSeconds, sampleCount

경기·인천·부산의 route/stop ID는 각각 GBIS., INCHEON_PUBLIC_CSV., BUSAN_GTFS.
접두사를 포함해야 한다. 출력의 dow=1은 실제 일요일 관측이 아니라 GTFS 템플릿 조회키다.
"""
import argparse
import datetime
import gzip
import hashlib
import json
from pathlib import Path
import re

NAMESPACE_PREFIX={"GBIS":"GBIS.","INCHEON_PUBLIC_CSV":"INCHEON_PUBLIC_CSV.","BUSAN_GTFS":"BUSAN_GTFS."}


def sha(path):
    """원본과 출력의 동일성을 확인할 SHA-256을 스트리밍으로 계산한다."""
    digest=hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda:stream.read(1024*1024),b""): digest.update(block)
    return digest.hexdigest()


def token(value,maximum):
    return isinstance(value,str) and re.fullmatch(r"[A-Za-z0-9._-]{1,"+str(maximum)+r"}",value) is not None


def convert(row,master_version,valid_from,valid_until,source_sha):
    """한 계산 완료값을 Backend BusReferenceService 계약으로 변환한다."""
    namespace=row.get("namespace");prefix=NAMESPACE_PREFIX.get(namespace)
    if prefix is None: raise ValueError("UNSUPPORTED_NAMESPACE")
    for field in ("routeId","fromStopId","toStopId"):
        value=row.get(field)
        if not token(value,80) or not value.startswith(prefix): raise ValueError("NON_CANONICAL_ID:"+field)
    for field in ("fromOrder","toOrder","hourSlot","sampleCount"):
        if type(row.get(field)) is not int: raise ValueError("INVALID_INTEGER:"+field)
    if row["fromOrder"]<1 or row["toOrder"]!=row["fromOrder"]+1 or row["fromStopId"]==row["toStopId"]:
        raise ValueError("INVALID_OCCURRENCE")
    if not 0<=row["hourSlot"]<=23 or row["sampleCount"]<1: raise ValueError("INVALID_SAMPLE")
    mean=row.get("meanSeconds")
    if type(mean) not in (int,float) or not 0<float(mean)<=3600: raise ValueError("INVALID_MEAN")
    return dict(masterVersion=master_version,namespace=namespace,routeId=row["routeId"],
        fromStopId=row["fromStopId"],toStopId=row["toStopId"],fromOrder=row["fromOrder"],
        toOrder=row["toOrder"],dow=1,hourSlot=row["hourSlot"],sampleKind="GTFS_SCHEDULE_FORMULA",
        meanSeconds=float(mean),sampleCount=row["sampleCount"],observedDates=0,
        observationStart=valid_from,observationEnd=valid_until,sourceSha256=source_sha)


def run(source,output,master_version,valid_from,valid_until):
    """전체 입력 검증 뒤 1,000행 단위 결정적 gzip과 manifest를 원자적으로 만든다."""
    if output.exists(): raise ValueError("OUTPUT_ALREADY_EXISTS")
    if not token(master_version,100): raise ValueError("INVALID_MASTER_VERSION")
    start=datetime.date.fromisoformat(valid_from);end=datetime.date.fromisoformat(valid_until)
    if end<start: raise ValueError("INVALID_VALIDITY")
    source_sha=sha(source);code_sha=sha(Path(__file__))
    rows=[]
    with source.open(encoding="utf-8-sig") as stream:
        for number,line in enumerate(stream,1):
            if not line.strip(): continue
            try: rows.append(convert(json.loads(line),master_version,valid_from,valid_until,source_sha))
            except (ValueError,TypeError,KeyError,json.JSONDecodeError) as error:
                raise ValueError("INVALID_SOURCE_ROW:"+str(number)+":"+str(error)) from error
    if not rows: raise ValueError("EMPTY_SOURCE")
    # 입력 순서와 무관하게 같은 artifact가 나오도록 정확키를 정렬하고 중복을 거절한다.
    key=lambda row:(row["namespace"],row["routeId"],row["fromStopId"],row["toStopId"],
                    row["fromOrder"],row["toOrder"],row["hourSlot"])
    rows.sort(key=key)
    if any(key(rows[i-1])==key(rows[i]) for i in range(1,len(rows))): raise ValueError("DUPLICATE_KEY")
    version="bus-formula-"+hashlib.sha256((source_sha+code_sha+master_version+valid_from+valid_until).encode()).hexdigest()[:24]
    output.mkdir(parents=True)
    outputs={}
    for chunk,start_index in enumerate(range(0,len(rows),1000)):
        payload=json.dumps({"rows":rows[start_index:start_index+1000]},ensure_ascii=False,separators=(",",":"),allow_nan=False).encode()
        path=output/(f"{chunk:06d}.json.gz");temporary=path.with_suffix(".partial")
        temporary.write_bytes(gzip.compress(payload,compresslevel=1,mtime=0));temporary.replace(path)
        outputs[path.name]=sha(path)
    manifest=dict(schemaVersion=2,dataVersion=version,masterVersion=master_version,rows=len(rows),chunks=len(outputs),
        complete=True,productionReady=False,outputs=outputs,scannedRows=len(rows),rejectedRows=0,rejectionReasons={},
        sourceManifestSha256=source_sha,codeSha256=code_sha,sampleKinds={"GTFS_SCHEDULE_FORMULA":len(rows)},
        validity=dict(start=valid_from,end=valid_until,meaning="GTFS_FEED_OR_APPROVED_ARTIFACT_VALIDITY"),
        limitations=["SCHEDULE_NOT_OBSERVED","NO_DAY_TYPE_EFFECT","NO_REALTIME_CORRECTION","PROJECT_MASTER_MAPPING_REQUIRED"])
    temporary=output/"export.json.tmp"
    temporary.write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+"\n",encoding="utf-8",newline="\n")
    temporary.replace(output/"export.json")
    return {k:v for k,v in manifest.items() if k!="outputs"}


if __name__=="__main__":
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source",type=Path);parser.add_argument("output",type=Path)
    parser.add_argument("--master-version",required=True)
    parser.add_argument("--valid-from",required=True);parser.add_argument("--valid-until",required=True)
    args=parser.parse_args()
    print(json.dumps(run(args.source,args.output,args.master_version,args.valid_from,args.valid_until),ensure_ascii=False))
