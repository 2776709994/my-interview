# 构建阶段：Node 20 + pnpm（项目使用 pnpm，见 package.json 的 packageManager 字段）
FROM node:20-alpine AS builder
WORKDIR /build

# 先复制依赖清单，利用 Docker 层缓存加速（依赖不变时不会重复 install）
COPY package.json pnpm-lock.yaml ./
COPY .npmrc ./
RUN corepack enable \
    && pnpm install --frozen-lockfile

# 复制源码并构建（tsc 类型检查 + vite build）
COPY . .
RUN pnpm build

# 运行阶段：Nginx 托管静态资源 + 反向代理 /api 与 /ws
FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=builder /build/dist /usr/share/nginx/html

EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
