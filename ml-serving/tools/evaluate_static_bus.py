"""동일 마스터 버전의 저장된 카카오 매핑 결과로 v13 커버리지·서빙 시간을 검증한다.

매핑을 다시 수행했다고 주장하지 않는다. 확정된 단일 공식 경로 대안만 읽고,
인접 순번 누락은 제외 이유로 남긴다. 실제 도착 정답이 없으므로 정확도는 계산하지 않는다.
"""
import argparse
from collections import Counter
from datetime import datetime, timedelta
import hashlib
import json
from pathlib import Path
import sys
import time

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from schemas import BatchRequest
from static_bus_predictor import StaticBusPredictor


def main():
    """검증된 대안별 모든 edge 성공 비율과 순차 진입시각 합산 결과를 별도로 보고한다."""
    parser = argparse.ArgumentParser()
    for name in ('bundle', 'mapping', 'output', 'departure'):
        parser.add_argument('--' + name, required=True)
    args = parser.parse_args()
    model = StaticBusPredictor(args.bundle)
    path = Path(args.mapping)
    mapping = json.loads(path.read_text(encoding='utf-8'))
    if mapping['busVersion'] != model.manifest['master_version']:
        raise ValueError('CORPUS_MASTER_MISMATCH')
    departure = datetime.fromisoformat(args.departure)
    if departure.utcoffset() != timedelta(hours=9):
        raise ValueError('KST_DEPARTURE_REQUIRED')
    counts, paths, requests = Counter(), [], {}
    for step in mapping['results']:
        if step['mode'] != 'BUS':
            continue
        for alternative in step['mapping'].get('busAlternatives', []):
            candidates = alternative['candidates']
            if alternative['identityStatus'] != 'CONFIRMED' or alternative['geometryStatus'] != 'VERIFIED' or len(candidates) != 1:
                counts['unresolved_alternatives'] += 1
                continue
            candidate = candidates[0]
            if len(candidate['stops']) < 2:
                counts['invalid_short_path'] += 1
                continue
            if candidate['namespace'] != 'SEOUL_BUS':
                counts['unsupported_provider_alternatives'] += 1
                continue
            cursor, complete, rows = departure, True, []
            for a, b in zip(candidate['stops'], candidate['stops'][1:]):
                counts['evaluated_seoul_edge_occurrences'] += 1
                key = (candidate['routeId'], a['stopId'], b['stopId'], a['sequence'])
                if b['sequence'] != a['sequence'] + 1:
                    counts['sequence_gap'] += 1
                    complete = False
                    break
                payload = dict(segment_key='edge', provider_namespace='SEOUL_BUS', provider_route_id=key[0],
                               provider_from_stop_id=key[1], provider_to_stop_id=key[2], from_stop_sequence=key[3],
                               service_date=cursor.date().isoformat(), hour_slot=cursor.hour)
                requests[key] = payload
                result = model.predict(BatchRequest.model_validate(dict(request_id='corpus', segments=[payload])))['results'][0]
                counts[result['reason']] += 1
                if result['prediction'] is None:
                    complete = False
                    # 이후 edge는 이미 미확정 진입시간이므로 예측 합산하지 않는다. 커버리지 집계는 아래 정적 set으로 별도 산정.
                    break
                import math
                seconds = result['prediction']['mean_seconds']
                rows.append(dict(key=key, seconds=seconds))
                cursor += timedelta(seconds=math.ceil(seconds))
            paths.append(dict(case_id=step['caseId'], candidate_index=step['candidateIndex'],
                              step_sequence=step['stepSequence'], provider_name=alternative['providerName'],
                              complete=complete, estimated_seconds=(cursor - departure).total_seconds() if complete else None,
                              edges=rows))
    # 정적 커버리지는 실패 후에도 전체 edge를 검사하여 앞 edge 실패로 분모가 작아지지 않게 한다.
    all_keys = set()
    for step in mapping['results']:
        if step['mode'] != 'BUS':
            continue
        for alt in step['mapping'].get('busAlternatives', []):
            if alt['identityStatus'] != 'CONFIRMED' or alt['geometryStatus'] != 'VERIFIED' or len(alt['candidates']) != 1:
                continue
            c = alt['candidates'][0]
            if c['namespace'] != 'SEOUL_BUS':
                continue
            for a, b in zip(c['stops'], c['stops'][1:]):
                all_keys.add((c['routeId'], a['stopId'], b['stopId'], a['sequence']))
    supported = all_keys & model.edges.keys()
    report = dict(model=model.metadata(), mapping_sha256=hashlib.sha256(path.read_bytes()).hexdigest(),
                  mapping_date=mapping['asOf'], unique_seoul_edges=len(all_keys), supported_unique_edges=len(supported),
                  missing_edges=sorted(all_keys - model.edges.keys()), counts=dict(counts),
                  complete_alternatives=sum(p['complete'] for p in paths), seoul_alternatives=len(paths), paths=paths,
                  timing_scope='Each BUS alternative enters at departure; preceding walking/wait/subway excluded',
                  accuracy='NOT_MEASURED_NO_ACTUAL_JOURNEY_LABELS')
    sample = next((r for key, r in requests.items() if key in supported), None)
    if sample:
        latencies = []
        request = BatchRequest.model_validate(dict(request_id='latency', segments=[sample]))
        for _ in range(30):
            started = time.perf_counter()
            model.predict(request)
            latencies.append((time.perf_counter() - started) * 1000)
        import numpy as np
        report['single_edge_warm_ms'] = dict(p50=float(np.median(latencies)), p95=float(np.quantile(latencies, .95)))
    Path(args.output).write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps({k: v for k, v in report.items() if k not in ('paths', 'missing_edges')}, ensure_ascii=True))


if __name__ == '__main__':
    main()
