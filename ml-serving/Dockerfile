# 학습 서버(GPU)·종무 로컬 검증과 같은 Python 3.12 (docs/contracts/ml-serving-infra-handoff.md). 3.13 은 검증되지 않은 조합
FROM python:3.12-slim

# LightGBM 은 C++ 코어가 OpenMP(libgomp.so.1)를 요구한다. slim 이미지에는 없어 빠지면 import 에서 죽는다 (9/14 첫 배포 실패 원인)
RUN apt-get update \
    && apt-get install -y --no-install-recommends libgomp1 \
    && rm -rf /var/lib/apt/lists/*

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1

WORKDIR /app

COPY requirements.txt ./requirements.txt
RUN pip install --no-cache-dir -r requirements.txt

COPY . .

RUN useradd --create-home --uid 10001 ddubi \
    && chown -R ddubi:ddubi /app

USER ddubi

EXPOSE 8000

CMD ["python", "-m", "uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8000"]
