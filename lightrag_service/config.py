"""LightRAG 服务配置 — 统一管理 LLM 和 Embedding 模型参数"""

# ===================== LLM 配置 =====================

# 使用 DashScope (通义千问) 作为 LLM 后端
# DashScope API 与 OpenAI API 格式兼容，可通过 openai SDK 调用
DASHSCOPE_API_KEY = None  # 运行时通过环境变量 DASHSCOPE_API_KEY 注入

LLM_CONFIG = {
    "model": "qwen3-max",           # 用于提取实体/关系的 LLM
    "api_base": "https://dashscope.aliyuncs.com/compatible-mode/v1",
    "api_key": DASHSCOPE_API_KEY,
    "temperature": 0.1,             # 提取实体要求精确，低 temperature
    "max_tokens": 4096,
}

# ===================== Embedding 配置 =====================

# 使用 DashScope text-embedding-v4 作为 embedding 模型
# 与 PaperBot 主服务使用相同的 embedding 模型
EMBEDDING_CONFIG = {
    "model": "text-embedding-v4",   # DashScope Embedding 模型
    "api_key": DASHSCOPE_API_KEY,
    "api_base": "https://dashscope.aliyuncs.com/compatible-mode/v1",
    "dimension": 1024,              # text-embedding-v4 输出 1024 维
}

# ===================== 服务配置 =====================

SERVICE_CONFIG = {
    "host": "0.0.0.0",
    "port": 8021,
    "working_dir": "./data",        # LightRAG 数据存储目录
}

# ===================== 检索模式 =====================

# mode: local / global / hybrid / naive / mix
# local  — 仅实体搜索，适合单篇论文精确问答
# global — 仅关系搜索，适合多篇论文综合分析
# hybrid — 实体+关系融合，综合效果较好
# naive  — 传统向量检索，不做图搜索
# mix    — hybrid + naive 混合，默认最佳
DEFAULT_QUERY_MODE = "mix"
