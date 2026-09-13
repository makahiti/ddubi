"""서빙 자원 정책 변경을 실제 묶음으로 검증한다. 학습·파일 갱신·외부 API 호출은 없다.

캐시 크기별 새 프로세스로 실행해 시작/첫 요청/반복 요청 시간과 프로세스 최대 RSS를
분리한다. golden의 예측·결측을 비교하고 오차가 있으면 실패한다.
작은 표본의 이식·부하 점검이며 실제 이용자 분포의 정확도나 운영 SLA가 아니다.
"""
import argparse
import asyncio
import ctypes
import hashlib
import json
from pathlib import Path
import platform
import sys
from time import perf_counter

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from predictor import Predictor
from schemas import BatchRequest


def peak_rss_mib():
    """운영체제 프로세스 최대 working set/RSS를 MiB로 반환한다. 캐시 크기와 구분한다."""
    if sys.platform == 'win32':
        from ctypes import wintypes

        class Counters(ctypes.Structure):
            _fields_ = [('cb', wintypes.DWORD), ('PageFaultCount', wintypes.DWORD)] + [
                (name, ctypes.c_size_t) for name in ['PeakWorkingSetSize', 'WorkingSetSize',
                'QuotaPeakPagedPoolUsage', 'QuotaPagedPoolUsage', 'QuotaPeakNonPagedPoolUsage',
                'QuotaNonPagedPoolUsage', 'PagefileUsage', 'PeakPagefileUsage']]

        kernel = ctypes.WinDLL('kernel32', use_last_error=True)
        psapi = ctypes.WinDLL('psapi', use_last_error=True)
        kernel.GetCurrentProcess.restype = wintypes.HANDLE
        psapi.GetProcessMemoryInfo.argtypes = [wintypes.HANDLE, ctypes.POINTER(Counters), wintypes.DWORD]
        counters = Counters()
        counters.cb = ctypes.sizeof(counters)
        if not psapi.GetProcessMemoryInfo(kernel.GetCurrentProcess(), ctypes.byref(counters), counters.cb):
            raise ctypes.WinError(ctypes.get_last_error())
        return counters.PeakWorkingSetSize / 1024 ** 2
    import resource
    scale = 1024 ** 2 if sys.platform == 'darwin' else 1024
    return resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / scale


def benchmark(args):
    """동일 요청을 재사용하되 결과 캐시 없이 매번 실제 추론한다. 원본 ID는 보고서에 담지 않는다."""
    golden = json.loads(args.golden.read_text(encoding='utf-8'))
    size = args.batch_size or len(golden['requests'])
    if not 1 <= size <= 1000 or args.iterations < 2:
        raise ValueError('batch 1..1000, iterations >= 2 required')
    request = BatchRequest.model_validate({'request_id': 'resource-check', 'segments': [
        {'segment_key': str(i), **golden['requests'][i % len(golden['requests'])]} for i in range(size)]})
    start = perf_counter()
    predictor = Predictor(args.bundle, cache_mib=args.cache_mib)
    if args.original_reader:
        # 비교 기준은 변경 전처럼 디스크를 읽는 원본 portable 함수다. 검증 묶음은 수정하지 않는다.
        import pandas
        import pyarrow.parquet
        predictor.features.pd, predictor.features.pq = pandas, pyarrow.parquet
    startup = perf_counter() - start
    timings = []
    digest = None
    max_error = 0.0
    for _ in range(args.iterations):
        start = perf_counter()
        response = predictor.predict(request)
        timings.append(perf_counter() - start)
        assert response['production_ready'] is False
        for i, row in enumerate(response['results']):
            j = i % len(golden['requests'])
            assert row['segment_key'] == str(i)
            if golden['unseen_id_counts'][j]:
                assert row['reason'] == 'UNSEEN_PROVIDER_ID'
                continue
            prediction = row['prediction']
            error = abs(prediction['mean_seconds'] - golden['predictions'][j])
            max_error = max(max_error, error)
            assert error <= 1e-5, 'golden prediction mismatch'
            assert prediction['missing_feature_count'] == golden['missing_feature_counts'][j]
        current_digest = hashlib.sha256(json.dumps(response['results'], sort_keys=True).encode()).hexdigest()
        assert digest is None or digest == current_digest, 'repeated prediction changed'
        digest = current_digest
    http_results = []
    if args.http_concurrency:
        # 선택적 ASGI 검증은 TCP/프록시를 제외하고 실제 앱·대기열·모델까지 통과한다.
        import httpx
        from app import create_app

        async def http_check():
            app = create_app(predictor)
            async with app.router.lifespan_context(app):
                async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url='http://test') as client:
                    async def send():
                        start = perf_counter()
                        response = await client.post('/v1/bus/predictions:batch', json=request.model_dump(mode='json'))
                        elapsed = perf_counter() - start
                        if response.status_code == 200:
                            actual = hashlib.sha256(json.dumps(response.json()['results'], sort_keys=True).encode()).hexdigest()
                            assert actual == digest, 'HTTP prediction changed'
                        else:
                            assert response.status_code == 503 and response.json() == {'detail': 'MODEL_BUSY'}
                        return {'status': response.status_code, 'seconds': elapsed}
                    return await asyncio.gather(*(send() for _ in range(args.http_concurrency)))
        http_results = asyncio.run(http_check())
    result = {'platform': platform.platform(), 'python': platform.python_version(),
              'model_version': predictor.manifest['version'], 'batch_size': size,
              'iterations': args.iterations, 'original_reader': args.original_reader,
              'startup_seconds': startup, 'request_seconds': timings,
              'peak_rss_mib': peak_rss_mib(), 'max_golden_error_seconds': max_error,
              'result_sha256': digest, 'cache': predictor.reference_cache.stats(), 'http_requests': http_results}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(result, ensure_ascii=False))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bundle', type=Path, required=True)
    parser.add_argument('--golden', type=Path, default=Path(__file__).resolve().parents[1] / 'examples/v12-server-golden.json')
    parser.add_argument('--cache-mib', type=int, default=256)
    parser.add_argument('--iterations', type=int, default=4)
    parser.add_argument('--batch-size', type=int)
    parser.add_argument('--original-reader', action='store_true')
    parser.add_argument('--http-concurrency', type=int, choices=range(0, 9), default=0)
    parser.add_argument('--output', type=Path, required=True)
    benchmark(parser.parse_args())
