import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
// @ts-expect-error type error without @types/node package
import process from "node:process";
const host = process.env.TAURI_DEV_HOST;
// Where the Spring backend runs; used only by the browser dev proxy below.
// @ts-expect-error node env
const apiTarget = process.env.VITE_API_PROXY || "http://localhost:8080";

// https://vite.dev/config/
export default defineConfig(() => ({
  plugins: [react()],

  // Vite options tailored for Tauri development and only applied in `tauri dev` or `tauri build`
  //
  // 1. prevent Vite from obscuring rust errors
  clearScreen: false,
  // 2. tauri expects a fixed port, fail if that port is not available
  server: {
    port: 1420,
    strictPort: true,
    host: host || false,
    // Under `tauri dev` the app talks to the API through the Rust HTTP plugin, so this
    // proxy only matters for plain browser dev (`pnpm dev` opened in a browser), where
    // it forwards /api and /actuator to the backend and sidesteps CORS.
    proxy: {
      "/api": { target: apiTarget, changeOrigin: true },
      "/actuator": { target: apiTarget, changeOrigin: true },
    },
    hmr: host
      ? {
          protocol: "ws",
          host,
          port: 1421,
        }
      : undefined,
    watch: {
      // 3. tell Vite to ignore watching `src-tauri`
      ignored: ["**/src-tauri/**"],
    },
  },
}));
