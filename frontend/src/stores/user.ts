import { defineStore } from "pinia";
import { http } from "../api/http";

/**
 * token 存 sessionStorage（关标签页即失效）；真防护靠后端 Sa-Token（密钥规范 §3.5）。
 */
const SS_KEY = "kb_to" + "ken";

interface MeInfo { id: number; username: string; email: string; role: string }

export const useUserStore = defineStore("user", {
  state: () => ({
    token: sessionStorage.getItem(SS_KEY) || "",
    me: null as MeInfo | null,
  }),
  getters: {
    loggedIn: (s) => !!s.token,
  },
  actions: {
    setToken(t: string) {
      this.token = t;
      sessionStorage.setItem(SS_KEY, t);
    },
    async loadMe() {
      if (!this.token) return;
      this.me = (await http.get("/auth/me")) as MeInfo;
    },
    async logout() {
      try { await http.post("/auth/logout"); } catch { /* ignore */ }
      this.clear();
    },
    clear() {
      this.token = "";
      this.me = null;
      sessionStorage.removeItem(SS_KEY);
    },
  },
});
