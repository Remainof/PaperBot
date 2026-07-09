#!/usr/bin/env bash
# LightRAG 服务启动脚本
# 用法: ./start.sh [DASHSCOPE_API_KEY]
set -euo pipefail

cd "$(dirname "$0")"

if [ -n "${1:-}" ]; then
    export DASHSCOPE_API_KEY="$1"
fi

if [ -z "${DASHSCOPE_API_KEY:-}" ]; then
    echo "❌ 请设置 DASHSCOPE_API_KEY 环境变量"
    echo "   用法: ./start.sh sk-xxx"
    echo "   或:   export DASHSCOPE_API_KEY=sk-xxx && ./start.sh"
    exit 1
fi

source .venv/bin/activate
echo "🚀 启动 LightRAG 服务 (http://0.0.0.0:8021)"
python server.py
