# Playwright E2E 运行说明（M12.2）

## 本机一条命令

```bat
start\start-all.bat          :: 后端 8080 + 前端 5173（已起则跳过）
cd frontend && npm run test:e2e
```

- `playwright.config.ts` 的 webServer：8080 仅探活（reuseExistingServer），5173 没起会自动拉起 vite dev。
- 浏览器缓存目录：环境变量 `PLAYWRIGHT_BROWSERS_PATH=D:\项目\rely\ms-playwright`（已 setx 持久化，不落 C 盘）。

## 用例内容

`e2e/mainflow.spec.ts` 单条主链路（真实 AI 管道，约 2–4 分钟）：
注册 → 建库 → 传 md → 等 READY（解析/切片/向量化全走真接口）→ 提问（SSE 流式，答案含"8 秒"）
→ 引用点击开原文抽屉 → 点踩 + reload 验证 feedback 持久化 → 导出 Markdown 下载事件。

## 选择器策略（踩过的坑，改 UI 前必读）

1. **绑文案不绑 class**：按钮/表单 label/tab 文案就是产品契约（"新建知识库/保存/开始对话/发送/导出 Markdown"）。
2. **strict mode 冲突要限定作用域**：找回密码对话框（M11.3）含"密码"label，全局 getByLabel 命中 2 个 → 限定 `.el-form` / `.el-dialog:visible`。
3. **el-select multiple 点包装层**：readonly combobox 输入被 Playwright 判 "not stable"（光标微动宽度变），点 `.el-select` 外壳开下拉，选项用 `.el-select-dropdown__item hasText`。
4. **导航靠按钮**：点库名文本不触发路由，卡片内"文档"按钮才是入口（`.kbc hasText 库名` 防串卡片）。
5. **actionTimeout=15s**：选择器问题 15 秒暴露，不烧满 240s 用例预算。

## CI 状态（如实）

GitHub Actions 暂**不**跑本 E2E：runner 上需要起 compose 全栈 + 注入 AI key（私有仓库 secrets 未配），
且本机已验证通道不稳（docs/06 预警成真）。当前 CI 三层=密钥扫描 / mvn test / vitest+build。
若将来配好 `KB_AI_KEY` 等 secrets，job 模板：compose up -d → npm run test:e2e → 失败留 trace artifact。
