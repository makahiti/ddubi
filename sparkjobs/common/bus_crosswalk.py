"""고정 배포 대응표를 Spark BUS 최신값에 조인하는 경계.

정적 gzip(최대 160MB)만 driver에서 검증·인덱싱한다. 원천 이벤트 DataFrame은 driver로
collect하지 않는다. provider key별 후보가 하나인 경우만 ID를 전달하고, Collector가 주장한
다른 SHA/버전은 비워서 배포 불일치를 드러낸다. 도착값을 구간 운행시간으로 바꾸지 않는다.
"""
import gzip
import hashlib
import io
import json
import re
from pyspark.sql import functions as F, types as T

POLICY='canonical-adjacent-v2'
SCHEMA=T.StructType([T.StructField(n,T.StringType(),False) for n in
    ('lookupType','namespace','routeId','fromStopId','toStopId','segmentId')])


def load_crosswalk(spark,path,expected_sha):
    """기동 시 SHA·ID를 검증하고 집계 인덱스를 한 번 물리화한다. 잡 종료 때 unpersist한다."""
    with open(path,'rb') as stream:raw=stream.read()
    if not expected_sha or hashlib.sha256(raw).hexdigest()!=expected_sha:raise ValueError('crosswalk SHA mismatch')
    # SHA를 확인한 바로 그 바이트를 해제한다. 배포 중 경로가 교체되어도 다른 파일을 다시 열지 않는다.
    with gzip.GzipFile(fileobj=io.BytesIO(raw)) as stream:content=stream.read(160_000_001)
    if len(content)>160_000_000:raise ValueError('crosswalk too large')
    data=json.loads(content)
    if data.get('schemaVersion')!=1 or data.get('idPolicy')!=POLICY or not data.get('masterVersion'):
        raise ValueError('crosswalk policy/version mismatch')
    if not re.fullmatch('[a-f0-9]{64}',data.get('masterSha256','')) or data.get('edgeCount')!=len(data['edges']):
        raise ValueError('crosswalk manifest mismatch')
    rows=[];seen=set()
    for e in data['edges']:
        ns=e['namespace'];parts=[e['routeId'],e['fromStopId'],e['toStopId']]
        if ns not in {'SEOUL_BUS','GBIS','INCHEON_PUBLIC_CSV'} or any(not re.fullmatch('[A-Za-z0-9._-]{1,50}',v) for v in parts):
            raise ValueError('crosswalk source ID invalid')
        components=[v if ns=='SEOUL_BUS' else ns+'.'+v for v in parts]
        if any(len(v)>50 for v in components) or e['segmentId']!='BUS:'+':'.join(components):
            raise ValueError('crosswalk canonical ID mismatch')
        key=(ns,parts[0],e['fromOrder'])
        if key in seen or e['fromOrder']<1 or e['toOrder']!=e['fromOrder']+1:raise ValueError('crosswalk order invalid')
        seen.add(key)
        rows.extend([('bus.arrival',ns,parts[0],'',parts[2],e['segmentId']),
                     ('bus.position',ns,parts[0],parts[1],parts[2],e['segmentId'])])
    # 동명/순환 정류장의 여러 진입 edge를 첫 행으로 뽑지 않고 후보 집합으로 남긴다.
    # 21만 조회 행을 F.lit(JSON)으로 계획에 넣으면 foreachBatch의 매 action마다
    # JSON 파싱/최적화와 집계가 반복되어 driver 힙을 소진한다. 명시 스키마의 정적
    # DataFrame으로 만들고 최종 집계 인덱스만 캐시한다. cache()는 지연 평가이므로
    # count()로 스트리밍 시작 전에 물리화한다. Spark 기본 MEMORY_AND_DISK 캐시는
    # 메모리가 부족할 때 디스크를 사용할 수 있어 강제 broadcast보다 보수적이다.
    frame=spark.createDataFrame(rows,SCHEMA)
    index=frame.groupBy('lookupType','namespace','routeId','fromStopId','toStopId')\
        .agg(F.sort_array(F.collect_set('segmentId')).alias('canonicalCandidates')).cache()
    try:
        index.count()
    except Exception:
        # 기동 실패 시 부분 캐시를 남기지 않는다. SHA/버전 검증 실패를 fallback으로 숨기지 않는다.
        index.unpersist()
        raise
    return index,data['masterVersion'],expected_sha


def apply_crosswalk(df,crosswalk):
    """정규화 BUS 관측에 대응표를 조인한다. SUBWAY 등 다른 모드의 기존 segmentIds는 유지한다."""
    bus=F.col('eventType').isin('bus.position','bus.arrival')
    empty=F.array().cast('array<string>')
    scope=F.when(F.col('eventType')=='bus.arrival',F.lit('ARRIVAL_TARGET_INCOMING_EDGE')).otherwise(F.lit('EXPLICIT_ADJACENT_STOP_PAIR'))
    if crosswalk is None:
        return df.withColumn('segmentIds',F.when(bus,empty).otherwise(F.col('segmentIds')))\
            .withColumn('canonicalMapping',F.when(bus,F.struct(F.lit('UNRESOLVED').alias('state'),
                F.lit('CROSSWALK_NOT_CONFIGURED').alias('reason'),scope.alias('scope'),
                F.lit(None).cast('string').alias('masterVersion'),F.lit(POLICY).alias('idPolicy'),
                F.lit(None).cast('string').alias('crosswalkSha256'))))
    index,version,sha=crosswalk
    condition=(F.col('eventType')==index.lookupType)&(F.col('providerIds.namespace')==index.namespace)\
        &(F.col('providerIds.routeId')==index.routeId)\
        &(F.when(F.col('eventType')=='bus.arrival',F.lit('')).otherwise(F.col('mappingFromStopId'))==index.fromStopId)\
        &(F.when(F.col('eventType')=='bus.arrival',F.col('providerIds.stopId')).otherwise(F.col('mappingToStopId'))==index.toStopId)
    joined=df.join(index,condition,'left').select(df['*'],index.canonicalCandidates)
    mismatch=(F.col('claimedMappingVersion').isNotNull()&(F.col('claimedMappingVersion')!=version))\
        |(F.col('claimedCrosswalkSha').isNotNull()&(F.col('claimedCrosswalkSha')!=sha))
    count=F.size(F.coalesce(F.col('canonicalCandidates'),empty))
    good=bus&~mismatch&(count==1)
    reason=F.when(mismatch,'CROSSWALK_VERSION_MISMATCH').when(count>1,'MULTIPLE_INCOMING_EDGES').when(count==1,'NONE').otherwise('PROVIDER_CROSSWALK_NOT_FOUND')
    state=F.when(mismatch,'UNRESOLVED').when(count>1,'AMBIGUOUS').when(count==1,'CONFIRMED').otherwise('UNRESOLVED')
    return joined.withColumn('segmentIds',F.when(good,F.col('canonicalCandidates')).when(bus,empty).otherwise(F.col('segmentIds')))\
        .withColumn('canonicalMapping',F.when(bus,F.struct(state.alias('state'),reason.alias('reason'),scope.alias('scope'),
            F.lit(version).alias('masterVersion'),F.lit(POLICY).alias('idPolicy'),F.lit(sha).alias('crosswalkSha256')))).drop('canonicalCandidates')
