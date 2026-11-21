# W06 checker — 仓库上下文构建（与 0.3.0 镜像构建历史一致，落盘固化）
# 构建期即跑 JUnit（TestMain 驱动，任一失败即中止 build）；运行时纯 JDK HttpServer。
FROM eclipse-temurin:21-jdk
WORKDIR /src

COPY agents/src/main/java/com/agenthub/w06/contract /src/main/java/com/agenthub/w06/contract
COPY checker/src/main/java /src/main/java
COPY checker/src/test/java /src/test/java
COPY deploy/libs /src/libs

RUN mkdir -p out/main out/test \
    && find /src/main -name '*.java' | sort > main.txt \
    && javac -encoding UTF-8 -cp '/src/libs/*' -d out/main @main.txt \
    && find /src/test -name '*.java' | sort > test.txt \
    && javac -encoding UTF-8 -cp '/src/libs/*:/src/out/main' -d out/test @test.txt \
    && java -cp '/src/libs/*:/src/out/main:/src/out/test' com.agenthub.w06.checker.TestMain

COPY deploy/snapshot /snapshot
COPY deploy/demo /app/demo
COPY deploy/reference-answer.json /app/demo/reference-answer.json
COPY deploy/workbench.html /app/workbench.html
COPY deploy/report-structure.json /app/report-structure.json
COPY deploy/tools.json /app/tools.json
COPY deploy/mcp-servers.json /app/mcp-servers.json
COPY agents/packages /app/agents/packages
COPY deploy/g6.min.js /app/g6.min.js
# 技能库（skill_tool 经 /v1/skills 注册表按路径读取；此前缺失导致技能层 404）
COPY skills /app/skills

ENV CHECKER_PORT=18095 SNAPSHOT_DIR=/snapshot STATIC_DIR=/app
EXPOSE 18095
HEALTHCHECK --interval=15s --timeout=5s --retries=5 \
  CMD wget -q -O- http://localhost:18095/health || exit 1
ENTRYPOINT ["java", "-cp", "/src/out/main:/src/libs/*", "com.agenthub.w06.checker.server.CheckerHttpServer"]
