# syntax=dockerfile:1

# 构建阶段
FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /build
COPY pom.xml .
# 先只复制 pom 并利用 Docker 层缓存下载依赖
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q -DskipTests package

# 运行阶段
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
COPY --from=builder /build/target/enterprise-rag-platform.jar app.jar
RUN useradd --create-home --shell /bin/bash apprunner && chown -R apprunner /app
USER apprunner
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
