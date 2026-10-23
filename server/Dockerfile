FROM node:22-alpine

WORKDIR /app
ENV NODE_ENV=production PORT=8080 DATABASE_PATH=/data/messenger.db

COPY package.json ./
COPY src ./src
RUN mkdir -p /data && chown -R node:node /app /data

USER node
EXPOSE 8080
VOLUME ["/data"]

CMD ["node", "--experimental-sqlite", "src/index.mjs"]
