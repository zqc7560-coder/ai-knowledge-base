import { createRouter, createWebHistory } from "vue-router";
import { useUserStore } from "../stores/user";

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: "/",
      component: () => import("../views/AppLayout.vue"),
      children: [
        { path: "", name: "home", component: () => import("../views/KbListView.vue") },
        { path: "kb/:id/docs", name: "docs", component: () => import("../views/DocListView.vue") },
        { path: "chat", name: "chat", component: () => import("../views/ChatView.vue") },
        { path: "admin", name: "admin", component: () => import("../views/AdminView.vue") },
      ],
    },
    { path: "/login", name: "login", component: () => import("../views/LoginView.vue") },
    // 公开分享页：无需登录（对应 /api/public/** 白名单）
    { path: "/share/:token", name: "share", component: () => import("../views/ShareView.vue") },
  ],
});

router.beforeEach((to) => {
  const store = useUserStore();
  if (to.name === "share") return; // 公开路由直通
  if (to.name !== "login" && !store.loggedIn) return { name: "login" };
  if (to.name === "login" && store.loggedIn) return { name: "home" };
});

export default router;
