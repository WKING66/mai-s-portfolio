FROM node:24-slim@sha256:dc26ae92c9fb15cf6b1d63e20f0944b2efe0f115b20e00be48fb23fceb061c54
RUN install -d -o 10001 -g 10001 /app
WORKDIR /app
COPY --chmod=0444 web.tar.gz /tmp/web.tar.gz
USER 10001:10001
RUN tar -xzf /tmp/web.tar.gz --no-same-owner --no-same-permissions && test -r .output/server/index.mjs
ENV NODE_ENV=production NODE_OPTIONS=--max-old-space-size=512 HOST=127.0.0.1 PORT=3000
CMD ["node", "/app/.output/server/index.mjs"]
