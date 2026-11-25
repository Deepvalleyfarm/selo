import { defineConfig } from "vitest/config";
// Unit tests only; security-rules tests need the emulator and run with `npm run test:rules`.
export default defineConfig({ test: { include: ["test/**/*.test.ts"] } });
