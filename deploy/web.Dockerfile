# The dashboard, built once and served by Caddy (which also proxies /api).
# Build from the repository root:  docker build -f deploy/web.Dockerfile .
FROM node:24-bookworm-slim AS build
WORKDIR /src
COPY dashboard/package.json dashboard/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY dashboard/ ./
RUN npm run build

FROM caddy:2.10-alpine
COPY deploy/Caddyfile /etc/caddy/Caddyfile
COPY --from=build /src/dist /srv
EXPOSE 80
