# W06 checker — 仓库上下文构建（不再依赖 w06-deploy 的 _pure 中转副本）
# 构建上下文：仓库根目录；依赖 jar 由 deploy/libs/ 随仓库分发（服务器无外网）
FROM eclipse-temurin:21-jdk
WORKDIR /src

# 源码：契约（agents）+ 核验器与服务端（checker）
COPY agents/src/main/java/com/agenthub/w06/contract /src/main/java/com/agenthub/w06/contract
COPY checker/src/main/java /src/main/java
COPY checker/src/test/java /src/test/java
COPY deploy/libs /src/libs

# 编译 + 构建期跑全部 Jupiter 测试（任一失败即中止构建）
RUN mkdir -p out/main out/test \
    && find /src/main -name '*.java' | sort > main.txt \
    && javac -encoding UTF-8 -cp '/src/libs/*' -d out/main @main.txt \
    && find /src/test -name '*.java' | sort > test.txt \
    && javac -encoding UTF-8 -cp '/src/libs/*:/src/out/main' -d out/test @test.txt \
    && java -cp '/src/libs/*:/src/out/main:/src/out/test' com.agenthub.w06.checker.TestMain

# 运行时资产：快照 + 静态工件 + 工作台
COPY deploy/snapshot /snapshot
COPY deploy/positive-artifact.json /app/demo/positive-artifact.json
COPY deploy/error-artifact.json /app/demo/error-artifact.json
COPY deploy/reference-answer.json /app/demo/reference-answer.json
COPY deploy/workbench.html /app/workbench.html

ENV CHECKER_PORT=18095 SNAPSHOT_DIR=/snapshot STATIC_DIR=/app
EXPOSE 18095
HEALTHCHECK --interval=15s --timeout=5s --retries=5 \
  CMD wget -q -O- http://localhost:18095/health || exit 1
ENTRYPOINT ["java", "-cp", "/src/out/main:/src/libs/*", "com.agenthub.w06.checker.server.CheckerHttpServer"]
