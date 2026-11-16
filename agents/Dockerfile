# W06 A2A wrapper — 仓库上下文构建
# 对外 A2A 协议（:18094），对内经 w06-net 调 checker（:18095）执行确定性核验
FROM eclipse-temurin:21-jdk
WORKDIR /src

COPY agents/src/main/java/com/agenthub/w06/runtime /src/main/java/com/agenthub/w06/runtime
COPY deploy/libs /src/libs

RUN mkdir -p out \
    && find /src/main -name '*.java' | sort > srcs.txt \
    && javac -encoding UTF-8 -cp '/src/libs/*' -d out @srcs.txt

COPY deploy/positive-artifact.json /app/demo/positive-artifact.json
COPY deploy/reference-answer.json /app/demo/reference-answer.json

ENV A2A_PORT=18094 CHECKER_BASE=http://w06-checker:18095 DEMO_DIR=/app/demo
EXPOSE 18094
HEALTHCHECK --interval=15s --timeout=5s --retries=5 \
  CMD wget -q -O- http://localhost:18094/health || exit 1
ENTRYPOINT ["java", "-cp", "/src/out:/src/libs/*", "com.agenthub.w06.runtime.A2aWrapperServer"]
