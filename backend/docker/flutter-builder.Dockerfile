FROM ghcr.io/cirruslabs/flutter:stable
RUN apt-get update && apt-get install -y --no-install-recommends zip && rm -rf /var/lib/apt/lists/*
WORKDIR /workspace
