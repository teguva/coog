# syntax=docker/dockerfile:1
# Build from the repo root:
#   docker build -f server/Dockerfile -t ghcr.io/teguva/coog .
FROM node:22-bookworm-slim AS admin
WORKDIR /admin
COPY admin/package.json admin/package-lock.json ./
RUN npm ci
COPY admin/ ./
RUN npm run build

FROM golang:1.26-bookworm AS build
WORKDIR /src
COPY server/go.mod server/go.sum ./
RUN go mod download
COPY server/ ./
COPY --from=admin /admin/dist/ ./internal/adminui/fs/
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /coog-api ./cmd/coog-api \
 && CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /coog-worker ./cmd/coog-worker

# Same major as the laptop cargo install (4.0.2). GitHub's archived 1.4.x zips are too old.
FROM rust:1-bookworm AS intiface
ARG INTIFACE_ENGINE_VERSION=4.0.4
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        pkg-config libdbus-1-dev libudev-dev libusb-1.0-0-dev \
    && rm -rf /var/lib/apt/lists/*
RUN cargo install intiface-engine --version "${INTIFACE_ENGINE_VERSION}" \
    && strip /usr/local/cargo/bin/intiface-engine

FROM debian:bookworm-slim
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        ffmpeg ca-certificates python3 python3-pip tini \
        bluez dbus libdbus-1-3 libudev1 \
    && pip3 install --break-system-packages --no-cache-dir yt-dlp \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /coog-api /usr/local/bin/coog-api
COPY --from=build /coog-worker /usr/local/bin/coog-worker
COPY --from=intiface /usr/local/cargo/bin/intiface-engine /usr/local/bin/intiface-engine
EXPOSE 8090
ENV COOG_LISTEN=:8090 \
    COOG_LIBRARY_PATH=/library \
    COOG_DATA_PATH=/data \
    COOG_YTDLP=yt-dlp \
    COOG_INTIFACE_ENABLED=true \
    COOG_INTIFACE_BIN=/usr/local/bin/intiface-engine \
    DBUS_SYSTEM_BUS_ADDRESS=unix:path=/run/dbus/system_bus_socket
VOLUME ["/library", "/data"]
ENTRYPOINT ["tini", "--", "/usr/local/bin/coog-api"]
