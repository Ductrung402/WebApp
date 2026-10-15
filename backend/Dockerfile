FROM python:3.11-slim

ENV PYTHONDONTWRITEBYTECODE=1
ENV PYTHONUNBUFFERED=1

WORKDIR /app

COPY requirements.txt .
# Retry the whole install as well as individual connections: a download can
# fail after the connection has already been established.
RUN for attempt in 1 2 3; do \
        python -m pip install --no-cache-dir --disable-pip-version-check \
            --progress-bar off --retries 10 --timeout 120 -r requirements.txt && exit 0; \
        if [ "$attempt" -eq 3 ]; then exit 1; fi; \
        echo "Dependency installation failed; retrying in 5 seconds..."; \
        sleep 5; \
    done

COPY app ./app
COPY main.py init_db.py ./

EXPOSE 8000

CMD ["sh", "-c", "python init_db.py && exec uvicorn main:app --host 0.0.0.0 --port ${PORT:-8000} --workers 1"]
