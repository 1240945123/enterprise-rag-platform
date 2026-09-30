# Enterprise RAG Platform

[![Java](https://img.shields.io/badge/Java-21-blue.svg)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5-6DB33F.svg)](https://spring.io/projects/spring-boot)
[![Spring AI](https://img.shields.io/badge/Spring%20AI-1.1-6DB33F.svg)](https://spring.io/projects/spring-ai)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-PGvector-4169E1.svg)](https://github.com/pgvector/pgvector)
[![License](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

一个面向企业场景的 AI 知识库问答平台，基于 **Spring Boot 3 + Spring AI + PGvector** 构建。

完整链路为：**文档摄取 → 递归切分 → 向量化 → 多知识库隔离检索 → 引用核对闸门 → 带依据生成 → 指标观测**。

> **设计重心不是「能否调通大模型」，而是企业落地时真正决定成败的三件事：**
> 答案必须有出处（引用溯源）、数据必须隔离（多知识库租户）、质量必须可度量（指标埋点）。

---

## 目录

- [项目简介](#项目简介)
- [功能特性](#功能特性)
- [系统架构](#系统架构)
- [技术选型](#技术选型)
- [目录结构](#目录结构)
- [安装步骤](#安装步骤)
- [使用方法](#使用方法)
- [配置说明](#配置说明)
- [关键设计取舍](#关键设计取舍)
- [测试](#测试)
- [路线图](#路线图)
- [贡献指南](#贡献指南)
- [许可证](#许可证)

---

## 项目简介

企业内部知识库的落地实践反复验证了三个典型失败模式：

| 失败模式 | 具体表现 | 后果 |
| --- | --- | --- |
| **答案无出处** | 模型表述看似权威，但无法定位依据的文档与段落 | 出错无法追责，业务方不敢采信 |
| **知识串味** | 各部门文档混入同一索引，检索结果跨域污染 | 财务问制度却答出员工手册内容 |
| **只有吞吐没有质量** | 监控仅覆盖 QPS 与延迟，答案质量退化无人察觉 | 问题静默积累，直到用户投诉才暴露 |

本项目针对上述三点做定向设计：**引用溯源**解决可追责性，**多知识库隔离**解决跨域污染，
**Prometheus 指标 + 引用闸门**让质量可观测、可告警。相关实现细节见[关键设计取舍](#关键设计取舍)。

---

## 功能特性

| 特性 | 说明 |
| --- | --- |
| **递归切分** | 四级语义切分（空行 → 换行 → 中文句末标点 → 定长硬切）+ 重叠窗口；每级仅在「确实切得开」时下钻，定长硬切兜底，保证算法终止 |
| **引用溯源** | 每个切片携带 `document_id` / `title` / `seq` 元数据，回答时回传完整引用清单与相似度分数 |
| **幻觉阻断闸门** | 引用数量或相似度未达标时**直接拒答，不调用大模型**，从源头避免无依据输出 |
| **多知识库隔离** | 检索阶段通过过滤表达式强制隔离，并对过滤参数做单引号转义，防止表达式注入 |
| **幂等摄取** | 切片主键由内容摘要派生；写入前按 `document_id` 清理旧向量，重复摄取语义为**覆盖**而非追加 |
| **可替换向量化方案** | 本地 ONNX（离线可用）与云端 Embedding API（OpenAI 兼容）一键切换，无需改动业务代码 |
| **可观测性** | `kb.qa.latency`（P50/P95 直方图）、`kb.qa.answers`、`kb.qa.rejections` 三项指标，Prometheus 可直接抓取 |
| **双运行模式** | `local` 零基础设施（内存向量库）/ `pgvector` 真实持久化，通过 profile 切换 |
| **离线可测** | 单元测试与集成测试均不依赖网络与 Docker，`RagEndToEndTest` 在无 Docker 环境自动跳过 |

---

## 系统架构

```
┌─────────────────────────── HTTP 层 ───────────────────────────┐
│  POST /api/v1/ingest          POST /api/v1/qa/ask             │
└───────────────┬───────────────────────────┬───────────────────┘
                │                           │
        ┌───────▼────────┐          ┌───────▼─────────┐
        │  IngestService │          │    QaService    │
        │  清洗 / 切分    │          │  闸门 + 生成     │
        └───────┬────────┘          └───┬─────────┬───┘
                │                       │         │
     ┌──────────▼─────────┐   ┌─────────▼──┐  ┌───▼────────────┐
     │    TextSplitter    │   │ Retrieval  │  │ CitationValidator│
     │ 递归切分 + 重叠窗口 │   │ 隔离 + 阈值 │  │  引用可用性判定   │
     └──────────┬─────────┘   └─────┬──────┘  └───┬────────────┘
                │                   │             │
                │           ┌───────▼────────┐    │
                │           │   VectorStore  │    │
                │           │ local/pgvector │    │
                └──────────►└───────┬────────┘    │
                                    │             │
                          ┌─────────▼────────┐    │
                          │  EmbeddingModel  │    │
                          │   ONNX / 云端 API │    │
                          └──────────────────┘    │
                                                  │
                                    不足 ─────────┘
                                     ↓
                              【拒答，不调模型】
                                    充足 ↓
                            ┌─────────────────┐
                            │    ChatModel    │
                            │ OpenAI 兼容协议  │
                            └─────────────────┘
```

**闸门置于生成之前**：`CitationValidator` 在检索之后、模型调用之前完成判定。
这一顺序是整个设计的核心，直接决定了无效请求不会产生模型开销。

架构细节与扩展点说明见 [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)。

---

## 技术选型

| 层次 | 选型 | 选择理由 |
| --- | --- | --- |
| 应用框架 | Spring Boot 3.5 | Java 后端事实标准，生态成熟 |
| AI 框架 | Spring AI 1.1 | 统一 `ChatModel` / `EmbeddingModel` / `VectorStore` 抽象，更换模型厂商无需改动业务代码 |
| 向量存储 | PostgreSQL + PGvector | 结构化数据与向量同库，避免引入需独立运维的分布式组件；采用 HNSW 索引 |
| 本地向量化 | ONNX Runtime（`all-MiniLM-L6-v2`，384 维） | 无云端依赖即可跑通全链路，便于 CI 与离线演示 |
| 中文向量化 | DashScope `text-embedding-v3`（1024 维，OpenAI 兼容） | 中文语义区分度良好，适合真实知识库；与对话模型复用同一 Key |
| 对话模型 | OpenAI 兼容协议 | DeepSeek / DashScope / OpenAI 均可，仅需修改两个环境变量 |
| 可观测性 | Micrometer + Prometheus | 指标格式标准化，Grafana 可直接接入 |
| 部署 | Docker Compose | 单条命令启动 PGvector 与应用 |

**为什么选择 PGvector 而非专用向量数据库？**

企业知识库的本质是「结构化元数据 + 向量」的混合查询场景。将向量存入 PostgreSQL，
可以直接复用既有的备份、权限、审计与运维体系，无需为一个功能模块再引入一套独立运维的组件。
在数据量级达到千万级以上、且写入吞吐成为瓶颈时，再考虑迁移至专用向量数据库更为合理。

---

## 目录结构

```
enterprise-rag-platform/
├── src/main/java/com/tianchen/kb/
│   ├── config/            # 配置属性绑定、本地向量库装配
│   ├── domain/            # 领域模型：切片、引用、问答结果
│   ├── ingest/            # 文档摄取：切分器与入库服务
│   ├── retrieval/         # 向量检索：知识库隔离与相似度阈值裁剪
│   ├── qa/                # RAG 主链路：引用核对闸门与答案生成
│   ├── web/               # REST 控制器、DTO、统一异常处理
│   ├── observability/     # 业务指标注册与语义化封装
│   └── support/           # 通用工具（内容摘要）
├── src/main/resources/
│   ├── application.yml          # 主配置：默认值、业务参数、可观测性
│   └── application-local.yml    # local / pgvector 两套 profile 的具体配置
├── src/test/java/com/tianchen/kb/
│   ├── ingest/                  # 切分器单元测试
│   ├── qa/                      # 引用校验单元测试
│   └── testsupport/             # 确定性桩 Embedding 模型
├── samples/               # 示例企业文档（差旅报销、新员工入职、数据安全）
├── sql/schema.sql         # PGvector 表结构与索引参考
├── docs/                  # 架构说明与接口文档
├── scripts/               # 本地运行、样例导入、冒烟测试、GitHub 推送
├── Dockerfile
├── docker-compose.yml
└── LICENSE
```

---

## 安装步骤

### 环境要求

| 依赖 | 版本要求 | 是否必需 |
| --- | --- | --- |
| JDK | 21+ | 必需 |
| Maven | 3.9+ | 必需 |
| Git | 任意近期版本 | 必需 |
| Docker / Docker Compose | 任意近期版本 | 仅 `pgvector` 模式需要 |

> 本项目**未附带 Maven Wrapper**，请使用本机已安装的 `mvn`。

### 1. 获取代码

```bash
git clone git@github.com:1240945123/enterprise-rag-platform.git
cd enterprise-rag-platform
```

使用 HTTPS 协议时替换为：

```bash
git clone https://github.com/1240945123/enterprise-rag-platform.git
```

### 2. 准备配置文件

```bash
cp .env.example .env
```

`.env` 中**至少需要确认以下两项**：

- `LLM_API_KEY` —— 对话模型密钥（必填，否则无法生成答案）；
- `EMBEDDING_PROVIDER` —— 向量化方案（模板默认 `openai`）。

其余变量均带有合理默认值，可按需调整。全部配置项的说明见[配置说明](#配置说明)。

> **关于向量化方案的选择**
>
> 模板默认使用云端 Embedding（`EMBEDDING_PROVIDER=openai`），中文区分度明显更好，推荐用于真实知识库评测。
> 若希望完全脱离云端，可改为 `EMBEDDING_PROVIDER=transformers` 使用本地 ONNX 模型——
> 注意该模型为英文语料训练，**中文场景区分度不足，仅适合跑通链路**（实测对比见[关键设计取舍](#关键设计取舍)）。
>
> `.env` 已列入 `.gitignore`，不会被提交；密钥请勿写入 `application.yml` 或任何受版本控制的文件。

### 3. 启动服务

**方式 A：零基础设施模式（推荐首次运行）**

无需 Docker 与 PostgreSQL，使用内存向量库即可跑通完整链路：

```bash
./scripts/run-local.sh          # Linux / macOS
.\scripts\run-local.ps1         # Windows PowerShell
```

`run-local.ps1` 支持 `-Port`、`-Profile`、`-Embedding`、`-JavaHome`、`-MavenHome`、`-MavenSettings`
与 `-ToolRoot` 参数，用于覆盖端口、运行模式、向量化方案及本地工具链路径。

**前置说明：启动脚本会自动载入项目根目录的 `.env`**（存在即生效）。
`run-local.ps1` 的命令行参数优先级高于 `.env`；`run-local.sh` 无命令行参数，
如需切换运行模式请直接修改 `.env`。

**方式 B：真实 PGvector 持久化**

`docker-compose.yml` 定义了 `pgvector` 与 `app` 两个服务。
若仅需数据库、应用仍在本地运行，请**只启动 `pgvector` 服务**，避免 `app` 容器占用 8080 端口：

```bash
# 1) 启动 PostgreSQL + PGvector（仅数据库）
docker compose up -d pgvector

# 2) 以 pgvector 模式启动应用
.\scripts\run-local.ps1 -Profile pgvector       # Windows PowerShell
./scripts/run-local.sh                          # Linux / macOS：先把 .env 中的
                                                # SPRING_PROFILES_ACTIVE 改为 pgvector
```

若希望应用一并容器化（无需本机 JDK 与 Maven），直接启动全部服务：

```bash
docker compose up -d
```

`docker-compose.yml` 中的 `app` 服务默认使用 `EMBEDDING_PROVIDER=transformers`（本地 ONNX）。
如需在容器内使用云端向量化，请在启动前导出 `LLM_BASE_URL`、`LLM_API_KEY` 等变量，
或直接修改 `docker-compose.yml` 中 `app` 服务的 `environment` 段。

### 4. 验证启动

```bash
curl -s http://localhost:8080/actuator/health
```

预期返回 `{"status":"UP"}`。

---

## 使用方法

### 导入示例文档

```bash
./scripts/load-samples.sh              # 默认导入至知识库 kb-corp
./scripts/load-samples.sh my-kb        # 导入至指定知识库
BASE_URL=http://host:8080 ./scripts/load-samples.sh   # 指定服务地址
```

脚本会先校验服务健康状态，再逐份导入 `samples/` 下的文档。

### 知识库问答

```bash
curl -s -X POST http://localhost:8080/api/v1/qa/ask \
  -H 'Content-Type: application/json' \
  -d '{
        "knowledgeBaseId": "kb-corp",
        "question": "一线城市住宿费标准是多少？",
        "topK": 3
      }'
```

响应示例：

```json
{
  "answer": "一线城市住宿费上限为每人每晚 600 元 [1]。发票抬头须与公司全称一致 [1]。",
  "citations": [
    {
      "documentId": "a1b2c3d4e5f60718",
      "title": "差旅费报销管理办法",
      "seq": 12,
      "snippet": "住宿费实行限额管理，一线城市每人每晚上限 600 元……",
      "score": 0.83
    }
  ],
  "retrieved": 3,
  "grounded": true,
  "elapsedMillis": 1284,
  "knowledgeBaseId": "kb-corp",
  "question": "一线城市住宿费标准是多少？"
}
```

**字段说明**

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `answer` | string | 生成答案；被拒答时为拒答说明文本 |
| `citations` | array | 引用清单，含来源文档、切片序号、片段与相似度分数 |
| `retrieved` | int | 实际召回并通过阈值的切片数 |
| `grounded` | boolean | 引用是否达标。为 `false` 时表示走拒答分支，**未调用大模型** |
| `elapsedMillis` | long | 服务端处理耗时（毫秒） |
| `knowledgeBaseId` | string | 回显本次请求的知识库标识 |
| `question` | string | 回显本次请求的问题原文 |

### 摄取自定义文档

```bash
curl -s -X POST http://localhost:8080/api/v1/ingest \
  -H 'Content-Type: application/json' \
  -d '{
        "knowledgeBaseId": "kb-corp",
        "title": "差旅费报销管理办法",
        "content": "……文档正文……"
      }'
```

请求字段中 `knowledgeBaseId` 与 `content` 为必填，`title` 可省略（省略时使用默认标题）。

### 运行端到端冒烟测试

在服务已启动的前提下执行：

```bash
python scripts/smoke-test.py --base-url http://127.0.0.1:8080
```

| 参数 | 默认值 | 说明 |
| --- | --- | --- |
| `--base-url` | `http://127.0.0.1:8080` | 被测服务地址 |
| `--kb` | `kb-corp` | 使用的知识库标识 |
| `--timeout` | `60` | 单次请求超时（秒） |
| `--wait-startup` | `0` | 等待服务启动的最长秒数，`0` 表示不等待 |

脚本覆盖健康检查 → 摄取与幂等 → 长文切分 → 带引用问答 → 幻觉阻断 → 知识库隔离 → 指标校验共 14 项检查，仅依赖 Python 标准库。

### REST API 一览

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/api/v1/ingest` | 摄取文档，返回 `documentId` 与 `chunkCount` |
| `POST` | `/api/v1/qa/ask` | 带引用溯源的问答 |
| `GET` | `/actuator/health` | 健康检查 |
| `GET` | `/actuator/prometheus` | Prometheus 指标 |

完整的请求/响应结构与错误码定义见 [`docs/API.md`](docs/API.md)。

---

## 配置说明

配置项均支持通过环境变量覆盖，便于容器化部署。

### 业务参数

| 配置项 | 环境变量 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `kb.ingest.chunk-size` | `CHUNK_SIZE` | `800` | 切片目标长度（字符） |
| `kb.ingest.overlap` | `CHUNK_OVERLAP` | `120` | 相邻切片的重叠窗口长度 |
| `kb.retrieval.top-k` | `RETRIEVAL_TOP_K` | `5` | 默认召回条数，可被请求体的 `topK` 覆盖 |
| `kb.retrieval.min-score` | `RETRIEVAL_MIN_SCORE` | `0.5` | 相似度阈值，低于此值的切片不参与作答 |
| `kb.qa.min-citation-score` | `MIN_CITATION_SCORE` | `0.5` | 引用可用的最低分数 |
| `kb.qa.min-citations` | `MIN_CITATIONS` | `1` | 最少引用数，不足即触发拒答 |

> **阈值与 Embedding 模型强绑定。**
> 不同模型的分数分布不可直接比较，更换模型后必须重新标定 `min-score` 与 `min-citation-score`。
> 调低会引入无依据内容（幻觉风险上升），调高会把真实提问误判为「无相关资料」（拒答率上升）。

### 模型与向量化

| 配置项 | 环境变量 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `spring.ai.model.embedding` | `EMBEDDING_PROVIDER` | `transformers` | `transformers`（本地 ONNX）/ `openai`（云端）/ `none`（关闭自动装配） |
| `spring.ai.openai.base-url` | `LLM_BASE_URL` | `https://api.deepseek.com` | OpenAI 兼容端点 |
| `spring.ai.openai.api-key` | `LLM_API_KEY` | `unused` | 模型密钥；未配置时无法生成答案 |
| `spring.ai.openai.chat.options.model` | `LLM_MODEL` | `deepseek-chat` | 对话模型名称 |
| `spring.ai.openai.embedding.options.model` | `EMBEDDING_MODEL` | `text-embedding-3-small` | 云端 Embedding 模型名称 |
| `spring.ai.vectorstore.pgvector.dimensions` | `PGVECTOR_DIMENSIONS` | `1024` | 向量维度，**必须与所用 Embedding 模型的输出维度一致** |

> **DashScope 用户请注意**：`LLM_BASE_URL` 只需写到 `/compatible-mode`，
> Spring AI 会自动拼接 `/v1/chat/completions`；多写 `/v1` 会导致 404。

### 运行模式与数据源

| 配置项 | 环境变量 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `spring.profiles.active` | `SPRING_PROFILES_ACTIVE` | `local` | `local`（内存向量库）或 `pgvector`（持久化） |
| `spring.datasource.url` | `PG_URL` | `jdbc:postgresql://localhost:5432/kb` | PostgreSQL 连接串 |
| `spring.datasource.username` | `PG_USERNAME` | `kb` | 数据库用户名 |
| `spring.datasource.password` | `PG_PASSWORD` | `kb` | 数据库密码 |
| `server.port` | — | `8080` | 服务端口 |

### 可观测性

| 指标 | 类型 | 说明 |
| --- | --- | --- |
| `kb.qa.latency` | Timer | 问答耗时，已开启百分比直方图，可计算 P50/P95 |
| `kb.qa.answers` | Counter | 有引用支撑并调用大模型作答的次数 |
| `kb.qa.rejections` | Counter | 因引用不足被拒答的次数 |

指标端点已通过 `management.endpoints.web.exposure.include` 开放（`health,info,metrics,prometheus`）。

---

## 关键设计取舍

### 1. 为什么宁可拒答，也不让模型硬答

企业知识场景中，一个没有出处的错误答案，其代价远大于一句「不知道」。
`CitationValidator` 在**检索之后、生成之前**设置闸门：引用数量或相似度不达标即返回明确的拒答说明，
**完全不调用大模型**。

实测数据可佐证这条路径确实省去了模型调用：拒答耗时约 **200~270 ms**，
正常作答约 **1.1~1.6 s**，两者相差一个数量级。

### 2. 为什么相似度阈值可配置而非硬编码

不同 Embedding 模型的分数分布差异显著，绝对分数不具可比性。
因此将 `kb.retrieval.min-score` 与 `kb.qa.min-citation-score` 暴露为配置项，
更换模型时只需调整参数，无需修改代码。**但阈值必须自行实测标定**，本仓库的标定结果如下：

| 向量化模型 | 相关问法分数 | 无关问法分数 | 结论 |
| --- | --- | --- | --- |
| 本地 ONNX `all-MiniLM-L6-v2` | ≈ 0.47 | ≈ 0.52 | 中文场景**几乎不可分**，仅可用于跑通链路 |
| DashScope `text-embedding-v3` | 0.60 ~ 0.80 | 0.38 ~ 0.41 | 可分性良好，取两簇中点 **0.50** |

### 3. 为什么手写切分器而不直接使用 TokenTextSplitter

需要精确控制三件事：

1. 中文按「句」而非按 token 切分；
2. 相邻切片保留可配置的重叠窗口，避免答案在边界处被截断；
3. 切分结果能被单元测试完整覆盖。

手写切分器的核心风险是**递归不终止**——若某一级分隔符不存在却仍然下钻，就会陷入无限递归。
因此每一级都必须先校验「确实切得开」才下钻，并保留定长硬切作为最终兜底。
`TextSplitterTest` 以「无空行、无标点的超长文本」专门守护这条边界。

### 4. 为什么重复摄取需要「先删除后写入」，而非仅比对文档 ID

向量库以切片主键存储。仅保持 `document_id` 稳定是不够的——
若切片对象以随机 ID 入库，重复摄取就退化为追加行为：同一段内容在库中存有多份，
既挤占 Top-K 名额，又会在引用清单中暴露重复项。

因此切片必须携带确定性 ID（由 `document_id + 序号` 派生），
且写入前按 `document_id` 清理旧向量，使重入语义为**覆盖**。

---

## 测试

```bash
mvn test
```

如需指定自定义 Maven settings（例如本地仓库位于非系统盘）：

```bash
mvn -s /path/to/settings.xml test
```

**测试构成**

| 测试类 | 覆盖内容 |
| --- | --- |
| `TextSplitterTest` | 切分不丢内容、不超长度、重叠窗口行为正确、非法入参被拒绝；含**递归终止性回归用例** |
| `CitationValidatorTest` | 低分引用被剔除、空片段不可用、最小引用数生效、`null` 入参安全 |
| `LocalProfileContractTest` | 零基础设施集成测试（内存向量库 + 确定性桩 Embedding），无需 Docker 与网络，守住「重复摄取不产生重复切片」「分数方向正确」两条契约 |
| `RagEndToEndTest` | Testcontainers 拉起真实 PGvector，跑通摄取与问答主链路；无 Docker 环境时**自动跳过**，不会使 `mvn test` 失败 |

当前结果：**18 项通过、4 项跳过**（共 22 项；跳过项为无 Docker 环境时的端到端用例）。

---

## 路线图

**Phase 1 —— 已完成**

文档 ETL、向量检索、引用溯源、幻觉阻断、REST API、Prometheus 指标。

**Phase 2 —— 规划中**

- [ ] Redis + Caffeine 二级缓存，复用相同问题的检索结果
- [ ] Grafana 大盘：延迟 P50/P95、拒答率、检索命中分布
- [ ] 文档增量更新与版本管理
- [ ] 多路召回（BM25 关键词 + 向量）与 RRF 融合排序
- [ ] 以 MCP Server 形式暴露知识库工具，支持外部 Agent 调用

---

## 贡献指南

欢迎提交 Issue 与 Pull Request。为便于审阅，请遵循以下约定。

### 分支与提交

- 从 `main` 切出特性分支：`feat/<简述>`、`fix/<简述>`、`docs/<简述>`；
- 提交信息遵循 [Conventional Commits](https://www.conventionalcommits.org/zh-hans/)，
  常用类型：`feat`、`fix`、`docs`、`test`、`chore`；
- 提交信息正文请说明**变更动机与影响范围**，而非仅描述改动内容。

### 代码约定

- 遵循既有的包结构与命名风格，新增能力请放入对应职责的包；
- 公开类型与关键方法需补充 Javadoc，说明**设计约束**（例如为何必须校验某条件）而非重复代码字面含义；
- 修改检索、切分、幂等相关逻辑时，**必须同步补充或更新测试**。

### 提交前自检

```bash
mvn test                       # 全部通过
./scripts/smoke-test.py        # 服务已启动时执行
```

### 变更需同步文档

以下变更必须同步更新文档，否则 PR 不予合并：

| 变更类型 | 需更新位置 |
| --- | --- |
| 新增或调整配置项 | 本文件的[配置说明](#配置说明) |
| 调整接口结构 | 本文件的[使用方法](#使用方法) 与 `docs/API.md` |
| 调整链路或分层 | `docs/ARCHITECTURE.md` 与本文件的[系统架构](#系统架构) |
| 调整阈值口径 | 本文件的[关键设计取舍](#关键设计取舍)，并附实测数据 |

---

## 许可证

本项目基于 [MIT License](LICENSE) 开源。

---

## 作者

**谢添臣** —— [GitHub](https://github.com/1240945123)

如本项目对你有帮助，欢迎 Star 支持。