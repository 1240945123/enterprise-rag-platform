#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
端到端冒烟测试：不依赖第三方库（仅需 Python 3.8+）。

用法（先确保服务已启动）：
    python scripts/smoke-test.py                        # 默认 http://127.0.0.1:8080
    python scripts/smoke-test.py --base-url http://127.0.0.1:9090

覆盖链路：
    健康检查 -> 摄取样例文档 -> 带引用问答 -> 知识库隔离 -> 幻觉阻断拒答
    -> 幂等摄取 -> Prometheus 指标
"""
import argparse
import json
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SAMPLES = ROOT / "samples"


class Smoke:
    def __init__(self, base_url: str, kb: str, timeout: int):
        self.base = base_url.rstrip("/")
        self.kb = kb
        self.timeout = timeout
        self.results = []

    # ---------- HTTP helpers ----------
    def get(self, path):
        with urllib.request.urlopen(self.base + path, timeout=self.timeout) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8"))

    def post(self, path, payload):
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        req = urllib.request.Request(
            self.base + path,
            data=data,
            headers={"Content-Type": "application/json; charset=utf-8"},
            method="POST",
        )
        with urllib.request.urlopen(req, timeout=self.timeout) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8"))

    # ---------- assertions ----------
    def check(self, name, ok, detail=""):
        self.results.append((name, bool(ok), detail))
        print(f"[{'PASS' if ok else 'FAIL'}] {name}" + (f" - {detail}" if detail else ""))
        return ok

    # ---------- steps ----------
    def health(self):
        status, body = self.get("/actuator/health")
        self.check("健康检查", status == 200 and body.get("status") == "UP", f"status={body.get('status')}")

    def ingest_samples(self):
        if not SAMPLES.is_dir():
            self.check("样例目录存在", False, str(SAMPLES))
            return
        for doc in sorted(SAMPLES.glob("*.md")):
            with open(doc, encoding="utf-8") as f:
                content = f.read()
            _, body = self.post("/api/v1/ingest", {
                "knowledgeBaseId": self.kb,
                "title": doc.name,
                "content": content,
            })
            self.check(f"摄取《{doc.name}》",
                       body.get("chunkCount", 0) > 0,
                       f"chunks={body.get('chunkCount')} id={body.get('documentId', '')[:8]}")
            # 幂等性：再摄取一次，documentId 应保持一致
            _, again = self.post("/api/v1/ingest", {
                "knowledgeBaseId": self.kb,
                "title": doc.name,
                "content": content,
            })
            self.check(f"幂等摄取《{doc.name}》",
                       again.get("documentId") == body.get("documentId"),
                       "重复入库得到同一 documentId 表示幂等")

    def ask(self, question, top_k=3, expect_grounded=True, kb=None, label=None):
        kb = kb or self.kb
        _, body = self.post("/api/v1/qa/ask", {
            "knowledgeBaseId": kb,
            "question": question,
            "topK": top_k,
        })
        grounded = bool(body.get("grounded"))
        name = label or f"问答「{question}」"
        detail = (f"grounded={grounded} citations={len(body.get('citations', []))} "
                  f"latency={body.get('elapsedMillis')}ms")
        if expect_grounded:
            ok = grounded and len(body.get("citations", [])) > 0
        else:
            ok = not grounded
        self.check(name, ok, detail)
        if ok and expect_grounded:
            answer = (body.get("answer") or "").replace("\n", " ")
            print(f"       答案：{answer}")
            first = (body.get("citations") or [{}])[0]
            print(f"       引用：[1] {first.get('title')} seq={first.get('seq')} score={first.get('score')}")
        return body

    def long_document_split(self):
        """拼接三份样例形成长文，验证递归切分确实产生多个分片。"""
        parts = []
        for doc in sorted(SAMPLES.glob("*.md")):
            with open(doc, encoding="utf-8") as f:
                parts.append(f.read())
        merged = "\n\n".join(parts)
        _, body = self.post("/api/v1/ingest", {
            "knowledgeBaseId": self.kb,
            "title": "企业制度合集.md",
            "content": merged,
        })
        self.check("长文档递归切分（应产生多个分片）",
                   body.get("chunkCount", 0) >= 2,
                   f"长度={len(merged)} chunks={body.get('chunkCount')}")

    def isolation(self):
        other = "kb-hr-confidential"
        with open(SAMPLES / "新员工入职指引.md", encoding="utf-8") as f:
            content = f.read()
        self.post("/api/v1/ingest", {"knowledgeBaseId": other, "title": "新员工入职指引.md", "content": content})
        self.ask("一线城市住宿费标准是多少？", kb=other, expect_grounded=False,
                 label="知识库隔离（非目标库应拒答）")

    def metrics(self):
        with urllib.request.urlopen(self.base + "/actuator/prometheus", timeout=self.timeout) as resp:
            text = resp.read().decode("utf-8")
        answers = self._counter(text, "kb_qa_answers_total")
        rejects = self._counter(text, "kb_qa_rejections_total")
        has_latency = "kb_qa_latency_seconds_bucket" in text
        self.check("Prometheus 指标（kb_qa_answers_total>0）", answers > 0, f"answers={answers}")
        self.check("Prometheus 指标（kb_qa_rejections_total>0）", rejects > 0, f"rejections={rejects}")
        self.check("Prometheus 指标（延迟直方图）", has_latency)

    @staticmethod
    def _counter(text, name):
        for line in text.splitlines():
            if line.startswith(name + "{") or line.startswith(name + " "):
                try:
                    return float(line.rsplit(" ", 1)[-1])
                except ValueError:
                    return 0.0
        return 0.0

    def summary(self):
        total = len(self.results)
        passed = sum(1 for _, ok, _ in self.results if ok)
        print("\n" + "=" * 56)
        print(f"冒烟测试：{passed}/{total} 通过")
        print("=" * 56)
        for name, ok, detail in self.results:
            if not ok:
                print(f"  失败项：{name} - {detail}")
        return 0 if passed == total else 1


def wait_for_ready(base_url, timeout=120):
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            with urllib.request.urlopen(base_url + "/actuator/health", timeout=3) as resp:
                body = json.loads(resp.read().decode("utf-8"))
                if body.get("status") in ("UP", "DOWN"):  # DOWN 也说明 HTTP 已就绪
                    return body
        except Exception:
            time.sleep(2)
    return None


def main():
    ap = argparse.ArgumentParser(description="企业知识库端到端冒烟测试")
    ap.add_argument("--base-url", default="http://127.0.0.1:8080")
    ap.add_argument("--kb", default="kb-corp")
    ap.add_argument("--timeout", type=int, default=60, help="单次请求超时（秒）")
    ap.add_argument("--wait-startup", type=int, default=0, help="等待服务启动的最长秒数，0=不等待")
    args = ap.parse_args()

    if args.wait_startup:
        print(f"等待服务就绪（最多 {args.wait_startup}s）…")
        if not wait_for_ready(args.base_url, args.wait_startup):
            print("服务未就绪，退出。", file=sys.stderr)
            return 2

    smoke = Smoke(args.base_url, args.kb, args.timeout)
    smoke.health()
    smoke.ingest_samples()
    smoke.long_document_split()
    smoke.ask("一线城市住宿费标准是多少？", expect_grounded=True)
    smoke.ask("公司 2027 年度营收目标是多少？", expect_grounded=False,
              label="幻觉阻断（知识库中无依据时应拒答）")
    smoke.isolation()
    smoke.metrics()
    return smoke.summary()


if __name__ == "__main__":
    sys.exit(main())
