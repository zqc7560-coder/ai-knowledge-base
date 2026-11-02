import { createRouter, createWebHistory } from "vue-router";
import { useUserStore } from "../stores/user";

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: "/", name: "home", component: () => import("../views/HomeView.vue") },
    { path: "/login", name: "login", component: () => import("../views/LoginView.vue") },
  ],
});

router.beforeEach((to) => {
  const store = useUserStore();
  if (to.name !== "login" && !store.loggedIn) return { name: "login" };
  if (to.name === "login" && store.loggedIn) return { name: "home" };
});

export default router;
