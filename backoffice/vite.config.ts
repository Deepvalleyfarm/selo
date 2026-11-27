import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import { resolve } from "node:path";

// Two pages: the back office (index.html) and the public developer portal (developers.html, served at /developers).
export default defineConfig({ plugins: [react()], build: { sourcemap: false, chunkSizeWarningLimit: 900,
  rollupOptions: { input: { main: resolve(__dirname, "index.html"), developers: resolve(__dirname, "developers.html") } } } });
