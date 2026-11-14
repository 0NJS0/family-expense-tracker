// Drives the real dashboard (production build) against a real backend in
// a headless Chromium-based browser. Run by scripts/e2e.sh; needs E2E_URL, E2E_USER, E2E_PASSWORD.
// Fails on any console error, failed request or HTTP error response.
import { existsSync } from 'node:fs'
import { delimiter, join } from 'node:path'
import puppeteer from 'puppeteer-core'

const url = process.env.E2E_URL
const user = process.env.E2E_USER
const password = process.env.E2E_PASSWORD
const browserPath =
  process.env.E2E_BROWSER ??
  ['chromium', 'chromium-browser', 'google-chrome', 'google-chrome-stable', 'brave', 'brave-browser', 'microsoft-edge']
    .flatMap((name) => (process.env.PATH ?? '').split(delimiter).map((dir) => join(dir, name)))
    .find((path) => existsSync(path))
if (!browserPath) throw new Error('No Chromium-based browser on PATH; set E2E_BROWSER to its executable')
if (!url || !user || !password) throw new Error('E2E_URL, E2E_USER and E2E_PASSWORD are required')

const PAGES = ['/', '/analytics', '/transactions', '/budgets', '/recurring', '/summaries', '/admin']
const problems = []
const browser = await puppeteer.launch({ executablePath: browserPath, headless: true, args: ['--no-first-run'] })

try {
  const page = await browser.newPage()
  page.on('console', (m) => m.type() === 'error' && problems.push(`console: ${m.text()}`))
  page.on('pageerror', (e) => problems.push(`page error: ${e.message}`))
  page.on('requestfailed', (r) => problems.push(`request failed: ${r.method()} ${r.url()} ${r.failure()?.errorText}`))
  page.on('response', (r) => r.status() >= 400 && problems.push(`HTTP ${r.status()}: ${r.request().method()} ${r.url()}`))

  const heading = async () => {
    await page.waitForSelector('h1', { timeout: 15_000 })
    return page.$eval('h1', (h) => h.textContent?.trim() ?? '')
  }

  // Sign in (the first visit shows the login page in Bangla).
  await page.setViewport({ width: 1280, height: 900 })
  await page.goto(url, { waitUntil: 'networkidle0' })
  await page.locator('::-p-aria(ইউজারনেম)').fill(user)
  await page.locator('::-p-aria(পাসওয়ার্ড)').fill(password)
  await page.locator('::-p-aria(প্রবেশ করুন[role="button"])').click()
  await page.waitForSelector('nav[aria-label="Main"]', { timeout: 15_000 })

  for (const lang of ['bn', 'en']) {
    for (const path of PAGES) {
      await page.goto(url + path, { waitUntil: 'networkidle0' })
      const h1 = await heading()
      if (!h1) problems.push(`${lang} ${path}: empty heading`)
      console.log(`  ok  ${lang} ${path}  "${h1}"`)
    }
    // In Bangla the switch reads "English".
    if (lang === 'bn') await page.locator('::-p-aria(English[role="button"])').click()
  }

  // The session survives a reload (refresh token), with no login page.
  await page.reload({ waitUntil: 'networkidle0' })
  if (await page.$('input[type="password"]')) problems.push('reload: signed out')
  else console.log('  ok  session survives a reload')

  // Phone-sized screen: no sideways scrolling on the main pages.
  await page.setViewport({ width: 390, height: 844, isMobile: true })
  for (const path of ['/', '/transactions', '/budgets']) {
    await page.goto(url + path, { waitUntil: 'networkidle0' })
    const wide = await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth + 1)
    if (wide) problems.push(`${path}: scrolls sideways at 390 px`)
    else console.log(`  ok  ${path} fits a 390 px screen`)
  }
} finally {
  await browser.close()
}

if (problems.length) {
  console.error('Dashboard check FAILED:\n' + problems.map((p) => '  - ' + p).join('\n'))
  process.exit(1)
}
console.log('Dashboard check passed.')
