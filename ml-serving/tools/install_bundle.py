"""다운로드한 자체 v12 모델과 참조 ZIP을 프로젝트의 Git 제외 폴더에 설치한다.

압축 경로·중복·전체 크기를 먼저 검사하고 새 폴더에만 푼다. 모델·참조·인코더를
같은 SHA manifest로 대조한다. 기존 묶음을 덮어쓰지 않으며 실패 폴더도 임의 삭제하지 않는다.
"""

import argparse
import hashlib
import json
import shutil
import zipfile
from pathlib import Path, PurePosixPath


def install(archive_path, model_path, destination):
    """명시한 로컬 파일만 복사한다. 정상 완료 후 서비스 시작은 별도 명령으로 수행한다."""
    destination = Path(destination).resolve()
    if destination.exists():
        raise ValueError("대상 폴더가 이미 존재합니다. 새 버전 경로를 지정하세요.")
    with zipfile.ZipFile(archive_path) as archive:
        entries = archive.infolist()
        names = [entry.filename for entry in entries]
        if len(set(names)) != len(names) or sum(entry.file_size for entry in entries) > 2 * 1024**3:
            raise ValueError("중복 경로 또는 2GiB 초과 압축 해제 크기")
        for entry in entries:
            name = PurePosixPath(entry.filename)
            if name.is_absolute() or ".." in name.parts or "\\" in entry.filename or ":" in entry.filename:
                raise ValueError("ZIP 경로가 잘못되었습니다.")
            if (entry.external_attr >> 16) & 0o170000 == 0o120000:
                raise ValueError("ZIP 심볼릭 링크는 허용하지 않습니다.")
        metadata = json.loads(archive.read("bundle.json"))
        destination.mkdir(parents=True)
        for entry in entries:
            target = destination / entry.filename
            if entry.is_dir():
                target.mkdir(parents=True, exist_ok=True)
            else:
                target.parent.mkdir(parents=True, exist_ok=True)
                with archive.open(entry) as source, target.open("xb") as output:
                    shutil.copyfileobj(source, output)
    shutil.copyfile(model_path, destination / "selected_model.joblib")
    for relative, expected in metadata["files"].items():
        path = (destination / relative).resolve()
        if not path.is_relative_to(destination):
            raise ValueError("manifest 경로가 묶음을 벗어납니다.")
        with path.open("rb") as handle:
            actual = hashlib.file_digest(handle, "sha256").hexdigest()
        if actual != expected:
            raise ValueError("묶음 SHA256이 일치하지 않습니다: " + relative)
    print(json.dumps({"installed": str(destination), "verified_files": len(metadata["files"])}))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive", type=Path, required=True)
    parser.add_argument("--model", type=Path, required=True)
    parser.add_argument("--destination", type=Path, required=True)
    args = parser.parse_args()
    install(args.archive, args.model, args.destination)
