# 构建阶段
FROM node:18-alpine AS builder
WORKDIR /app
# 设置淘宝镜像源
COPY .npmrc .
COPY package*.json ./
RUN npm install
COPY . .
RUN npm run build

# 运行阶段 (使用 Nginx)
FROM nginx:alpine
COPY --from=builder /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
