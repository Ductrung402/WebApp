import { test as base, expect } from '@playwright/test'
import { basename } from 'node:path'

// Keep QA screenshots inside Playwright output; never overwrite the user's docs/screenshots.
export const test = base.extend({
  page: async ({ page }, use, testInfo) => {
    const screenshot = page.screenshot.bind(page)
    page.screenshot = (options = {}) => screenshot({
      ...options,
      ...(typeof options.path === 'string' && options.path.startsWith('../docs/screenshots/')
        ? { path: testInfo.outputPath(basename(options.path)) } : {}),
    })
    await use(page)
  },
})
export { expect }
