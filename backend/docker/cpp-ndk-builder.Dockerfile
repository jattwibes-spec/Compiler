FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends clang cmake make ninja-build && rm -rf /var/lib/apt/lists/*
WORKDIR /workspace
