# AI 智能知识库管理系统（Knowledge Base RAG）

简化版 FastGPT / Dify：上传 PDF / Word / TXT / Markdown 构建个人知识库，AI 基于 RAG 检索增强回答问题。

**技术栈**：Vue 3 + Spring Boot 3 + MySQL 8 + Redis 7 + pgvector（可切 Milvus）+ LangChain4j + 阿里云百炼 DashScope。

## 文档
| 文件 | 内容 |
|---|---|
| [docs/01-项目总体计划.md](docs/01-项目总体计划.md) | 需求、架构、数据模型、API 设计、AI 技术方案、风险 |
| [docs/02-开发计划.md](docs/02-开发计划.md) | M0–M6 里程碑 WBS、验收标准、测试策略 |
| [docs/03-AI密钥安全规范.md](docs/03-AI密钥安全规范.md) | **强制红线**：AI 密钥如何保证永不被用户/前端/仓库/日志看到 |

## ⚠️ 安全须知
AI API 密钥**只允许**存在于服务端环境变量中（本地开发放 `backend/.env`，已被 `.gitignore` 排除）。
禁止写入前端代码、Dockerfile、compose 明文、git 历史、日志。提交前 pre-commit + gitleaks 会拦截。
仓库初始化时请把 `.env.example` 复制为 `backend/.env` 并填入真实值（不要提交）。

## 快速开始（M0 完成后适用）
```bash
cp .env.example backend/.env      # 填 DB 与 AI_API_KEY
cd deploy && docker compose up -d # mysql / redis / pgvector
cd backend && ./mvnw spring-boot:run
cd frontend && npm i && npm run dev
```

## 目录结构
```
ai-knowledge-base/
├── docs/       # 计划与设计文档
├── backend/    # Spring Boot 多模块（kb-common…kb-admin）
├── frontend/   # Vue3 + Vite
├── sql/        # Flyway 迁移脚本
├── deploy/     # docker-compose、nginx、备份脚本
└── scripts/    # 密钥扫描、评估集跑分
```
