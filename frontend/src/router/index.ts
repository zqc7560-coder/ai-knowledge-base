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
  ],
});

router.beforeEach((to) => {
  const store = useUserStore();
  if (to.name !== "login" && !store.loggedIn) return { name: "login" };
  if (to.name === "login" && store.loggedIn) return { name: "home" };
});

export default router;
