"""Verify every exported transit reference ID, shard boundary, flag and parent reference."""
import argparse
import collections
import importlib.util
import json
from pathlib import Path


spec=importlib.util.spec_from_file_location('transit_export',Path(__file__).with_name('export-transit-reference.py'))
export=importlib.util.module_from_spec(spec);spec.loader.exec_module(export)


def verify(root):
    complete=export.verified(root)
    result=dict(productionReady=False,sourceVersion=complete['summary']['sourceVersion'],packages={})
    for name in ('subway','road'):
        package=root/name;manifest_path=package/'manifest.json';manifest=json.loads(manifest_path.read_text(encoding='utf-8'))
        if manifest['kind']!=name.upper() or manifest.get('productionReady') is not False or manifest.get('realtimeApplied') is not False or manifest.get('referenceOnly') is not True:raise ValueError('MANIFEST_REFERENCE_FLAGS_INVALID')
        if manifest_path.stat().st_size>1024*1024 or manifest['entriesIndex']['algorithm']!='SHA256_PREFIX_2':raise ValueError('MANIFEST_BOUNDARY_INVALID')
        ids=set();parents=set();counts=collections.Counter();examples={};max_size=0
        for bucket,shard in manifest['entriesIndex']['shards'].items():
            path=export.safe_relative(package,shard['path']);size=path.stat().st_size
            if size!=shard['sizeBytes'] or size>export.MAX_SHARD_BYTES or export.digest(path)!=shard['sha256']:raise ValueError('SHARD_INTEGRITY_INVALID')
            max_size=max(max_size,size);data=json.loads(path.read_text(encoding='utf-8'))
            if data['schemaVersion']!=1 or data['kind']!=manifest['kind']:raise ValueError('SHARD_KIND_INVALID')
            for ref,detail in data['entries'].items():
                match=export.ID_PATTERN.fullmatch(ref)
                if not match or match[2][:2]!=bucket or not ref.startswith(manifest['kind']+'_') or ref in ids:raise ValueError('REFERENCE_ID_BOUNDARY_INVALID')
                if detail['referenceId']!=ref or detail['recordType']!=match[1] or detail['kind']!=manifest['kind']:raise ValueError('DETAIL_IDENTITY_INVALID')
                if any(detail.get(k) is not False for k in ('productionReady','productionEligible','realtimeApplied','currentnessVerified')) or detail.get('referenceOnly') is not True:raise ValueError('DETAIL_REFERENCE_FLAGS_INVALID')
                if detail['data'].get('productionReady') is not False or detail.get('sourcePeriod') is not None:raise ValueError('DETAIL_SOURCE_FLAGS_INVALID')
                ids.add(ref);counts[match[1]]+=1
                if detail.get('parentReferenceId'):parents.add(detail['parentReferenceId'])
                if match[1] not in examples:examples[match[1]]=dict(referenceId=ref,label=detail.get('label'),masterCompatibility=detail.get('masterCompatibility'))
        if parents-ids:raise ValueError('DANGLING_PATTERN_REFERENCE')
        if len(ids)!=manifest['counts']['recordCount']:raise ValueError('RECORD_COUNT_MISMATCH')
        if not set(manifest['exampleReferenceIds'])<=ids:raise ValueError('EXAMPLE_ID_MISSING')
        if export.digest(package/manifest['catalog']['path'])!=manifest['catalog']['sha256']:raise ValueError('CATALOG_INTEGRITY_INVALID')
        catalog_ids=[r['referenceId'] for r in export.rows(package/manifest['catalog']['path'])]
        if len(catalog_ids)!=len(ids) or set(catalog_ids)!=ids:raise ValueError('CATALOG_ID_COVERAGE_MISMATCH')
        sha=export.digest(manifest_path)
        for example in examples.values():
            detail=export.lookup(package,example['referenceId'],sha)
            if detail is None:raise ValueError('REAL_LOOKUP_FAILED')
        result['packages'][name]=dict(manifestSha256=sha,verifiedRecords=len(ids),recordTypeCounts=dict(counts),
             verifiedShardCount=len(manifest['entriesIndex']['shards']),maximumShardBytes=max_size,examples=examples)
    return result


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--root',type=Path,required=True);p.add_argument('--output',type=Path)
    a=p.parse_args();result=verify(a.root)
    if a.output:
        if a.output.exists():raise ValueError('OUTPUT_EXISTS')
        export.write_json(a.output,result)
    print(json.dumps(result,ensure_ascii=False,indent=2))
