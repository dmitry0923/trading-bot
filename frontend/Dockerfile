# Build stage: сборка CRA-дашборда (TypeScript)
FROM node:20-alpine AS builder
WORKDIR /app
COPY package.json package-lock.json* ./
RUN npm install
COPY tsconfig.json .
COPY public public
COPY src src
ARG REACT_APP_AUTH_USER
ARG REACT_APP_AUTH_PASSWORD
ENV REACT_APP_AUTH_USER=$REACT_APP_AUTH_USER \
    REACT_APP_AUTH_PASSWORD=$REACT_APP_AUTH_PASSWORD
RUN npm run build

# Runtime stage: nginx, отдаёт SPA и проксирует /api на backend
FROM nginx:1.25-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=builder /app/build /usr/share/nginx/html
EXPOSE 80
