FROM node:22-bookworm-slim AS build
WORKDIR /app
COPY frontend/package*.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build
FROM caddy:2
COPY --from=build /app/dist /srv
COPY deploy/Caddyfile /etc/caddy/Caddyfile
