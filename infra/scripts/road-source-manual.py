"""Extract only GIS road identifier/direction evidence from the local HWP in ZIP."""
import argparse
import hashlib
import io
import json
import struct
import zipfile
import zlib


def paragraphs(data, compressed):
    raw=zlib.decompress(data,-15) if compressed else data
    pos=0
    while pos<len(raw):
        if pos+4>len(raw): raise ValueError('truncated HWP record header')
        header=struct.unpack_from('<I',raw,pos)[0];pos+=4
        tag,size=header&1023,header>>20
        if size==4095:
            if pos+4>len(raw): raise ValueError('truncated HWP extended length')
            size=struct.unpack_from('<I',raw,pos)[0];pos+=4
        if pos+size>len(raw): raise ValueError('truncated HWP payload')
        content=raw[pos:pos+size];pos+=size
        if tag==67:yield content.decode('utf-16-le').strip()


def extract(archive):
    import olefile
    with zipfile.ZipFile(archive) as z:
        names=[n for n in z.namelist() if n.lower().endswith('.hwp')]
        if len(names)!=1:raise ValueError('Expected one GIS manual')
        data=z.read(names[0])
    with olefile.OleFileIO(io.BytesIO(data)) as doc:
        header=doc.openstream('FileHeader').read()
        compressed=bool(struct.unpack_from('<I',header,36)[0]&1)
        texts=[]
        for stream in sorted(doc.listdir()):
            if stream[0]=='BodyText':texts.extend(paragraphs(doc.openstream(stream).read(),compressed))
    indexes=set()
    for i,t in enumerate(texts):
        if any(k in t for k in ('TRAF_ID_P','TRAF_ID_N','링크 그래픽 방향','ONEWAY(일방통행)','한 방향으로만 통행','국가표준노드링크는','국가표준노드링크에서 제공')):
            indexes.update(range(max(0,i-1),min(len(texts),i+4)))
    return dict(member=names[0],manualSha256=hashlib.sha256(data).hexdigest(),
                paragraphs=[dict(index=i,text=texts[i]) for i in sorted(indexes)],
                runtimeAccInfoNamespaceProven=False)


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--roads',required=True)
    a=p.parse_args()
    print(json.dumps(extract(a.roads),ensure_ascii=False,indent=2))
