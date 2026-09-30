# API 文档

所有请求与响应均为 JSON（`Content-Type: application/json`），基础路径 `http://localhost:8080`。

---

## POST /api/v1/ingest

摄取一篇文档：切分 → 向量化 → 写入指定知识库。

**请求体**

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `knowledgeBaseId` | string | 是 | 知识库标识，用于检索隔离 |
| `title` | string | 否 | 文档标题，缺省为 `untitled`，用于引用展示 |
| `content` | string | 是 | 文档正文（UTF-8） |

```json
{
  "knowledgeBaseId": "kb-corp",
  "title": "差旅费报销管理办法",
  "content": "住宿费实行限额管理，一线城市每人每晚上限 600 元……"
}
```

**成功响应 200**

```json
{
  "documentId": "a1b2c3d4e5f60718",
  "chunkCount": 12,
  "createdAt": "2026-09-29T15:20:31.482Z"
}
```

**幂等性**：相同 `knowledgeBaseId` + `title` + `content` 重复提交，`documentId` 不变，
且**不会产生重复向量**——服务会先按该文档清掉旧切片再写入，重入语义是覆盖而非追加。

**失败响应 400**

```json
{ "error": "BAD_REQUEST", "message": "content must not be blank", "status": 400 }
```

---

## POST /api/v1/qa/ask

带引用溯源的问答。

**请求体**

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `knowledgeBaseId` | string | 是 | 限定检索范围，强制隔离 |
| `question` | string | 是 | 问题 |
| `topK` | int | 否 | 覆盖默认 Top-K（`kb.retrieval.top-k`） |

```json
{ "knowledgeBaseId": "kb-corp", "question": "一线城市住宿费标准是多少？", "topK": 3 }
```

**成功响应 200**

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `answer` | string | 答案；未落地时为拒答说明 |
| `citations` | array | 引用清单，见下 |
| `retrieved` | int | 召回数量（校验前） |
| `grounded` | boolean | 是否有引用支撑；`false` 表示已拒答 |
| `elapsedMillis` | long | 端到端耗时 |
| `knowledgeBaseId` / `question` | string | 回显请求参数 |

`citations[]` 结构：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `documentId` | string | 来源文档 ID |
| `title` | string | 来源文档标题 |
| `seq` | int | 切片序号，可回溯定位原文位置 |
| `snippet` | string | 切片正文 |
| `score` | double | **相似度**，0~1，越大越相关（由向量库分数归一化而来，非距离） |

```json
{
  "answer": "一线城市住宿费上限为每人每晚 600 元 [1]。",
  "citations": [
    { "documentId": "a1b2c3d4e5f60718", "title": "差旅费报销管理办法",
      "seq": 12, "snippet": "住宿费实行限额管理，一线城市每人每晚上限 600 元…", "score": 0.83 }
  ],
  "retrieved": 3,
  "grounded": true,
  "elapsedMillis": 1284,
  "knowledgeBaseId": "kb-corp",
  "question": "一线城市住宿费标准是多少？"
}
```

**拒答示例**（知识库中没有相关资料）

```json
{
  "answer": "未在当前知识库中检索到足够支撑该问题的资料，无法给出有依据的回答。请补充相关文档后重试。",
  "citations": [],
  "retrieved": 0,
  "grounded": false,
  "elapsedMillis": 42
}
```

---

## GET /actuator/health

健康检查。

```json
{ "status": "UP" }
```

## GET /actuator/prometheus

Prometheus 抓取端点。核心指标：

```
kb_qa_latency_seconds{application="enterprise-rag-platform",quantile="0.95"}
kb_qa_answers_total{application="enterprise-rag-platform"}
kb_qa_rejections_total{application="enterprise-rag-platform"}
```

`answers_total` 为有引用支撑并**真正调用大模型**作答的次数，`rejections_total` 为
引用不足被拒答的次数（该路径不调用大模型，耗时会明显更低）。
两者之和即总请求量，`rejections / (answers + rejections)` 即拒答率。

---

## 错误码约定

| HTTP | error | 场景 |
| --- | --- | --- |
| 400 | `BAD_REQUEST` | 业务参数非法（如空的 content） |
| 400 | `VALIDATION_FAILED` | Bean Validation 校验失败 |
| 500 | `INTERNAL_ERROR` | 未预期异常，详情见服务端日志 |
