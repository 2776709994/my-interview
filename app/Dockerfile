# 构建阶段
FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /build
COPY pom.xml .
# 配置阿里云 Maven 镜像加速下载
RUN sed -i 's|https://repo.maven.apache.org/maven2|https://maven.aliyun.com/repository/public|g' /usr/share/maven/conf/settings.xml
COPY src ./src
RUN mvn clean package -DskipTests

# 运行阶段
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=builder /build/target/*.jar app.jar

# 暴露端口
EXPOSE 8080

# 启动命令
ENTRYPOINT ["java", "-jar", "app.jar"]
