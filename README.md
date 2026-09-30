# Enterprise RAG Platform

企业级 AI 知识库与智能问答平台。基于 **Spring Boot 3 + Spring AI + PGvector** 构建，
覆盖企业知识助手的完整链路：**文档摄取 → 向量化 → 多知识库隔离检索 → 引用溯源 → 带依据生成 → 可观测性**。

> 设计重点不在「能不能调通大模型」，而在于三件企业场景真正关心的事：
> **答案必须有出处**（引用溯源）、**数据必须隔离**（多知识库租户）、**质量必须可度量**（埋点指标）。

---

## 为什么做这个

企业内部知识库落地的三个典型失败模式：

1. **答案没出处**——模型说得头头是道，但没人知道依据哪份文件，出错无法追责；
2. **知识串味**——不同部门文档混在一个索引里，财务问制度却检索到新员工手册；
3. **只有 QPS 没有质量**——监控只看吞吐延迟，答案质量退化无人察觉。

本项目针对这三点做了针对性设计（见下文章节）。

---

## 核心能力

| 能力 | 实现方式 |
| --- | --- |
| **文档 ETL** | 递归四级切分（空行 → 换行 → 中文句末标点 → 定长兜底）+ 重叠窗口，避免答案跨切片边界被截断；任一级切不开时必有兜底，保证终止 |
| **引用溯源** | 每个切片携带 `document_id / title / seq`，回答时回传完整引用清单与相似度分数 |
| **幻觉阻断** | 引用未达置信门槛时**直接拒答**，不调用大模型，从源头避免无依据输出 |
| **多知识库隔离** | 检索阶段用过滤表达式强制隔离，并对入参做单引号转义 |
| **可替换 Embedding** | 本地 ONNX（离线可用）与云端 Embedding API（OpenAI 兼容）一键切换 |
| **可观测性** | `kb.qa.latency` P50/P95 直方图 + 检索命中数 + 拒答计数，Prometheus 可直接抓取 |
| **幂等摄取** | 文档 ID 由「知识库 + 标题 + 正文」摘要生成；切片携带确定性 ID 且写入前按文档清旧向量，重复入库是**覆盖**而非追加 |

---

## 技术选型

| 层次 | 选型 | 选择理由 |
| --- | --- | --- |
| 应用框架 | Spring Boot 3.5 | Java 后端事实标准，生态成熟 |
| AI 框架 | Spring AI 1.1 | 统一 ChatModel / EmbeddingModel / VectorStore 抽象，换厂商不改业务代码 |
| 向量库 | PostgreSQL + PGvector | 结构化数据与向量同库，避免再引入一套分布式组件；HNSW 索引 |
| 本地向量化 | ONNX Runtime（`all-MiniLM-L6-v2`，384 维） | 无云端依赖也能跑通全链路，便于 CI 与离线演示 |
| 中文向量化 | DashScope `text-embedding-v3`（1024 维，OpenAI 兼容） | 中文语义区分度好，适合真实知识库；与 LLM 复用同一 Key |
| 大模型 | OpenAI 兼容协议 | DeepSeek / DashScope / OpenAI 均可，改两个环境变量即可切换 |
| 可观测性 | Micrometer + Prometheus | 指标格式标准，Grafana 直接可用 |
| 部署 | Docker Compose | 一条命令起 PGvector + 应用 |

**为什么用 PGvector 而不是专门的向量数据库？**
企业知识库本质是「结构化元数据 + 向量」的混合查询场景。把向量放进 PostgreSQL，
可以复用现成的备份、权限、审计与运维体系，不需要为一个功能再引入一套需要独立运维的组件。

---

## 目录结构

```
enterprise-rag-platform/
├── src/main/java/com/tianchen/kb/
│   ├── config/          # 配置属性绑定、本地向量库装配
│   ├── domain/          # 切片、引用、问答结果等领域模型
│   ├── ingest/          # 文档摄取：切分器 + 入库服务
│   ├── retrieval/       # 向量检索（含知识库隔离与相似度阈值裁剪）
│   ├── qa/              # RAG 主链路：引用核对 + 生成
│   ├── web/             # REST 控制器、DTO、统一异常处理
│   └── observability/   # 业务指标注册
├── src/main/resources/
│   ├── application.yml         # 主配置
│   └── application-local.yml   # local / pgvector 两套 profile
├── samples/             # 三份示例企业文档（报销、入职、数据安全）
├── sql/schema.sql       # PGvector 表结构与索引参考
├── docs/                # 架构说明与接口文档
├── scripts/             # 本地运行、样例导入、GitHub 推送
├── Dockerfile
└── docker-compose.yml
```

---

## 快速开始

```bash
git clone git@github.com:1240945123/enterprise-rag-platform.git
cd enterprise-rag-platform
```

### 方式 A：零基础设施（推荐先试这个）

不需要 Docker、不需要数据库、不需要云端 Key，**一条命令跑通完整链路**：

```bash
cp .env.example .env        # 按需修改 LLM_API_KEY
./scripts/run-local.sh      # Linux / macOS
# 或 PowerShell
.\scripts\run-local.ps1
```

### 方式 B：真实 PGvector 持久化

