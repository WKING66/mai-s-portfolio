# Nuxt 前端容器：只跑 Nitro 产物，构建已在 CI 完成。
FROM node:24-slim

RUN groupadd --gid 10001 app && useradd --uid 10001 --gid app --no-create-home --shell /usr/sbin/nologin app

WORKDIR /app
COPY web/.output /app/.output

ENV NODE_ENV=production \
    NODE_OPTIONS=--max-old-space-size=512 \
    HOST=127.0.0.1 \
    PORT=3000

USER 10001:10001
EXPOSE 3000

CMD ["node", "/app/.output/server/index.mjs"]
