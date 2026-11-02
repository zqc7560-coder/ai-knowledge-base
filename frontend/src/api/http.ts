import axios, { type AxiosError } from "axios";
import { ElMessage } from "element-plus";
import { useUserStore } from "../stores/user";

/**
 * 统一请求层：只与本系统 /api 通信（密钥红线 L3：前端代码不存在任何 AI 上游地址）。
 * 拦截器拆信封：code===0 直接返回 data；401 跳登录。
 */
export const http = axios.create({ baseURL: "/api", timeout: 60000 });

http.interceptors.request.use((cfg) => {
  const store = useUserStore();
  if (store.token) cfg.headers.Authorization = store.token;
  return cfg;
});

http.interceptors.response.use(
  (resp) => {
    const body = resp.data;
    if (body && typeof body === "object" && "code" in body) {
      if (body.code === 0) return body.data;
      ElMessage.error(body.message || "请求失败");
      return Promise.reject(new Error(body.message));
    }
    return body;
  },
  (err: AxiosError<{ message?: string; code?: number }>) => {
    const code = err.response?.status;
    if (code === 401) {
      const store = useUserStore();
      store.clear();
      window.location.hash = "#/login";
      ElMessage.warning("请先登录");
    } else {
      ElMessage.error(err.response?.data?.message || "网络异常");
    }
    return Promise.reject(err);
  },
);
