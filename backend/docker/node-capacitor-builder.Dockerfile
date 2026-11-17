FROM node:20-bookworm
RUN apt-get update && apt-get install -y --no-install-recommends openjdk-17-jdk zip && rm -rf /var/lib/apt/lists/*
RUN npm install -g typescript @capacitor/cli
WORKDIR /workspace
