import { defineConfig } from 'vitest/config';

/**
 * Loaded by Angular `@angular/build:unit-test` when `angular.json` sets `runnerConfig` to this path.
 * (`runnerConfig: true` only auto-discovers `vitest-base.config.*`, not `vitest.config.ts`.)
 * Fork workers are more stable than threads in constrained container runners.
 * Keep parallelism modest and allow longer hook/test timeouts for slower agents.
 */
export default defineConfig({
  test: {
    pool: 'forks',
    maxWorkers: 2,
    fileParallelism: true,
    hookTimeout: 120_000,
    testTimeout: 120_000,
  },
});
