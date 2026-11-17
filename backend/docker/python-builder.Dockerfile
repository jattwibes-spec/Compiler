FROM python:3.11-slim
RUN apt-get update && apt-get install -y --no-install-recommends git zip unzip openjdk-17-jdk && rm -rf /var/lib/apt/lists/*
RUN pip install --no-cache-dir buildozer pyinstaller briefcase cython
WORKDIR /workspace
