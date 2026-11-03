import { test, expect } from "@playwright/test";
import fs from "fs";
import path from "path";

/**
 * 主链路 E2E（M12.2）——docs/06 验收口径：
 * 注册 → 建库 → 传 md → 等 READY → 提问 → 引用抽屉 → 点踩 → 导出。
 * 选择器全部对照真实组件模板（ChatView/KbListView/DocListView/LoginView），
 * Element-Plus 的 role 可达性靠 label/tab/按钮文案——改文案需要同步这里，是有意为之的耦合
 * （文案即产品契约，UI 测试就该绑定它；比绑定 class 名稳定）。
 */
const SUF = Date.now().toString(36);
const USER = `e2e_${SUF}`;
const PASS = "e2e-pass-123";
const KBNAME = `E2E库${SUF}`;

test("主链路：注册→建库→传文档→READY→提问→引用→点踩→导出", async ({ page }) => {
  // ---- 注册 ----
  await page.goto("/login");
  await page.getByRole("tab", { name: "注册" }).click();
  // 限定 el-form 作用域：找回密码对话框(M11.3)也含"密码"语义元素，全局 getByLabel 会 strict 冲突
  const form = page.locator(".el-form");
  await form.getByLabel("用户名").fill(USER);
  await form.getByLabel("邮箱").fill(`${USER}@t.local`);
  await form.getByLabel("密码").fill(PASS);
  await page.getByRole("button", { name: "注 册" }).click();
  await expect(page.getByRole("button", { name: "新建知识库" })).toBeVisible({ timeout: 20_000 });

  // ---- 建库 ----
  await page.getByRole("button", { name: "新建知识库" }).click();
  const kbd = page.locator(".el-dialog:visible");
  await kbd.getByLabel("名称").fill(KBNAME);
  await kbd.getByRole("button", { name: "保存" }).click();
  await expect(page.getByText(KBNAME)).toBeVisible({ timeout: 15_000 });

  // ---- 进库传 md：导航靠卡片内"文档"按钮（点库名文本不触发路由）；限定到含本库名的卡片防多卡片串按钮 ----
  await page.locator(".kbc", { hasText: KBNAME }).getByRole("button", { name: "文档" }).click();
  await expect(page.getByText(/拖拽或点击上传/)).toBeVisible({ timeout: 15_000 });
  const tmp = path.join(process.cwd(), "e2e-doc.md");
  fs.writeFileSync(tmp,
    "# XG-200 网关部署手册\n\n## 复位\n长按复位键 8 秒，指示灯三闪后松开，恢复出厂设置。\n\n## 默认地址\n管理后台默认 IP 是 192.168.8.100，默认口令 admin/admin123。\n",
    "utf-8");
  await page.locator("input[type=file]").setInputFiles(tmp);
  // 真实管道：解析→切片→向量化，等状态标签 READY（EMBEDDING 时组件自带轮询刷新）
  await expect(page.getByText("READY", { exact: true }).first()).toBeVisible({ timeout: 180_000 });

  // ---- 问答 ----
  await page.getByRole("button", { name: "对话" }).click();
  await page.getByRole("button", { name: "新对话" }).click();
  const dlg = page.locator(".el-dialog:visible");
  // el-select multiple 的 readonly combobox 输入会被判 not stable（宽度随光标微变），
  // 点 .el-select 包装层同样打开下拉——Element Plus 通用解法
  await dlg.locator(".el-select").click();
  await page.locator(".el-select-dropdown:visible .el-select-dropdown__item", { hasText: KBNAME }).click();
  await page.keyboard.press("Escape");   // 关多选下拉
  await dlg.getByRole("button", { name: "开始对话" }).click();

  await page.locator('textarea').last().fill("复位网关要长按几秒？");
  await page.getByRole("button", { name: "发送" }).click();
  await expect(page.getByText(/8\s*秒/).first()).toBeVisible({ timeout: 150_000 });

  // ---- 引用 → 原文预览抽屉 ----
  const link = page.locator(".cite-link").first();
  await expect(link).toBeVisible({ timeout: 10_000 });
  await link.click();
  await expect(page.getByText(/原文预览/)).toBeVisible({ timeout: 10_000 });
  await expect(page.locator(".el-drawer:visible").getByText(/复位键/)).toBeVisible({ timeout: 10_000 });
  await page.keyboard.press("Escape");

  // ---- 点踩（落库）→ 持久化验证 ----
  // 产品约束（ChatView.feedback 的 !m.id 分支）：刚流式生成的消息没有 id，
  // 必须 reload 走历史回放拿到 id 才能评价——E2E 按真实用户动线：
  // reload → 点踩（.bad 类出现）→ 再 reload（feedback 从后端读出，仍 .bad）
  await page.reload();
  await page.locator(".conv").first().click({ timeout: 15_000 });
  await expect(page.getByText(/复位/).first()).toBeVisible({ timeout: 20_000 });
  await page.locator('span.fb', { hasText: "👎" }).last().click();
  await expect(page.locator(".fb.bad").first()).toBeVisible({ timeout: 10_000 });
  await page.reload();
  await page.locator(".conv").first().click({ timeout: 15_000 });
  // 历史回放里 feedback 仍在（M6 承诺：messages.feedback 列持久化）
  await expect(page.locator(".fb.bad").first()).toBeVisible({ timeout: 15_000 });

  // ---- 导出 Markdown ----
  const dl = page.waitForEvent("download", { timeout: 15_000 });
  await page.getByRole("button", { name: "导出 Markdown" }).click();
  const file = await dl;
  expect(file.suggestedFilename()).toMatch(/对话导出.*\.md/);

  fs.unlinkSync(tmp);
});
