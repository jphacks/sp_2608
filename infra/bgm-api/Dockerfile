# PC（Docker）で動かす bgm-api。TypeScript を1ファイルにまとめ、実行イメージには依存パッケージを入れない。
FROM node:24.11.1-slim@sha256:48abc13a19400ca3985071e287bd405a1d99306770eb81d61202fb6b65cf0b57 AS build
WORKDIR /build
COPY package.json package-lock.json ./
# wrangler 等の postinstall は不要。esbuild のバイナリは optional 依存として入る。
RUN npm ci --ignore-scripts --no-audit --no-fund
COPY src ./src
RUN npm run build:node

FROM node:24.11.1-slim@sha256:48abc13a19400ca3985071e287bd405a1d99306770eb81d61202fb6b65cf0b57
WORKDIR /app
COPY --from=build /build/dist/node.mjs ./node.mjs
COPY migrations ./migrations
COPY catalog/catalog.jsonl ./catalog/catalog.jsonl
ENV BGM_MIGRATIONS=/app/migrations BGM_CATALOG=/app/catalog/catalog.jsonl NODE_ENV=production
# 名前付きボリュームは初回にこの所有者を引き継ぐ。root のままだと node ユーザーで DB を作れない。
RUN mkdir /state && chown node:node /state
USER node
EXPOSE 8005
CMD ["node", "--disable-warning=ExperimentalWarning", "node.mjs"]
