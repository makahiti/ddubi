"""정적 topology에 좌표 인덱스와 별도 실시간 crosswalk를 결합한다.

공식 같은 호선·정규화 역명이 하나일 때만 실시간 ID를 부여한다. 실시간 자료가
없는 호선은 null을 유지한다. 좌표는 비교 검증용 별도 파일이며 ID 생성에 쓰지 않는다.
입력 원본과 생성 결과의 SHA-256을 기록하며 압축 시각을 고정해 재현성을 보장한다.
"""

import collections, gzip, hashlib, json, pathlib, re, unicodedata, openpyxl, sys

P = pathlib.Path(sys.argv[1]).resolve()


def read(name):
    return json.loads((P / name).read_text(encoding="utf-8"))


def norm(s):
    s = re.sub(
        r"\([^)]*\)|\[[^]]*\]", "", unicodedata.normalize("NFKC", str(s or ""))
    ).upper()
    s = "".join(c for c in s if c.isalnum())
    return s[:-1] if s.endswith("역") else s


def save(name, obj):
    (P / name).write_text(
        json.dumps(obj, ensure_ascii=False, indent=2), encoding="utf-8"
    )


lines = read("enriched-lines.json")
aliases = read("enriched-aliases.json")
livepath = pathlib.Path(sys.argv[2]).resolve()
book = openpyxl.load_workbook(livepath, read_only=True, data_only=True)
live = collections.defaultdict(set)
for row in list(book["Data"].values)[1:]:
    if row[0] and row[1]:
        live[(norm(row[3]), norm(row[2]))].add((str(row[0]), str(row[1])))
book.close()
kric = {
    ("KRIC." + str(r[2]) + "." + str(r[0])): r
    for r in read("kric-stations.json")[1:]
    if r[0] and r[2]
}
topis = read("seoul-stations-normalized.json")
if isinstance(topis, dict):
    topis = topis["list"]
byid = {str(r["BLDN_ID"]): r for r in topis}
coords = {}
crosswalk = []
missing = []
for line in lines:
    for s in line["stations"]:
        name = norm(s["stationName"])
        lname = norm(line["lineName"])
        # 공식 실시간 파일은 7호선 이수를 총신대입구(이수)로 수록한다.
        # 호선과 정적 시간표 ID 2738을 함께 제한하여 4호선 역으로 합쳐지는 것을 방지한다.
        if lname == "7호선" and name == "이수" and s["stationId"] == "2738":
            name = norm("총신대입구")
        matches = live.get((lname, name), set())
        if len(matches) == 1:
            lid, sid = next(iter(matches))
            s["realtimeStationId"] = sid
            if line["realtimeSubwayId"] not in (None, lid):
                raise ValueError("live line conflict")
            line["realtimeSubwayId"] = lid
            crosswalk.append(
                dict(
                    lineId=line["subwayId"],
                    stationId=s["stationId"],
                    realtimeSubwayId=lid,
                    realtimeStationId=sid,
                )
            )
        else:
            missing.append(
                dict(
                    line=line["lineName"],
                    station=s["stationName"],
                    stationId=s["stationId"],
                    reason="MISSING" if not matches else "AMBIGUOUS",
                )
            )
        static = s["stationId"]
        point = None
        if static in kric:
            r = kric[static]
            try:
                point = dict(
                    latitude=float(r[9]),
                    longitude=float(r[10]),
                    source="KRIC-32",
                    sourceDate=str(r[14]),
                )
            except (ValueError, TypeError):
                pass
        elif static in byid:
            r = byid[static]
            if norm(r["BLDN_NM"]) == norm(s["stationName"]):
                point = dict(
                    latitude=float(r["LAT"]),
                    longitude=float(r["LOT"]),
                    source="SEOUL-OA-21232",
                )
        elif static.startswith("GTXA."):
            matches = [
                r
                for r in topis
                if r["ROUTE"] == "수도권 광역급행철도"
                and norm(r["BLDN_NM"]) == norm(s["stationName"])
            ]
            if s["stationName"] == "운정중앙":
                matches = [
                    r
                    for r in topis
                    if r["ROUTE"] == "수도권 광역급행철도" and r["BLDN_NM"] == "운정"
                ]
            if len(matches) == 1:
                r = matches[0]
                point = dict(
                    latitude=float(r["LAT"]),
                    longitude=float(r["LOT"]),
                    source="SEOUL-OA-21232",
                    sourceStationId=r["BLDN_ID"],
                )
        # 검토된 출처 충돌만 고친다. 양원 KRIC 행은 서울 주소에 경북 좌표가 들어 있고,
        # 미사 TOPIS 점은 공식 KRIC 역사 좌표와 약 256m 다르다. 원래 ID는 바꾸지 않는다.
        if static == "KRIC.I4108.1204":
            r = byid["1204"]
            point = dict(
                latitude=float(r["LAT"]),
                longitude=float(r["LOT"]),
                source="SEOUL-OA-21232",
                supersedes="KRIC-32 wrong-region coordinate",
                originalLatitude=kric[static][9],
                originalLongitude=kric[static][10],
            )
        if static == "2563":
            r = kric["KRIC.S1105.0555"]
            point = dict(
                latitude=float(r[9]),
                longitude=float(r[10]),
                source="KRIC-32",
                sourceStationId="0555",
                supersedes="SEOUL-OA-21232 coordinate discrepancy",
            )
        if point and 33 <= point["latitude"] <= 39 and 124 <= point["longitude"] <= 132:
            coords[static] = point
        else:
            missing.append(
                dict(
                    line=line["lineName"],
                    station=s["stationName"],
                    stationId=static,
                    reason="COORDINATE_MISSING",
                )
            )
payload = dict(schemaVersion=1, lines=lines, aliases=aliases)
canonical = json.dumps(
    payload, ensure_ascii=False, sort_keys=True, separators=(",", ":")
).encode()
payload["dataVersion"] = "metro-20260910-" + hashlib.sha256(canonical).hexdigest()[:16]
encoded = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode()
(P / "metropolitan-subway-master.json.gz").write_bytes(gzip.compress(encoded, mtime=0))
save("enriched-lines.json", lines)
save("station-coordinates.json", coords)
save(
    "crosswalk.json",
    list({(r["lineId"], r["stationId"]): r for r in crosswalk}.values()),
)
save(
    "master-gaps.json",
    list({json.dumps(r, sort_keys=True): r for r in missing}.values()),
)
sources = []
for filename in [
    "seoul-timetable-20260616.csv",
    "seoul-stations.json",
    "kric-stations-20260630.xlsx",
    "kric-operations-20260630.xlsx",
    "gtxa-main.html",
    "incheon-station-210.html",
]:
    path = P / "raw" / filename
    meta = json.loads(
        path.with_suffix(path.suffix + ".source.json").read_text(encoding="utf-8")
    )
    sources.append(dict(file=filename, **meta))
sources.append(
    dict(
        file=livepath.name,
        url="https://data.seoul.go.kr/dataList/OA-12764/A/1/datasetView.do",
        sha256=hashlib.sha256(livepath.read_bytes()).hexdigest(),
    )
)
save(
    "source-manifest.json",
    dict(
        dataVersion=payload["dataVersion"],
        sources=sources,
        artifactSha256=hashlib.sha256(
            (P / "metropolitan-subway-master.json.gz").read_bytes()
        ).hexdigest(),
    ),
)
print(
    "version",
    payload["dataVersion"],
    "patterns",
    len(lines),
    "coordinates",
    len(coords),
    "crosswalk",
    len({(r["lineId"], r["stationId"]) for r in crosswalk}),
)
