FROM oven/bun:1-alpine

WORKDIR /app
COPY package.json bridge.ts ./
COPY src ./src

USER bun
CMD ["bun", "run", "bridge.ts"]
