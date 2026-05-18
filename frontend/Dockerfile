FROM node:20-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:alpine
COPY --from=build /app/dist /usr/share/nginx/html
# Template is processed at container startup via nginx's built-in envsubst.
# Only AUTH_TOKEN is substituted; nginx variables ($host, $uri, etc.) are left intact.
COPY nginx.conf.template /etc/nginx/templates/default.conf.template
ENV NGINX_ENVSUBST_VARS=AUTH_TOKEN
EXPOSE 80
