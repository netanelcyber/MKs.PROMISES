FROM python:3.12-slim-bookworm
RUN apt-get update && apt-get install -y --no-install-recommends antiword \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt
COPY server.py ./
RUN useradd --create-home --uid 10001 protocol
USER protocol
ENV PYTHONUNBUFFERED=1 PYTHONDONTWRITEBYTECODE=1 PROTOCOL_HOST=0.0.0.0 PROTOCOL_PORT=8788
EXPOSE 8788
CMD ["python", "server.py"]
