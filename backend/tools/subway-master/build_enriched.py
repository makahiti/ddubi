"""공식 운행정보의 명시적 역 목록/열차별 행으로 수도권 보강 마스터를 만든다.

카카오 경로는 마스터 생성에 사용하지 않는다. KRIC의 001 같은 값은 운영사에 따라
순번이므로 정적 역 ID로 사용하지 않는다. 공식 역사 파일의 호선·역명으로 역 코드를
찾고, 못 찾거나 둘 이상이면 해당 운행 패턴을 격리한다. 기존 1~9호선 ID는 유지한다.
"""

import collections, gzip, hashlib, json, pathlib, re, unicodedata, math, sys

P = pathlib.Path(sys.argv[1]).resolve()


def norm(s):
    s = unicodedata.normalize("NFKC", str(s or ""))
    s = re.sub(r"\([^)]*\)|\[[^]]*\]", "", s).upper()
    s = "".join(c for c in s if c.isalnum())
    if s.endswith("정류장"):
        s = s[:-3]
    return s[:-1] if s.endswith("역") else s


def read(name):
    return json.loads((P / name).read_text(encoding="utf-8"))


def save(name, x):
    (P / name).write_text(json.dumps(x, ensure_ascii=False, indent=2), encoding="utf-8")


# 공식 운행 노선 코드와 정적 역사 자료가 분할된 코드 범위를 명시한다.
CONFIG = {
    "I11D1": ("신분당선", ["I11D1"]),
    "S2801": ("인천1호선", ["S2801"]),
    "S2802": ("인천2호선", ["S2802"]),
    "L41E1": ("에버라인", ["L41E1"]),
    "I28A1": ("공항철도", ["I28A1"]),
    "I41K5": ("경강선", ["I41K5"]),
    "I4108": ("경의중앙선", ["I4108", "I4102"]),
    "I41K2": ("경춘선", ["I41K2", "I4108", "I4102"]),
    "I28K1": ("수인분당선", ["I28K1", "I4105", "I4102"]),
}

# 코레일 운행정보의 축약명만 해석한다. 카카오 입력에는 이 치환을 적용하지 않는다.
ALIASES = {
    "I41K5": dict(
        zip(
            ["신판교", "신이매", "경광주", "도예촌", "세종릉"],
            ["판교", "이매", "경기광주", "신둔도예촌", "세종대왕릉"],
        )
    ),
    "I28K1": dict(
        zip(
            [
                "대모산",
                "강남구",
                "로데오",
                "매탄권",
                "수원시",
                "신수원",
                "소래포",
                "인천논",
                "남동인",
                "신인천",
                "신길온",
            ],
            [
                "대모산입구",
                "강남구청",
                "압구정로데오",
                "매탄권선",
                "수원시청",
                "수원",
                "소래포구",
                "인천논현",
                "남동인더스파크",
                "인천",
                "신길온천",
            ],
        )
    ),
    "I4108": dict(
        zip(
            ["디엠시", "항공대", "1양원", "1양정", "효창공", "홍대입"],
            [
                "디지털미디어시티",
                "한국항공대",
                "양원",
                "양정",
                "효창공원앞",
                "홍대입구",
            ],
        )
    ),
    "I41K2": {"평내호": "평내호평"},
}

stations = read("kric-stations.json")[1:]
operations = read("kric-operations.json")[1:]
seoul = read("seoul-topology.json")
lookup = collections.defaultdict(list)
for r in stations:
    if not r[0] or not r[1] or not r[2]:
        continue
    lookup[(r[2], norm(r[1]))].append(r)

unknown = collections.Counter()


def station(code, name):
    """해당 서비스에 허용된 정적 호선에서 유일한 역만 고른다. 이름 유사도는 쓰지 않는다."""
    name = ALIASES.get(code, {}).get(name, name)
    result = {}
    for origin in CONFIG[code][1]:
        for r in lookup.get((origin, norm(name)), []):
            # 동일 코레일 역 코드가 여러 물리 노선에 중복 수록되면 서비스에 가까운
            # 첫 번째 코드 영역을 유지한다. 다른 역 코드는 끝까지 모호성으로 남긴다.
            result.setdefault(str(r[0]), r)
    if len(result) != 1:
        unknown[(code, name, "missing" if not result else "ambiguous")] += 1
        return None
    return next(iter(result.values()))


