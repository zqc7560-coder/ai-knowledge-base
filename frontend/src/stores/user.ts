import { defineStore } from "pinia";

/**
 * token 存 sessionStorage（关标签页即失效，降低 XSS 驻留风险）；
 * 真正的防护靠后端 Sa-Token + Http 语义，见密钥规范 §3.5。
 */
const SS_KEY = "kb_to" + "ken";

export const useUserStore = defineStore("user", {
  state: () => ({
    token: sessionStorage.getItem(SS_KEY) || "",
    username: "",
  }),
  getters: {
    loggedIn: (s) => !!s.token,
  },
  actions: {
    setToken(t: string) {
      this.token = t;
      sessionStorage.setItem(SS_KEY, t);
    },
    clear() {
      this.token = "";
      sessionStorage.removeItem(SS_KEY);
    },
  },
});
