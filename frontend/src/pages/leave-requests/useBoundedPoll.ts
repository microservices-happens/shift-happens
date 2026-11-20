import { useEffect, useRef, useState } from 'react';

export interface BoundedPollOptions {
  intervalMs?: number;
  maxAttempts?: number;
}

/**
 * Calls `tick` every `intervalMs` while `run` is not null, until `tick` resolves to true (settled)
 * or `maxAttempts` reads were made. `run` identifies one polling run: pass a new value to start
 * again, null to stop. Pending timers are cleared on unmount and when `run` changes, so a closed
 * page never keeps polling. A failing read counts as an attempt and the next one is tried.
 *
 * Returns true when the current run stopped after `maxAttempts` without settling.
 */
export function useBoundedPoll(
  run: number | null,
  tick: () => Promise<boolean>,
  { intervalMs = 2000, maxAttempts = 45 }: BoundedPollOptions = {}
): boolean {
  const tickRef = useRef(tick);
  useEffect(() => {
    tickRef.current = tick;
  });
  const [exhaustedRun, setExhaustedRun] = useState<number | null>(null);

  useEffect(() => {
    if (run === null) return;
    let cancelled = false;
    let timer: number | undefined;
    let attempts = 0;

    const poll = async () => {
      attempts += 1;
      let settled = false;
      try {
        settled = await tickRef.current();
      } catch {
        settled = false;
      }
      if (cancelled || settled) return;
      if (attempts >= maxAttempts) {
        setExhaustedRun(run);
        return;
      }
      timer = window.setTimeout(poll, intervalMs);
    };

    timer = window.setTimeout(poll, intervalMs);
    return () => {
      cancelled = true;
      window.clearTimeout(timer);
    };
  }, [run, intervalMs, maxAttempts]);

  return run !== null && exhaustedRun === run;
}
