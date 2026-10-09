# syntax=docker/dockerfile:1

# ---------- deps: install exactly what package-lock.json pins ----------
FROM node:22-alpine AS deps
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund

# ---------- build: produce the standalone server ----------
FROM node:22-alpine AS build
WORKDIR /app
# Where /api/* is proxied to. next.config.ts is evaluated during `next build` and serialised
# into the standalone server, so this must be supplied as a build arg (docker build --build-arg).
ARG API_URL=http://backend:8080
ENV API_URL=${API_URL} \
    NEXT_TELEMETRY_DISABLED=1
COPY --from=deps /app/node_modules ./node_modules
COPY . .
RUN npm run build

# ---------- runner: minimal, non-root ----------
FROM node:22-alpine AS runner
WORKDIR /app
ARG API_URL=http://backend:8080
ENV NODE_ENV=production \
    NEXT_TELEMETRY_DISABLED=1 \
    PORT=3000 \
    HOSTNAME=0.0.0.0 \
    API_URL=${API_URL}

RUN addgroup -S -g 1001 nodejs && adduser -S -u 1001 -G nodejs nextjs

COPY --from=build --chown=nextjs:nodejs /app/public ./public
COPY --from=build --chown=nextjs:nodejs /app/.next/standalone ./
COPY --from=build --chown=nextjs:nodejs /app/.next/static ./.next/static

USER nextjs
EXPOSE 3000
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD wget -q -O /dev/null http://127.0.0.1:3000/login || exit 1
CMD ["node", "server.js"]
