"""실제 장애 형태인 반대 방향 연결과 공식 파일 변조를 작은 노선 fixture로 검증한다."""
import csv
import gzip
import hashlib
import json
import tempfile
import unittest
from pathlib import Path
from audit import audit


class AuditTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name);self.sources=[]
        self.edge=dict(namespace='SEOUL_BUS',routeId='r',fromStopId='a',toStopId='b',fromOrder=1,toOrder=2,
                       direction=None,segmentId='BUS:r:a:b')
        self.bus=self.root/'bus.gz';self.bus.write_bytes(gzip.compress(json.dumps(dict(masterVersion='fixture',edges=[self.edge])).encode()))
        self.write(58,[{'노선ID':'r','노드일련번호':'1','노드ID':'a','정류장순번':'1','사용여부':'1','구간ID':'before'},
                       {'노선ID':'r','노드일련번호':'2','노드ID':'b','정류장순번':'2','사용여부':'1','구간ID':'s'}])
        self.write(62,[{'구간ID':'s','구간순번':'1','링크ID':'l1'}, {'구간ID':'s','구간순번':'2','링크ID':'l2'}])
        self.road=[{'링크ID':'l1','시작노드ID':'n1','종료노드ID':'n2','사용여부':'1'},
                   {'링크ID':'l2','시작노드ID':'n2','종료노드ID':'n3','사용여부':'1'}]
        self.write(63,self.road)
        self.write(7,[{'표준링크ID':'l1','시작노드ID':'n1','종료노드ID':'n2','링크ID':'t1'},
                      {'표준링크ID':'l2','시작노드ID':'n2','종료노드ID':'n3','링크ID':'t2'}])

    def write(self, dataset, rows):
        p=self.root/f'{dataset}.csv'
        # 내려받은 원본처럼 각 셀 BOM을 넣어도 문자열 ID와 순번이 보존되어야 한다.
        with p.open('w',encoding='utf-8-sig',newline='') as f:
            writer=csv.writer(f);writer.writerow(['\ufeff'+k for k in rows[0]])
            for row in rows:writer.writerow(['\ufeff'+str(v) for v in row.values()])
        self.sources=[s for s in self.sources if s['datasetId']!=dataset]
        self.sources.append(dict(datasetId=dataset,path=str(p),sha256=hashlib.sha256(p.read_bytes()).hexdigest()))
        (self.root/'sources.json').write_text(json.dumps(self.sources),encoding='utf-8')

    def test_two_link_path_and_topis_chain(self):
        result=audit(self.root,self.bus)
        self.assertEqual(result['counts']['TOPIS_PATH_COMPLETE'],1)
        candidate=json.loads((self.root/'mapping-candidates.jsonl').read_text(encoding='utf-8'))
        self.assertEqual(candidate['bisLinkIds'],['l1','l2'])
        self.assertEqual(candidate['topisServiceLinkIds'],['t1','t2'])
        self.assertEqual(result['operationalMappings'],0)

    def test_reverse_link_is_rejected(self):
        self.road[1]['시작노드ID']='n3';self.road[1]['종료노드ID']='n2';self.write(63,self.road)
        result=audit(self.root,self.bus)
        self.assertEqual(result['counts']['DIRECTIONAL_LINK_CHAIN_DISCONNECTED'],1)

    def test_source_tamper_rejected(self):
        with (self.root/'58.csv').open('a',encoding='utf-8') as f:f.write('changed')
        with self.assertRaisesRegex(ValueError,'SHA'):audit(self.root,self.bus)

    def test_same_id_with_different_node_pair_is_not_topis_match(self):
        self.write(7,[{'표준링크ID':'l1','시작노드ID':'wrong','종료노드ID':'n2','링크ID':'t1'}])
        self.assertEqual(audit(self.root,self.bus)['counts']['TOPIS_PATH_PARTIAL'],1)


if __name__=='__main__':unittest.main()
