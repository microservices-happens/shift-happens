import { expect, test, type Page } from '@playwright/test';
import {
  futureDate,
  gotoUntilOption,
  gotoUntilRow,
  gotoUntilText,
  login,
  pickOption,
  unique,
} from './helpers';

/**
 * One serial business walk over the real system: sign in, add an employee with a job role, create a
 * shift and assign the employee, swap the assignment and approve it, then run a leave request through
 * approval and another through rejection. Each step also guards a UI bug found while building this
 * suite (session loss on reload, missing shift staffing, missing leave balances/swaps).
 */
test.describe.configure({ mode: 'serial' });

let employeeLastName = '';
let swapTargetLastName = '';
let shiftName = '';
let leaveTypeName = '';
let leaveTypeId = '';

test('the session survives a page reload', async ({ page }) => {
  await login(page);
  await expect(page.getByText('Shift Happens').first()).toBeVisible();
  await page.reload({ waitUntil: 'domcontentloaded' });
  await expect(page).toHaveURL(/\/$/);
  await expect(page.getByText('Sofie Jensen')).toBeVisible();
});

test('an employee is created and gets a job role', async ({ page }) => {
  await login(page);
  const stamp = Date.now().toString().slice(-7);
  employeeLastName = `Person${stamp}`;
  await page.goto('/employees/new');
  await page.getByLabel('Employee number', { exact: true }).fill(`E2E-${stamp}`);
  await page.getByLabel('First name', { exact: true }).fill('E2E');
  await page.getByLabel('Last name', { exact: true }).fill(employeeLastName);
  await page.getByLabel('Email', { exact: true }).fill(`e2e.${stamp}@shift-happens.example`);
  await page.getByLabel('Hire date', { exact: true }).fill(futureDate(-1));
  await page.getByRole('button', { name: 'Create', exact: true }).click();

  await expect(page).toHaveURL(/\/employees\/\d+$/);
  await expect(page.getByText('Job roles', { exact: true })).toBeVisible();

  const roleSelect = page.getByLabel('Add role');
  await roleSelect.selectOption(await pickOption(page, roleSelect, 'Sygeplejerske'));
  await page.getByRole('button', { name: 'Add job role', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Remove', exact: true }).first()).toBeVisible();

  // A second employee is the swap target. Creating it through the API keeps the target free of seeded
  // assignments, so repeated E2E runs cannot collide with an old shift.
  const targetStamp = `${stamp}1`;
  swapTargetLastName = `Target${targetStamp}`;
  const token = await page.evaluate(() => localStorage.getItem('shift_happens_token'));
  const headers = {
    Authorization: `Bearer ${token}`,
    'Idempotency-Key': crypto.randomUUID(),
  };
  const targetResponse = await page.request.post('/api/v1/employees', {
    headers,
    data: {
      employeeNumber: `E2E-${targetStamp}`,
      userRole: 'Employee',
      firstName: 'E2E',
      lastName: swapTargetLastName,
      email: `e2e.target.${targetStamp}@shift-happens.example`,
      hireDate: futureDate(-1),
      employmentStatus: 'ACTIVE',
      primaryWorkLocationId: null,
    },
  });
  expect(targetResponse.status()).toBe(201);
  const targetEmployee = await targetResponse.json();
  const roleResponse = await page.request.post('/api/v1/employeejobroles', {
    headers: { ...headers, 'Idempotency-Key': crypto.randomUUID() },
    data: { employeeId: targetEmployee.employeeId, jobRoleId: 1, assignedDate: futureDate(-1) },
  });
  expect(roleResponse.status()).toBe(201);
  await page.waitForTimeout(1500);
});

test('a shift is created with its staffing plan and the employee is assigned', async ({ page }) => {
  await login(page);
  shiftName = unique('E2E Shift');
  const date = futureDate(10 + (Math.floor(Date.now() / 1000) % 30));
  await page.goto(`/shifts/new?date=${date}`);
  await page.getByLabel('Shift name', { exact: true }).fill(shiftName);
  await page.getByLabel('Start', { exact: true }).fill(`${date}T09:00`);
  await page.getByLabel('End', { exact: true }).fill(`${date}T17:00`);
  await expect(page.getByLabel('Job role 1', { exact: true })).not.toHaveValue('0');
  await page.getByRole('button', { name: 'Create', exact: true }).click();
  await expect(page).toHaveURL(/\/shifts$/);

  await page.goto('/shift-assignments/new');
  await gotoUntilOption(page, '/shift-assignments/new', 1, shiftName);
  const selects = page.locator('form select');
  await expect(selects).toHaveCount(3);
  await selects.nth(0).selectOption(await pickOption(page, selects.nth(0), employeeLastName));
  await selects.nth(1).selectOption(await pickOption(page, selects.nth(1), shiftName));
  await page.getByRole('button', { name: 'Create', exact: true }).click();
  await expect(page).toHaveURL(/\/shift-assignments$/);
  await gotoUntilRow(page, '/shift-assignments', shiftName);
  await expect(page.locator('tr', { hasText: shiftName })).toContainText('E2E');
});

test('a swap is requested from the assignment and a manager approves it', async ({ page }) => {
  await login(page);
  await gotoUntilRow(page, '/shift-assignments', shiftName);
  const row = page.locator('tr', { hasText: shiftName });
  await expect(row).toHaveCount(1);
  await row.getByRole('button', { name: 'Request swap', exact: true }).click();
  const colleague = page.locator('.card select').last();
  await colleague.selectOption(await pickOption(page, colleague, swapTargetLastName));
  await page.getByLabel('Reason (optional)', { exact: true }).fill('E2E swap');
  await page.getByRole('button', { name: 'Send request', exact: true }).click();
  await expect(page.getByText(/Swap request \d+ sent/)).toBeVisible();

  await gotoUntilRow(page, '/swap-requests', shiftName);
  const swapRow = page.locator('tr', { hasText: shiftName });
  await expect(swapRow).toHaveCount(1);
  await swapRow.getByRole('button', { name: 'Approve', exact: true }).click();
  await expect(page.getByText(/Approved swap \d+/)).toBeVisible();

  await gotoUntilText(page, '/shift-assignments', shiftName, 'Ida Hansen');
});

test('a swap can be rejected and leaves the assignment with its employee', async ({ page }) => {
  await login(page);
  const rejectShift = unique('E2E Reject Shift');
  const date = futureDate(50 + (Math.floor(Date.now() / 1000) % 5));
  await page.goto(`/shifts/new?date=${date}`);
  await page.getByLabel('Shift name', { exact: true }).fill(rejectShift);
  await page.getByLabel('Start', { exact: true }).fill(`${date}T09:00`);
  await page.getByLabel('End', { exact: true }).fill(`${date}T17:00`);
  await page.getByRole('button', { name: 'Create', exact: true }).click();
  await expect(page).toHaveURL(/\/shifts$/);

  await gotoUntilOption(page, '/shift-assignments/new', 1, rejectShift);
  const selects = page.locator('form select');
  await selects.nth(0).selectOption(await pickOption(page, selects.nth(0), employeeLastName));
  await selects.nth(1).selectOption(await pickOption(page, selects.nth(1), rejectShift));
  await page.getByRole('button', { name: 'Create', exact: true }).click();
  await expect(page).toHaveURL(/\/shift-assignments$/);
  await gotoUntilRow(page, '/shift-assignments', rejectShift);

  const row = page.locator('tr', { hasText: rejectShift });
  await row.getByRole('button', { name: 'Request swap', exact: true }).click();
  const colleague = page.locator('.card select').last();
  await colleague.selectOption(await pickOption(page, colleague, swapTargetLastName));
  await page.getByRole('button', { name: 'Send request', exact: true }).click();
  await expect(page.getByText(/Swap request \d+ sent/)).toBeVisible();

  await gotoUntilRow(page, '/swap-requests', rejectShift);
  const swapRow = page.locator('tr', { hasText: rejectShift });
  await swapRow.getByRole('button', { name: 'Reject', exact: true }).click();
  await expect(page.getByText(/Rejected swap \d+/)).toBeVisible();

  await gotoUntilText(page, '/shift-assignments', rejectShift, employeeLastName);
  await expect(page.locator('tr', { hasText: rejectShift })).not.toContainText('Emma Pedersen');
});

test('a leave type is created with a balance and a request is approved', async ({ page }) => {
  await login(page);
  leaveTypeName = unique('E2E Leave');
  await page.goto('/leave-types');
  const createForm = page.locator('form').first();
  await createForm.getByLabel('Name', { exact: true }).fill(leaveTypeName);
  await createForm.getByRole('button', { name: 'Create leave type', exact: true }).click();

  const grantForm = page.locator('form').nth(1);
  const grantSelects = grantForm.locator('select');
  leaveTypeId = await pickOption(page, grantSelects.nth(1), leaveTypeName);
  await grantSelects.nth(0).selectOption(await pickOption(page, grantSelects.nth(0), employeeLastName));
  await grantSelects.nth(1).selectOption(leaveTypeId);
  await grantForm.getByLabel('Days', { exact: true }).fill('25');
  await grantForm.getByRole('button', { name: 'Grant days', exact: true }).click();
  await expect(page.getByText(/day\(s\) granted/)).toBeVisible();

  const requestId = await submitLeaveRequest(page, 500 + (Math.floor(Date.now() / 1000) % 20));
  await gotoUntilText(page, `/leave-requests/${requestId}`, 'Approve');
  await page.getByRole('button', { name: 'Approve', exact: true }).click();
  await expect(page.locator('.pill', { hasText: 'Approved' })).toBeVisible({ timeout: 90_000 });
});

test('a leave request can be rejected with a reason', async ({ page }) => {
  await login(page);
  const requestId = await submitLeaveRequest(page, 600 + (Math.floor(Date.now() / 1000) % 20));
  await gotoUntilText(page, `/leave-requests/${requestId}`, 'Reject');
  await page.getByLabel('Comment', { exact: true }).fill('E2E rejection reason');
  await page.getByRole('button', { name: 'Reject', exact: true }).click();
  await expect(page.locator('.pill', { hasText: 'Rejected' })).toBeVisible({ timeout: 60_000 });
  await expect(page.getByText('E2E rejection reason')).toBeVisible();
});

async function submitLeaveRequest(page: Page, dayOffset: number): Promise<number> {
  const start = futureDate(dayOffset);
  const end = futureDate(dayOffset + 1);
  await gotoUntilOption(page, '/leave-requests/new', 1, leaveTypeName);
  const form = page.locator('form').first();
  const selects = form.locator('select');
  await selects.nth(0).selectOption(await pickOption(page, selects.nth(0), employeeLastName));
  await selects.nth(1).selectOption(leaveTypeId || (await pickOption(page, selects.nth(1), leaveTypeName)));
  await form.getByLabel('Start date', { exact: true }).fill(start);
  await form.getByLabel('End date', { exact: true }).fill(end);
  await form.getByLabel('Reason', { exact: true }).fill('E2E leave');
  const [response] = await Promise.all([
    page.waitForResponse(
      (response) => response.url().endsWith('/api/v1/leaverequests') && response.request().method() === 'POST'
    ),
    form.getByRole('button', { name: 'Submit request', exact: true }).click(),
  ]);
  expect(response.status()).toBe(202);
  return (await response.json()).leaveRequestId as number;
}
