"""참조 갱신이 가짜 관측/잘못된 날짜 준비 상태를 만들지 않는지 검증한다."""
from datetime import date
from pathlib import Path
import sys
from types import SimpleNamespace
import pandas as pd
from fastapi.testclient import TestClient
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from app import create_app
from tools.extend_reference_bundle import normalize, deduplicate, required_dates


def test_zero_travel_is_missing_but_zero_passengers_is_observation():
    raw={'기준_날짜':'20260713','노선_ID':'001','출발_정류장_ID':'002','도착_정류장_ID':'003',
         '출발_정류장_순서':'7','정류장_순서':'7'}
    raw.update({f'운행시간_{h:02d}시':'0' for h in range(24)})
    raw.update({f'재차인원합_{h}시':'0' for h in range(24)})
    bus,bad,keys=normalize(pd.DataFrame([raw],dtype='string'),'OA-21217')
    passenger,_,_=normalize(pd.DataFrame([raw],dtype='string'),'OA-21218')
    assert bad.empty and bus.iloc[0].provider_route_id=='001'
    assert pd.isna(bus.iloc[0].h00) and passenger.iloc[0].h00==0
    changed=bus.copy();changed['h00']=30
    clean,conflict=deduplicate(pd.concat([bus,bus,changed]),keys)
    assert clean.empty and len(conflict)==2


def test_lags_stay_training_compatible():
    days=required_dates(date(2026,10,10),date(2026,10,10))
    assert date(2026,7,25) in days['OA-21217']
    assert date(2026,10,9) not in days['OA-21217']
    assert len(days['OA-21217'])==8 and len(days['OA-21218'])==4


def test_expired_and_future_bundle_are_not_ready_and_missing_files_are_visible():
    model=SimpleNamespace(bundle={'missing_files':['bus_daily/example.parquet']},metadata=lambda:{
        'supported_from':'2026-09-10','supported_until':'2026-09-30','production_ready':False})
    for day,expected,state in [(date(2026,9,9),503,'NOT_STARTED'),(date(2026,9,30),200,'CURRENT'),(date(2026,10,1),503,'EXPIRED')]:
        with TestClient(create_app(predictor=model,today=lambda:day)) as client:
            response=client.get('/health/ready')
            assert response.status_code==expected
            assert response.json()['reference_status']==state
            assert response.json()['missing_reference_file_count']==1
            assert client.get('/health/live').status_code==200
