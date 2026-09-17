"""v13 파일 추가만으로 기존 앱의 초기화·공개 경로가 바뀌는 회귀를 방어한다.

서버 설정과 모델이 없는 신규 체크아웃에서도 프로세스는 기동해야 한다.
모델 미설치 readiness 503은 기존 동작이며 v13을 자동 연결해 숨기지 않는다.
"""
from pathlib import Path
import subprocess
import sys


def test_default_app_starts_without_static_bundle_or_v2_routes():
    """별도 프로세스에서 앱만 import하여 다른 테스트의 v13 import 영향을 제거한다."""
    script = '''
import os, sys
for name in list(os.environ):
    if name.startswith('ML_'):
        os.environ.pop(name)
from fastapi.testclient import TestClient
from app import create_app
app = create_app()
assert not any(name.startswith('static_bus_') for name in sys.modules)
with TestClient(app) as client:
    assert client.get('/health/live').status_code == 200
    assert client.get('/health/ready').status_code == 503
    assert client.get('/v2/bus/health').status_code == 404
    assert set(client.get('/openapi.json').json()['paths']) == {
        '/health/live', '/health/ready', '/v1/bus/predictions:batch',
        '/v1/bus/environment:prepare', '/v1/bus/environment:predict'}
assert not any(name.startswith('static_bus_') for name in sys.modules)
'''
    result = subprocess.run([sys.executable, '-c', script],
                            cwd=Path(__file__).resolve().parents[1],
                            capture_output=True, text=True, timeout=30)
    assert result.returncode == 0, result.stderr
