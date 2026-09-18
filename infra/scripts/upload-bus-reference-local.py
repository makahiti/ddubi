"""서버 전용: backend 네트워크 공간의 loopback으로 불변 참고자료를 적재한다.

sudo python3 upload-bus-reference-local.py <bundle-folder> <checkpoint>
키는 docker inspect 결과에서 메모리로만 읽고 자식 환경으로 전달한다.
포트 공개, 인증 우회, DB 직접 삽입, 추천 활성화는 하지 않는다.
"""
import argparse
import json
import os
from pathlib import Path
import subprocess


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('folder',type=Path)
    parser.add_argument('checkpoint',type=Path)
    args=parser.parse_args()
    if os.name!='posix' or os.geteuid()!=0:
        parser.error('Linux 서버에서 sudo로 실행해야 합니다.')
    details=json.loads(subprocess.check_output(['docker','inspect','backend'],text=True))[0]
    if not details['State']['Running'] or details['State'].get('Health',{}).get('Status')!='healthy':
        raise SystemExit('BACKEND_NOT_HEALTHY')
    env=dict(item.split('=',1) for item in details['Config']['Env'] if '=' in item)
    key=env.get('INTERNAL_API_KEY','')
    if not key: raise SystemExit('INTERNAL_API_KEY_NOT_CONFIGURED')
    child_env=os.environ.copy()
    child_env['DDUBI_INTERNAL_API_KEY']=key
    script=Path(__file__).resolve().with_name('transfer-bus-reference.py')
    command=['nsenter','--target',str(details['State']['Pid']),'--net','--','python3',str(script),
             str(args.folder.resolve()),'--upload','--base-url','http://127.0.0.1:8080',
             '--checkpoint',str(args.checkpoint.resolve())]
    result=subprocess.run(command,env=child_env,check=False)
    raise SystemExit(result.returncode)


if __name__=='__main__': main()