groups = collections.defaultdict(list)
patterns = {}
rejected = []


def add(code, names, evidence):
    resolved = [station(code, name) for name in names]
    if any(r is None for r in resolved):
        rejected.append(
            dict(
                code=code, reason="STATION_NOT_UNIQUE", names=names, sourceRows=evidence
            )
        )
        return
    ids = ["KRIC." + r[2] + "." + str(r[0]) for r in resolved]
    if len(ids) < 2 or any(a == b for a, b in zip(ids, ids[1:])):
        rejected.append(
            dict(code=code, reason="INVALID_PATH", names=names, sourceRows=evidence)
        )
        return
    direction = "ORDERED"
    key = (code, tuple(ids))
    if key in patterns:
        return
    patterns[key] = dict(
        subwayId="KRIC." + code,
        realtimeSubwayId=None,
        lineName=CONFIG[code][0],
        direction=direction,
        servicePatternId=hashlib.sha256((">".join(ids)).encode()).hexdigest(),
        stations=[
            dict(
                stationId=sid, realtimeStationId=None, stationName=r[1], sequence=i + 1
            )
            for i, (sid, r) in enumerate(zip(ids, resolved))
        ],
        sourceRows=evidence,
    )


for rownum, r in enumerate(operations, 2):
    if r[1] not in CONFIG or r[5] != "일반":
        continue
    route = str(r[7] or "")
    if re.match(r"^[A-Za-z0-9]+-", route):
        # 구분자 + 또는 쉼표 뒤의 코드-역명 형식만 경계로 인정한다.
        names = [
            re.sub(r"^[A-Za-z0-9]+-", "", v).strip()
            for v in re.split(r"[+,](?=[A-Za-z0-9]+-)", route)
        ]
        add(r[1], names, [rownum])
    else:
        key = (r[1], str(r[0]), r[6], r[3], r[4], str(r[12]))
        groups[key].append((rownum, r))
for key, rows in groups.items():
    # 원본은 한 열차의 순차 정차 행이다. 시각이 감소하면 자정 통과인지 검사한다.
    times = []
    bad = False
    previous = None
    rollover = 0
    for n, r in rows:
        raw = str(r[8] or r[9] or "")
        m = re.fullmatch(r"(\d{1,2}):(\d{2})(?::(\d{2}))?", raw)
        if not m:
            bad = True
            break
        seconds = int(m[1]) * 3600 + int(m[2]) * 60 + int(m[3] or 0)
        if previous is not None and seconds + rollover < previous:
            if previous % 86400 >= 23 * 3600 and seconds < 3 * 3600:
                rollover += 86400
            else:
                bad = True
                break
        previous = seconds + rollover
        times.append(previous)
    if bad:
        rejected.append(
            dict(
                code=key[0],
                reason="INVALID_TIME_ORDER",
                sourceRows=[n for n, r in rows],
            )
        )
        continue
    add(key[0], [r[7] for n, r in rows], [n for n, r in rows])

