# notification-service/Dockerfile
# Placeholder — replace with your actual service build.
# The image is built by CI and pushed to GHCR.

FROM node:20-alpine AS base
WORKDIR /app

# Dependencies (add package.json once you have a service)
# COPY package*.json ./
# RUN npm ci --omit=dev

# Application code
# COPY . .

# Health endpoint expected by docker-compose healthcheck
# EXPOSE 3000
# CMD ["node", "src/index.js"]

# ── Temporary placeholder ─────────────────────────────────────
# Keeps the image buildable until real service code is added.
RUN apk add --no-cache wget
EXPOSE 3000
CMD ["sh", "-c", "while true; do { echo -e 'HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nOK'; } | nc -l -p 3000 -q 1; done"]
