// Time windows for the GraphQL queries. Every list query in contracts/graphql/scheduling.graphql takes
// a [from, to) DateTime window, so pages that used to list "everything" now ask for a window.

/** Monday 00:00 local time of the week that contains `date`. */
export function startOfWeek(date: Date): Date {
  const d = new Date(date);
  d.setHours(0, 0, 0, 0);
  d.setDate(d.getDate() - ((d.getDay() + 6) % 7));
  return d;
}

export function addDays(date: Date, days: number): Date {
  const d = new Date(date);
  d.setDate(d.getDate() + days);
  return d;
}

/** The contract's DateTime scalar: ISO-8601 with offset, e.g. 2026-10-01T08:00:00.000Z. */
export function toDateTime(date: Date): string {
  return date.toISOString();
}

/** [from, to) of one week, as query variables. */
export function weekWindow(weekStart: Date): { from: string; to: string } {
  return { from: toDateTime(weekStart), to: toDateTime(addDays(weekStart, 7)) };
}

/**
 * The window used where the REST pages used to list everything (my schedule, assignment list,
 * shift pickers): four weeks back and eight weeks ahead of the current week.
 */
export const PAST_WEEKS = 4;
export const FUTURE_WEEKS = 8;

export function scheduleWindow(now: Date = new Date()): { from: string; to: string } {
  const weekStart = startOfWeek(now);
  return {
    from: toDateTime(addDays(weekStart, -7 * PAST_WEEKS)),
    to: toDateTime(addDays(weekStart, 7 * (FUTURE_WEEKS + 1))),
  };
}

/** `<input type="datetime-local">` value (local time, minutes) for a DateTime from the server. */
export function toLocalInput(dateTime: string | null | undefined): string {
  if (!dateTime) return '';
  const d = new Date(dateTime);
  if (Number.isNaN(d.getTime())) return '';
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(
    d.getMinutes()
  )}`;
}

/** A datetime-local value back to a DateTime with offset, or null when empty. */
export function fromLocalInput(value: string): string | null {
  if (!value) return null;
  const d = new Date(value);
  return Number.isNaN(d.getTime()) ? null : d.toISOString();
}