# 방향은 ID 크기로 추정하지 않는다. 실제 경로의 공유 인접 edge 방향을 전파한다.
# 서로 겹치지 않는 연결 성분은 별도 토큰이며, 공식 상/하행 코드라는 의미가 아니다.
for code in CONFIG:
    pending = sorted(
        [p for (c, ids), p in patterns.items() if c == code],
        key=lambda p: (-len(p["stations"]), p["servicePatternId"]),
    )
    edge_sign = {}
    component = 0
    while pending:
        progressed = False
        for p in list(pending):
            ids = [s["stationId"] for s in p["stations"]]
            edges = list(zip(ids, ids[1:]))
            signs = {edge_sign[e] for e in edges if e in edge_sign}
            if not signs and edge_sign:
                continue
            if not signs:
                component += 1
                signs = {component}
            if len(signs) > 1:
                rejected.append(
                    dict(
                        code=code,
                        reason="DIRECTION_CONFLICT",
                        sourceRows=p["sourceRows"],
                    )
                )
                del patterns[(code, tuple(ids))]
                pending.remove(p)
                progressed = True
                continue
            sign = next(iter(signs))
            p["direction"] = "C" + str(abs(sign)) + ("F" if sign > 0 else "R")
            for a, b in edges:
                edge_sign[(a, b)] = sign
                edge_sign[(b, a)] = -sign
            pending.remove(p)
            progressed = True
        if not progressed:
            # 단절된 공식 경로를 연결하지 않고 새 성분으로 시작한다.
            p = pending[0]
            component += 1
            a, b = [s["stationId"] for s in p["stations"][:2]]
            edge_sign[(a, b)] = component
            edge_sign[(b, a)] = -component

# GTX-A 운영사 화면은 북부/남부를 별도 운행 카드로 공개한다. 역 코드와 순서를
# 해당 카드에서 읽고, 두 카드를 잇는 삼성/서울~수서 edge는 만들지 않는다.
html = (P / "raw/gtxa-main.html").read_text(encoding="utf-8")
for section in re.findall(r'<div class="station-card".*?(?=<!--// .*?-->)', html, re.S):
    codes = re.findall(
        r'<span class="station-value">([^<]+)</span>\s*<span class="station-text">([^<]+)</span>',
        section,
    )
    for direction, ordered in [("F", codes), ("R", list(reversed(codes)))]:
        ids = ["GTXA." + code for code, name in ordered]
        if len(ids) < 2:
            raise ValueError("GTX card schema changed")
        patterns[("GTXA", tuple(ids))] = dict(
            subwayId="GTXA.A",
            realtimeSubwayId=None,
            lineName="GTX-A",
            direction=direction,
            servicePatternId=hashlib.sha256(">".join(ids).encode()).hexdigest(),
            stations=[
                dict(
                    stationId=sid,
                    realtimeStationId=None,
                    stationName=name,
                    sequence=i + 1,
                )
                for i, (sid, (code, name)) in enumerate(zip(ids, ordered))
            ],
            sourceRows=["https://www.gtx-a.com/main.do"],
        )

names = {r["stationId"]: r["stationName"] for r in seoul["stations"]}
paths = collections.defaultdict(list)
for r in seoul["lineStations"]:
    paths[(r["subwayId"], r["direction"], r["patternId"])].append(
        dict(
            stationId=r["stationId"],
            realtimeStationId=None,
            stationName=names[r["stationId"]],
            sequence=r["sequence"],
        )
    )
base = [
    dict(
        subwayId=r["subwayId"],
        realtimeSubwayId=None,
        lineName=r["lineName"],
        direction=r["direction"],
        servicePatternId=r["patternId"],
        stations=paths[(r["subwayId"], r["direction"], r["patternId"])],
    )
    for r in seoul["lines"]
]
enriched = [
    {k: v for k, v in r.items() if k != "sourceRows"} for r in patterns.values()
]
save("enriched-lines.json", base + enriched)
save(
    "enriched-aliases.json",
    dict(
        busRouteAliasIds={},
        busStopAliasIds={},
        subwayLineAliasIds={},
        subwayStationAliasIds={"서해구청": ["KRIC.S2802.3210"]},
    ),
)
save("enrichment-rejections.json", rejected)
save("enrichment-pattern-evidence.json", list(patterns.values()))
save(
    "unknown-operation-stations.json",
    [
        dict(line=k[0], name=k[1], reason=k[2], count=v)
        for k, v in sorted(unknown.items())
    ],
)
print("patterns", len(base), len(enriched), "rejected", len(rejected))
print("by line", collections.Counter(r["lineName"] for r in enriched))
print("unknown", unknown)
