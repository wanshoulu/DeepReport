# W06 Orchestrator — root DeepAgent 装配层（S7.1 装车）
# 编译在 Maven 容器完成（依赖固化 deploy/libs-orch），本镜像只装配运行
FROM eclipse-temurin:21-jdk
WORKDIR /src

COPY agent-orchestrator/target/classes /src/classes
COPY checker/target/classes /src/classes-checker
COPY agents/target/classes /src/classes-agents
COPY deploy/libs-orch /src/libs

ENV ORCHESTRATOR_PORT=18094
EXPOSE 18094
ENTRYPOINT ["java", "-cp", "/src/classes:/src/classes-checker:/src/classes-agents:/src/libs/*", "com.agenthub.w06.orchestrator.server.OrchestratorServer"]
