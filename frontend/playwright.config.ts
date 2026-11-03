import { defineConfig } from "@playwright/test";

// Playwright E2E（M12.2）：本机形态对 dev 栈（vite 5173 + 后端 8080）跑主链路。
// webServer 自动拉起并等就绪：5173 已在跑则复用（reuseExistingServer），避免和 start-all.bat 打架。
export default defineConfig({
  testDir: "./e2e",
  timeout: 240_000,            // 单测含真实解析/向量化/问答，预算给足
  workers: 1,                  // 共享后端与限流窗口，串行防互踩
  fullyParallel: false,
  reporter: [["list"]],
  use: {
    baseURL: "http://127.0.0.1:5173",
    trace: "retain-on-failure",
    actionTimeout: 15_000,      // 单动作 15s 上限：选择器问题快速暴露，不烧满用例预算
  },
  webServer: [
    {
      // 后端：宿主 8080（Playwright 不管它——由 start-all.bat 或 CI 的 compose 起；
      // 本机跑 E2E 前确保后端已健康，见 README-e2e 说明）
      command: "echo kb-backend-assumed-running",
      url: "http://127.0.0.1:8080/api/ping",
      reuseExistingServer: true,
      timeout: 15_000,
    },
    {
      // 前端：没起就用 dev server 拉起（vite 代理 /api→8080）
      command: "npm run dev -- --port 5173 --host 127.0.0.1",
      url: "http://127.0.0.1:5173",
      reuseExistingServer: true,
      timeout: 60_000,
    },
  ],
});
