"""GMS가 제안한 값에서 원문으로 확인할 수 있는 값만 남기는지 검증한다."""

from common.traffic_incident_ai import IncidentAiExtractor, ground_extraction, validate_extraction
from common.traffic_incident_enrichment import VerifiedLinkMaster, normalize_incident


def test_lane_number_is_not_closed_lane_count():
    text = "장승배기로 1차로 고장차량"
    proposed = {
        "incidentKind": "BREAKDOWN",
        "incidentDetail": "고장차량",
        "controlScope": "PARTIAL",
        "closedLaneCount": 1,
        "laneNumbers": [1],
        "directionText": None,
        "busDetourMentioned": False,
        "statusMention": "UNKNOWN",
        "timeExpressions": [],
        "evidence": {},
    }
    grounded, flags = ground_extraction(proposed, text)
    validate_extraction(grounded, text)
    assert grounded["closedLaneCount"] is None
    assert grounded["laneNumbers"] == [1]
    assert grounded["controlScope"] == "UNKNOWN"
    assert "UNSUPPORTED_closedLaneCount" in flags


def test_gms_result_is_cached_across_batches(tmp_path):
    import json

    calls = []

    class Response:
        status_code = 200

        def json(self):
            return {
                "choices": [
                    {
                        "message": {
                            "content": json.dumps(
                                {
                                    "incidentKind": "ACCIDENT",
                                    "incidentDetail": "추돌사고",
                                    "controlScope": "PARTIAL",
                                    "closedLaneCount": 2,
                                    "laneNumbers": [2],
                                    "directionText": None,
                                    "busDetourMentioned": False,
                                    "statusMention": "UNKNOWN",
                                    "timeExpressions": [],
                                    "evidence": {"description": "wrong model evidence"},
                                }
                            )
                        }
                    }
                ]
            }

    def post(*args, **kwargs):
        calls.append((args, kwargs))
        return Response()

    row = {
        "source": "seoul.traffic_incident",
        "providerIncidentId": "123",
        "description": "동부간선로 2차로 추돌사고로 부분 통제",
    }
    extractor = IncidentAiExtractor("test-key", tmp_path / "cache.sqlite3", post=post)
    first = extractor.enrich_many([row])[0]
    second = extractor.enrich_many([row])[0]
    assert len(calls) == 1
    assert first == second
    assert first["aiExtractionStatus"] == "VALID"
    assert first["aiClosedLaneCount"] is None
    assert first["aiLaneNumbers"] == [2]
    assert first["aiControlScope"] == "PARTIAL"


def test_live_four_digit_occurrence_time_is_parsed():
    row = normalize_incident(
        {
            "eventId": "poll",
            "source": "seoul.traffic_incident",
            "collectedAt": "2026-09-22T10:00:00+09:00",
            "payload": {
                "raw": {
                    "incidentId": "1",
                    "occurredDate": "20260922",
                    "occurredTime": "0930",
                    "description": "2차로 추돌사고",
                }
            },
        },
        VerifiedLinkMaster.unavailable(),
    )
    assert row["startedAt"] == "2026-09-22T09:30:00+09:00"


def test_expected_completion_is_not_marked_completed():
    proposed = {
        "incidentKind": "BREAKDOWN",
        "incidentDetail": "차량고장",
        "controlScope": "UNKNOWN",
        "closedLaneCount": None,
        "laneNumbers": [],
        "directionText": None,
        "busDetourMentioned": False,
        "statusMention": "COMPLETED",
        "timeExpressions": [],
        "evidence": {},
    }
    grounded, _ = ground_extraction(proposed, "차량고장 처리 중. 11시 완료 예정")
    validate_extraction(grounded, "차량고장 처리 중. 11시 완료 예정")
    assert grounded["statusMention"] == "UNKNOWN"
