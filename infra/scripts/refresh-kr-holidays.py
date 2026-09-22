#!/usr/bin/env python3
"""공휴일 목록(infra/config/kr-holidays.json)을 한국천문연구원 특일 정보로 새로 만든다.

    export KASI_HOLIDAY_API_KEY='...'        # 공공데이터포털에서 발급
    python3 infra/scripts/refresh-kr-holidays.py 2027

왜 따로 두나.
    매일 도는 시각표 작업은 이 파일을 읽기만 한다. 그 작업이 외부 API 를 부르면, API 가
    잠깐 죽은 날 시각표가 통째로 안 만들어진다. 공휴일은 1년에 몇 번 바뀌지 않으므로
    필요할 때만 사람이 이 스크립트를 돌려 파일을 갱신한다.

무엇을 하나.
    지정한 연도의 공휴일을 12개월치 받아 기존 파일과 합친다. 다른 연도의 값은 건드리지
    않는다. 받은 값이 한 건도 없으면 파일을 쓰지 않는다 - 빈 목록으로 덮으면 모든 공휴일이
    평일로 바뀌어 없는 열차로 계산하게 된다.

주의.
    API 키는 이 저장소에 넣지 않는다. 환경변수로만 준다.
"""

from __future__ import annotations

import json
import os
import sys
import urllib.parse
import urllib.request
from pathlib import Path
from xml.etree import ElementTree

API = 'https://apis.data.go.kr/B090041/openapi/service/SpcdeInfoService/getRestDeInfo'
CONFIG = Path(__file__).resolve().parents[1] / 'config' / 'kr-holidays.json'


def fetch_month(key: str, year: int, month: int) -> dict[str, str]:
    """한 달치 공휴일. {'2026-10-03': '개천절'} 모양."""
    query = urllib.parse.urlencode(
        {
            'serviceKey': key,
            'solYear': year,
            'solMonth': f'{month:02d}',
            'numOfRows': 50,
        },
        safe='%',
    )
    with urllib.request.urlopen(f'{API}?{query}', timeout=20) as response:
        body = response.read()

    found: dict[str, str] = {}
    for item in ElementTree.fromstring(body).iter('item'):
        locdate = (item.findtext('locdate') or '').strip()
        name = (item.findtext('dateName') or '').strip()
        is_holiday = (item.findtext('isHoliday') or 'Y').strip()
        # isHoliday 가 N 이면 '공공기관 휴무일이 아닌 기념일' 이다. 지하철은 평일로 다닌다.
        if len(locdate) == 8 and is_holiday == 'Y':
            found[f'{locdate[:4]}-{locdate[4:6]}-{locdate[6:]}'] = name or '공휴일'
    return found


def main() -> int:
    key = os.environ.get('KASI_HOLIDAY_API_KEY', '').strip()
    if not key:
        print('KASI_HOLIDAY_API_KEY 가 없다. 공공데이터포털에서 발급해 환경변수로 준다.')
        return 2

    year = int(sys.argv[1]) if len(sys.argv) > 1 else None
    if year is None:
        print('사용법: refresh-kr-holidays.py <연도>')
        return 2

    fetched: dict[str, str] = {}
    for month in range(1, 13):
        try:
            fetched.update(fetch_month(key, year, month))
        except Exception as error:  # noqa: BLE001 - 어느 달이 실패했는지만 알리고 계속한다
            print(f'{year}-{month:02d} 조회 실패: {error}')

    if not fetched:
        print(f'{year}년 공휴일을 한 건도 받지 못했다. 파일을 그대로 둔다.')
        return 1

    document = json.loads(CONFIG.read_text(encoding='utf-8'))
    holidays: dict[str, str] = document['holidays']
    # 같은 해의 옛 값만 걷어내고 새로 받은 것으로 바꾼다. 다른 해는 건드리지 않는다.
    kept = {date: name for date, name in holidays.items() if not date.startswith(f'{year}-')}
    kept.update(fetched)

    document['holidays'] = dict(sorted(kept.items()))
    document['updatedAt'] = __import__('datetime').date.today().isoformat()
    document['source'] = '한국천문연구원 특일 정보 (getRestDeInfo)'
    CONFIG.write_text(
        json.dumps(document, ensure_ascii=False, indent=2) + '\n', encoding='utf-8'
    )
    print(f'{year}년 공휴일 {len(fetched)}건 반영. 전체 {len(document["holidays"])}건.')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