```bash
export SPRING_PROFILES_ACTIVE=pgvector
docker compose up -d
```

### 导入示例数据并提问

```bash
./scripts/load-samples.sh

curl -s -X POST http://localhost:8080/api/v1/qa/ask \
  -H 'Content-Type: application/json' \
  -d '{"knowledgeBaseId":"kb-corp","question":"一线城市住宿费标准是多少？","topK":3}'
```

返回示例：

```json
{
  "answer": "一线城市住宿费上限为每人每晚 600 元 [1]。发票抬头须与公司全称一致 [1]。",
  "citations": [
    { "documentId": "a1b2c3d4e5f60718", "title": "差旅费报销管理办法",
      "seq": 12, "snippet": "住宿费实行限额管理，一线城市每人每晚上限 600 元…", "score": 0.83 }
  ],
  "retrieved": 3,
  "grounded": true,
  "elapsedMillis": 1284
}
```

---

## REST API

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/api/v1/ingest` | 摄取文档，返回 `documentId` 与 `chunkCount` |
| `POST` | `/api/v1/qa/ask` | 带引用溯源的问答 |
| `GET` | `/actuator/health` | 健康检查 |
| `GET` | `/actuator/prometheus` | Prometheus 指标 |

详见 [`docs/API.md`](docs/API.md)。

---

## 关键设计取舍

**1. 为什么宁可拒答也不让模型硬答？**
企业知识场景下，一个没有出处的错误答案比「不知道」造成的代价大得多。
`CitationValidator` 在检索之后、生成之前设一道闸门：
引用数量或相似度不达标就返回明确的拒答说明，**连大模型都不调用**。
实测拒答耗时约 200~270ms，而正常作答约 1.1~1.6s，数量级差异印证了这条路径确实省掉了模型调用。

**2. 为什么相似度阈值可配而不是写死？**
不同 Embedding 模型的分数分布差异很大，绝对分数不可比。把
`kb.retrieval.min-score` 与 `kb.qa.min-citation-score` 暴露出来，
换模型时只需调参，不改代码。**但阈值必须自己实测标定**，本仓库的标定结果：

| 模型 | 相关问法 | 无关问法 | 结论 |
| --- | --- | --- | --- |
| ONNX `all-MiniLM-L6-v2` | ≈0.47 | ≈0.52 | 中文场景**几乎不可分**，说明该模型只能用于跑通链路 |
| DashScope `text-embedding-v3` | 0.60~0.80 | 0.38~0.41 | 可分性良好，取中点 **0.50** |

**3. 为什么切分器要手写而不直接用 TokenTextSplitter？**
需要控制三件事：① 中文按「句」而不是按 token 切；② 相邻切片保留可配置的重叠窗口；
③ 切分结果必须能被单元测试完整覆盖。手写切分器的核心风险是**递归不终止**——
若某级分隔符不存在却仍下钻，就会无限递归；因此每一级必须校验「确实切得开」，
并保留定长硬切作为最终兜底。`TextSplitterTest` 用「无空行、无标点的超长文本」
专门守住这条边界。

**4. 为什么重复摄取需要「删除后重写」而不是只比较文档 ID？**
向量库按切片主键存储。仅让 `document_id` 保持稳定是不够的，
若切片对象用随机 ID 入库，重复摄取就变成追加：同一段内容在库里存了多份，
挤占 Top-K 名额，并在引用清单里暴露重复项。
因此切片必须携带确定性 ID，且写入前按 `document_id` 清理旧向量，使重入语义是**覆盖**。

---

## 测试

```bash
./mvnw test          # 或：mvn -s <settings> test
```

- `TextSplitterTest` —— 切分不丢内容、不超长度、overlap 行为正确、非法入参被拒；
  含**递归终止性回归用例**（无空行/无标点的超长文本）
- `CitationValidatorTest` —— 低分引用被剔除、空片段不可用、最小引用数生效、null 入参安全
- `LocalProfileContractTest` —— 零基础设施集成测试（内存向量库 + 确定性桩 Embedding），
  不需 Docker/网络，守住「重复摄取不产生重复切片」「分数方向正确」两条契约
- `RagEndToEndTest` —— Testcontainers 拉起真实 PGvector，跑通摄取与问答主链路（需 Docker，
  无 Docker 环境自动跳过，不会把 `mvn test` 染红）

另有端到端冒烟脚本，覆盖健康检查 → 摄取/幂等 → 长文切分 → 带引用问答 → 幻觉阻断 → 隔离 → 指标：

```bash
python scripts/smoke-test.py --base-url http://127.0.0.1:8080
```

---

## 路线图

已完成（Phase 1）：ETL、向量检索、引用溯源、幻觉阻断、REST API、Prometheus 指标。

下一步（Phase 2）：

- [ ] Redis + Caffeine 二级缓存，缓存同一问题的检索结果
- [ ] Grafana 大盘：延迟 P50/P95、拒答率、检索命中分布
- [ ] 文档增量更新与版本管理
- [ ] 多路召回（关键词 BM25 + 向量）与 RRF 融合排序
- [ ] MCP Server 工具暴露，支持外部 Agent 调用

---

## 许可

MIT
