ARG PYTHON_IMAGE=python:3.11-slim
FROM ${PYTHON_IMAGE}

WORKDIR /app
COPY collector/requirements.txt ./requirements.txt
RUN pip install --no-cache-dir -r requirements.txt
COPY collector/src ./src
COPY collector/config ./config

ENV PYTHONPATH=/app/src
CMD ["python", "-m", "main"]
