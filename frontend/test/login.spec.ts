import { describe, it, expect, vi, beforeEach } from "vitest";
import { mount, flushPromises } from "@vue/test-utils";
import { createPinia, setActivePinia } from "pinia";
import ElementPlus from "element-plus";

// mock router：记录 push 目标。useRoute 是 M10.5 登录回跳(query.redirect)引入的，
// 组件 setup 里会调用，mock 必须一并导出，否则 vitest 报 "No useRoute export"。
const pushSpy = vi.fn();
vi.mock("vue-router", () => ({
  useRouter: () => ({ push: pushSpy }),
  useRoute: () => ({ query: {}, params: {}, path: "/login", fullPath: "/login" }),
}));

// mock http：登录成功返回假 token
const postMock = vi.fn();
const getMock = vi.fn();
vi.mock("../src/api/http", () => ({
  http: {
    post: (...a: unknown[]) => postMock(...a),
    get: (...a: unknown[]) => getMock(...a),
    put: vi.fn(),
    delete: vi.fn(),
  },
}));

import LoginView from "../src/views/LoginView.vue";
import { useUserStore } from "../src/stores/user";

describe("LoginView", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    pushSpy.mockReset();
    postMock.mockReset();
    sessionStorage.clear();
  });

  it("短密码直接拦截，不发请求", async () => {
    const w = mount(LoginView, { global: { plugins: [ElementPlus] } });
    const inputs = w.findAll("input");
    await inputs[0].setValue("someone");
    await inputs[1].setValue("123"); // <8
    await w.find("button").trigger("click");
    await flushPromises();
    expect(postMock).not.toHaveBeenCalled();
  });

  it("登录成功 -> token 入 store -> 跳首页", async () => {
    postMock.mockResolvedValue({ token: "tk-abc" });
    getMock.mockResolvedValue({ id: 1, username: "someone", email: "x@y.z", role: "USER" });
    const w = mount(LoginView, { global: { plugins: [ElementPlus] } });
    const inputs = w.findAll("input");
    await inputs[0].setValue("someone");
    await inputs[1].setValue("goodpass123");
    await w.find("button").trigger("click");
    await flushPromises();
    expect(postMock).toHaveBeenCalledWith("/auth/login", { username: "someone", password: "goodpass123" });
    const store = useUserStore();
    expect(store.token).toBe("tk-abc");
    expect(pushSpy).toHaveBeenCalledWith("/");
  });

  it("注册标签 -> 提交 /auth/register 带 email", async () => {
    postMock.mockResolvedValue({ token: "tk-reg" });
    getMock.mockResolvedValue({ id: 2, username: "newbie", email: "n@n.n", role: "USER" });
    const w = mount(LoginView, { global: { plugins: [ElementPlus] } });
    // 切到注册 tab
    const tabs = w.findAll('[role="tab"]');
    await tabs[1].trigger("click");
    await flushPromises();
    const inputs = w.findAll("input");
    await inputs[0].setValue("newbie");
    await inputs[1].setValue("n@n.n");
    await inputs[2].setValue("goodpass123");
    await w.find("button").trigger("click");
    await flushPromises();
    expect(postMock).toHaveBeenCalledWith("/auth/register", {
      username: "newbie", email: "n@n.n", password: "goodpass123",
    });
    expect(useUserStore().token).toBe("tk-reg");
  });
});
