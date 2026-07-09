"""
LightRAG 服务 — FastAPI HTTP 服务

集成 LightRAG (https://github.com/HKUDS/LightRAG) 作为可选的 Graph RAG 检索后端。
提供 /insert（建图）和 /query（检索问答）两个端点。

启动方式：
    python server.py
    或
    uvicorn server:app --host 0.0.0.0 --port 8021

环境变量：
    DASHSCOPE_API_KEY  — DashScope API 密钥（必填）
    WORK_DIR           — LightRAG 数据目录（可选，默认 ./data）
"""

import os
import logging

import uvicorn
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field

from lightrag import LightRAG
from lightrag.llm.openai import openai_complete_if_cache, openai_embed

from config import (
    LLM_CONFIG,
    EMBEDDING_CONFIG,
    SERVICE_CONFIG,
    DEFAULT_QUERY_MODE,
)

# ── 日志 ──────────────────────────────────────────────

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
)
log = logging.getLogger("lightrag_service")

# ── 模型函数 ──────────────────────────────────────────

API_KEY = os.environ.get("DASHSCOPE_API_KEY")
if not API_KEY:
    raise RuntimeError("环境变量 DASHSCOPE_API_KEY 未设置")


async def llm_model_func(
    prompt: str,
    system_prompt: str | None = None,
    history_messages: list | None = None,
    **kwargs,
) -> str:
    """DashScope LLM 调用函数（兼容 LightRAG 接口）"""
    return await openai_complete_if_cache(
        model=LLM_CONFIG["model"],
        prompt=prompt,
        system_prompt=system_prompt,
        history_messages=history_messages,
        base_url=LLM_CONFIG["api_base"],
        api_key=API_KEY,
        temperature=kwargs.get("temperature", LLM_CONFIG["temperature"]),
        max_tokens=kwargs.get("max_tokens", LLM_CONFIG["max_tokens"]),
    )


async def embedding_func(texts: list[str]) -> list[list[float]]:
    """DashScope Embedding 调用函数（兼容 LightRAG 接口）"""
    return await openai_embed(
        texts=texts,
        model=EMBEDDING_CONFIG["model"],
        base_url=EMBEDDING_CONFIG["api_base"],
        api_key=API_KEY,
    )


# ── FastAPI 应用 ──────────────────────────────────────

app = FastAPI(
    title="PaperBot LightRAG 服务",
    description="Graph RAG 后端，支持论文实体关系建图和检索问答",
    version="1.0.0",
)

rag: LightRAG | None = None


@app.on_event("startup")
async def startup():
    global rag
    work_dir = os.environ.get("WORK_DIR", SERVICE_CONFIG["working_dir"])
    os.makedirs(work_dir, exist_ok=True)

    rag = LightRAG(
        working_dir=work_dir,
        llm_model_func=llm_model_func,
        embedding_func=embedding_func,
    )
    log.info("LightRAG 初始化完成, working_dir=%s, llm=%s, emb=%s",
             work_dir, LLM_CONFIG["model"], EMBEDDING_CONFIG["model"])


# ── 请求/响应模型 ────────────────────────────────────

class InsertRequest(BaseModel):
    text: str = Field(..., description="要插入的文档文本")
    chunk: bool = Field(default=True, description="是否对文本分块后再插入")


class InsertResponse(BaseModel):
    status: str


class QueryRequest(BaseModel):
    question: str = Field(..., description="用户问题")
    mode: str = Field(default=DEFAULT_QUERY_MODE, description="检索模式: local / global / hybrid / naive / mix")


class QueryResponse(BaseModel):
    answer: str


# ── 端点 ──────────────────────────────────────────────


@app.post("/insert")
async def insert(req: InsertRequest) -> InsertResponse:
    """插入文档文本，LightRAG 自动提取实体和关系构建知识图谱"""
    if rag is None:
        raise HTTPException(status_code=503, detail="LightRAG 未初始化")

    text = req.text.strip()
    if not text:
        raise HTTPException(status_code=400, detail="文本内容为空")

    log.info("插入文档: %d 字符, chunk=%s", len(text), req.chunk)
    await rag.ainsert(text, chunk=req.chunk)
    log.info("插入完成")
    return InsertResponse(status="ok")


@app.post("/query")
async def query(req: QueryRequest) -> QueryResponse:
    """检索并回答用户问题（基于知识图谱的 Graph RAG）"""
    if rag is None:
        raise HTTPException(status_code=503, detail="LightRAG 未初始化")

    question = req.question.strip()
    if not question:
        raise HTTPException(status_code=400, detail="问题内容为空")

    valid_modes = {"local", "global", "hybrid", "naive", "mix"}
    if req.mode not in valid_modes:
        raise HTTPException(status_code=400, detail=f"mode 必须是 {', '.join(sorted(valid_modes))}")

    log.info("查询: mode=%s, question=%s", req.mode, truncate(question, 80))
    answer = await rag.aquery(question, mode=req.mode)
    log.info("查询完成, 回答长度: %d 字符", len(answer))
    return QueryResponse(answer=answer)


# ── 健康检查 ──────────────────────────────────────────


@app.get("/health")
async def health():
    return {"status": "ok", "rag_initialized": rag is not None}


# ── 工具函数 ──────────────────────────────────────────


def truncate(s: str, max_len: int) -> str:
    if len(s) <= max_len:
        return s
    return s[:max_len] + "..."


# ── 入口 ──────────────────────────────────────────────

if __name__ == "__main__":
    uvicorn.run(
        "server:app",
        host=SERVICE_CONFIG["host"],
        port=SERVICE_CONFIG["port"],
        reload=False,
    )
