# Build stage: сборка CRA-дашборда
FROM node:20-alpine AS builder
WORKDIR /app
COPY package.json .
RUN npm install
COPY public public
COPY src src
RUN npm run build

# Runtime stage: nginx, отдаёт SPA и проксирует /api на backend
FROM nginx:1.25-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=builder /app/build /usr/share/nginx/html
EXPOSE 80
