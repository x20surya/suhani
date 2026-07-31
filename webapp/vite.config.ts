import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";
import { fileURLToPath, URL } from "node:url";

// Builds into ../web, which silai/web.py already serves via StaticFiles.
// The build output is committed, so the aarch64 VPS never needs Node.
export default defineConfig({
  plugins: [react(), tailwindcss()],
  build: {
    outDir: fileURLToPath(new URL("../web", import.meta.url)),
    emptyOutDir: true,
    // Readable stack traces from a phone in another room beat a few KB.
    sourcemap: true,
    target: "es2022",
  },
  server: {
    // `npm run dev` proxies the API to the FastAPI server on 8080.
    proxy: {
      "/api": "http://127.0.0.1:8080",
    },
  },
});
