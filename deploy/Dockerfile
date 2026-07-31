FROM python:3.12-slim

WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends \
    gcc libffi-dev curl \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
# --only-binary turns a missing aarch64 wheel into a fast, loud failure instead
# of a silent 20-minute source build on a small VM. http-ece (pulled in by
# pywebpush) is exempted because it publishes NO wheel on PyPI at any version -
# verified with `pip download --only-binary=:all: http-ece` -> "from versions:
# none". It is pure Python, so it builds from sdist in seconds.
RUN pip install --no-cache-dir \
      --only-binary=:all: --no-binary=http-ece \
      -r requirements.txt

COPY silai/ silai/

# There is deliberately NO `download-files` step. VAD and end-of-turn weights
# now ship inside the livekit-local-inference wheel, so the old ~461 MB
# HuggingFace layer is gone, along with the build's dependency on HuggingFace
# being reachable and un-rate-limited.
#
# Import-time smoke test instead: this is the loud, BUILD-time failure for
# livekit API drift, which beats discovering it at 8am on her phone.
RUN python -c "import silai.agent; print('agent module OK')"

# Frontend build output, committed to the repo so this image needs no Node.
# Last, because it changes most often and is cheap to re-copy.
COPY web/ web/

EXPOSE 8080 8081

# Overridden per-service in docker-compose.yml
CMD ["uvicorn", "silai.web:app", "--host", "0.0.0.0", "--port", "8080"]
