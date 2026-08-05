# Build stage: сборка Vite-дашборда (TypeScript)
FROM node:22-alpine AS builder
WORKDIR /app
COPY package.json package-lock.json* ./
RUN npm install
COPY vite.config.ts tsconfig.json index.html ./
COPY src src
ARG VITE_AUTH_USER
ARG VITE_AUTH_PASSWORD
ENV VITE_AUTH_USER=$VITE_AUTH_USER \
    VITE_AUTH_PASSWORD=$VITE_AUTH_PASSWORD
RUN npm run build

# Runtime stage: nginx, отдаёт SPA и проксирует /api на backend
FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=builder /app/dist /usr/share/nginx/html
EXPOSE 80
