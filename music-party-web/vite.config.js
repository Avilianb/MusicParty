import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// https://vitejs.dev/config/
export default defineConfig({
  // 相对 base：产物可放在站点根，也可整体放到子路径（如 https://host/party/）下运行
  base: './',
  plugins: [vue()],
  server: {
    // 完全放开 host 校验，允许通过 Cloudflare 隧道等任意域名访问（本地测试用）
    allowedHosts: true,
    proxy: {
      // 代理 API 请求
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      // 代理 WebSocket
      '/ws': {
        target: 'http://localhost:8080',
        ws: true,
        changeOrigin: true
      }
    }
  }
})