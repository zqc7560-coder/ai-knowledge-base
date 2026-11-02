import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      "/api": { target: "http://127.0.0.1:8080", changeOrigin: true },
    },
  },
  build: {
    // 密钥红线 L3：前端构建产物绝不允许出现 AI 上游域名 —— proxy 只在 dev 生效，
    // 生产同源部署由 nginx /api 转发，代码里只写相对路径 /api。
    chunkSizeWarningLimit: 1200,
  },
});
