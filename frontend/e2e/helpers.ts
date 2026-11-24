import { expect, type Locator, type Page } from '@playwright/test';

export const PASSWORD = process.env.E2E_PASSWORD ?? 'Demo1234!';
export const MANAGER = 'sofie.jensen@shift-happens.example';
export const ADMIN = 'admin@shift-happens.example';

/** Local e-mail/password login through the real form; the session must survive a reload. */
export async function login(page: Page, email: string = MANAGER): Promise<void> {
  await page.goto('/login', { waitUntil: 'domcontentloaded' });
  await page.getByLabel('Email', { exact: true }).fill(email);
  await page.getByLabel('Password', { exact: true }).fill(PASSWORD);
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();
  await expect(page).toHaveURL(/\/$/);
}

/** Selects the option whose text contains `contains`, polling while an async list is still loading. */
export async function pickOption(page: Page, select: Locator, contains: string): Promise<string> {
  let value: string | undefined;
  await expect
    .poll(
      async () => {
        const options = await select
          .locator('option')
          .evaluateAll((els) => els.map((el) => ({ value: el.value, text: (el.textContent ?? '').trim() })));
        value = options.find((option) => option.text.includes(contains))?.value;
        return value;
      },
      { message: `option containing "${contains}"`, timeout: 20_000 }
    )
    .toBeTruthy();
  return value as string;
}

export function unique(prefix: string): string {
  return `${prefix} ${Date.now().toString().slice(-7)}`;
}

/** A far-future day per run, so overlap rules of previous runs cannot fire. */
export function futureDate(daysFromNow: number): string {
  return new Date(Date.now() + daysFromNow * 86_400_000).toISOString().slice(0, 10);
}

/**
 * Navigation helper for reads that come from a CQRS read model: the command returned 202 and the
 * projection catches up from events, so the page is reloaded until the option shows up.
 */
export async function gotoUntilOption(
  page: Page,
  url: string,
  selectIndex: number,
  contains: string
): Promise<void> {
  await expect
    .poll(
      async () => {
        await page.goto(url);
        const options = await page
          .locator('form select')
          .nth(selectIndex)
          .locator('option')
          .evaluateAll((els) => els.map((el) => (el.textContent ?? '').trim()));
        return options.some((text) => text.includes(contains));
      },
      { message: `option "${contains}" on ${url}`, timeout: 30_000 }
    )
    .toBe(true);
}

/** Same for a table row that is served by the read model. */
export async function gotoUntilRow(page: Page, url: string, contains: string): Promise<void> {
  await expect
    .poll(
      async () => {
        await page.goto(url);
        return (await page.locator('tr', { hasText: contains }).count()) > 0;
      },
      { message: `row "${contains}" on ${url}`, timeout: 30_000 }
    )
    .toBe(true);
}

/** Reloads until the page text contains everything in `parts` (e.g. an approved swap). */
export async function gotoUntilText(page: Page, url: string, ...parts: string[]): Promise<void> {
  await expect
    .poll(
      async () => {
        await page.goto(url);
        const text = await page.locator('body').innerText();
        return parts.every((part) => text.includes(part));
      },
      { message: `text ${parts.join(' + ')} on ${url}`, timeout: 30_000 }
    )
    .toBe(true);
}
