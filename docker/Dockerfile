# 运行镜像：只放 jar（构建在 CI/本机完成，见 docker/README.md）
FROM eclipse-temurin:17-jre

WORKDIR /app
COPY bookkeep-web/target/bookkeep.jar /app/app.jar

# 不用 root 跑应用；容器内只用 8080，不 publish
RUN groupadd -r app && useradd -r -g app app && chown -R app:app /app
USER app

EXPOSE 8080

# 生产 profile：收紧限流（见 application-prod.yml），并触发 ProdSecurityGuard 的启动自检
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar", "--spring.profiles.active=prod"]