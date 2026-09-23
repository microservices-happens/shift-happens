# 0002. Shift swaps live inside Scheduling; the saga is leave approval

- **Status:** Accepted
- **Date:** 2026-09-23

## Context
Shift Swap was first drawn as its own service. A swap only reassigns an existing shift assignment, which Scheduling owns. The course also requires a saga with compensating actions.

## Decision
Swaps are part of Scheduling, where approval and reassignment are one local transaction. The saga requirement is covered by leave approval, which truly spans two services:
1. Leave reserves days in the ledger.
2. Scheduling releases the overlapping assignments.
3. On refusal or timeout, Leave compensates with a reversal ledger entry and rejects the request.

## Consequences
- **Easier:** one service fewer, with no distributed workflow for a single-owner operation.
- **Harder:** the saga needs a saga table, a timeout and cooperation tests for both outcomes.

## Alternatives considered
A separate Shift Swap service with a swap saga: an artificial split, because the data belongs to Scheduling.
