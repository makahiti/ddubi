#!/usr/bin/env python3
"""Read-only recommendation evidence gate. Never interprets health as readiness.

--evaluation: saved evaluation JSON (not credentials); emits only aggregate facts.
Without it, verifies the running backend timetable header but reports NOT_VERIFIED.
The backend remains the authority for complete timetable/mapping validation.
"""
import argparse
import datetime as dt
import json
import subprocess
import sys


def instant(value):
    parsed = dt.datetime.fromisoformat(value.replace('Z', '+00:00'))
    if parsed.tzinfo is None:
        raise ValueError('timezone required')
    return parsed


def evaluate(body, now):
    data = body.get('data', body)
    quality = data.get('quality') or {}
    failures = []
    if data.get('schemaVersion') != 'schedule-evaluation-v2':
        failures.append('SCHEMA_UNVERIFIED')
    if data.get('state') != 'READY':
        failures.append('EVALUATION_NOT_READY')
    if data.get('decision') not in {'KEEP_DEPARTURE', 'ADVANCE_DEPARTURE',
                                    'DEPART_NOW', 'MARGIN_UNAVAILABLE', 'LATE_EXPECTED'}:
        failures.append('DECISION_NOT_CONFIRMED')
    if quality.get('preview') is not False or quality.get('productionEligible') is not True:
        failures.append('NOT_PRODUCTION_ELIGIBLE')
    if quality.get('costCoverage') != 'COMPLETE':
        failures.append('COST_INCOMPLETE')
    if (data.get('search') or {}).get('complete') is False:
        failures.append('SEARCH_INCOMPLETE')
    for name in ('estimatedArrivalAt', 'computedAt', 'validUntil'):
        try:
            value = instant(data[name])
            if name == 'validUntil' and value <= now:
                failures.append('EVIDENCE_EXPIRED')
            if name == 'computedAt' and value > now:
                failures.append('COMPUTED_IN_FUTURE')
        except (KeyError, TypeError, ValueError, AttributeError):
            failures.append(name.upper() + '_INVALID')
    if any(leg.get('role') in ('RIDE', 'WAIT') and
           leg.get('durationSource') in (None, 'UNKNOWN', 'PROVIDER_TOTAL_FALLBACK')
           for leg in data.get('legs', [])):
        failures.append('TRANSIT_FALLBACK')
    return {'evaluationReady': not failures, 'failures': failures,
            'realtimeApplied': quality.get('realtimeApplied') is True,
            'missingCount': len(quality.get('missingInputs') or []),
            'fallbackCount': len(quality.get('fallbackReasons') or [])}


def timetable_header(data, now):
    for key in ('version', 'masterDataVersion', 'source'):
        if not isinstance(data.get(key), str) or not data[key].strip():
            raise ValueError('missing metadata')
    if not instant(data['validFrom']) <= now < instant(data['validUntil']):
        raise ValueError('outside validity')
    if instant(data['publishedAt']) > now:
        raise ValueError('future publication')
    if not isinstance(data.get('trips'), list) or not data['trips']:
        raise ValueError('empty trips')
    return {'version': data['version'], 'tripCount': len(data['trips']),
            'validUntil': data['validUntil'], 'headerValid': True}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--evaluation', help='fresh API evaluation response JSON file')
    args = parser.parse_args()
    now = dt.datetime.now(dt.timezone.utc)
    try:
        if args.evaluation:
            with open(args.evaluation, encoding='utf-8-sig') as source:
                result = evaluate(json.load(source), now)
            print(json.dumps(result, ensure_ascii=False))
            return 0 if result['evaluationReady'] else 2
        # Fixed command; do not dump docker inspect or the backend environment.
        result = subprocess.run(['docker', 'exec', 'backend', 'sh', '-c',
            'test -n "$DDUBI_SUBWAY_TIMING_TIMETABLE_PATH" && '
            'test -r "$DDUBI_SUBWAY_TIMING_TIMETABLE_PATH" && '
            'head -c 33554433 "$DDUBI_SUBWAY_TIMING_TIMETABLE_PATH"'],
            capture_output=True, timeout=15, check=True)
        if len(result.stdout) > 32 * 1024 * 1024:
            raise ValueError('artifact too large')
        report = timetable_header(json.loads(result.stdout), now)
        report['recommendationStatus'] = 'NOT_VERIFIED_NEEDS_ACTUAL_EVALUATION'
        print(json.dumps(report, ensure_ascii=False))
        return 2
    except (OSError, ValueError, KeyError, TypeError, AttributeError,
            subprocess.SubprocessError):
        # Do not include raw payload, environment, command output or secrets.
        print(json.dumps({'recommendationStatus': 'NOT_VERIFIED',
                          'reason': 'MISSING_INVALID_OR_UNAVAILABLE_EVIDENCE'}))
        return 2


if __name__ == '__main__':
    sys.exit(main())
