# Frontend imajı: Vite ile derle, nginx ile sun.
#
# İki aşamalı: derleme araçları (node_modules ~300 MB) sonuç imaja girmez,
# yalnızca dist/ çıktısı taşınır.

FROM node:22-alpine AS build
WORKDIR /app

# package*.json önce kopyalanıyor: kaynak kod değiştiğinde npm ci katmanı
# önbellekten gelir, her derlemede bağımlılıklar yeniden inmez.
COPY package.json package-lock.json ./
RUN npm ci

COPY . .
RUN npm run build

FROM nginx:1.27-alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
