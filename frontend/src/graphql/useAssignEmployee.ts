import { useCallback, useState } from 'react';
import { useApolloClient } from '@apollo/client/react';
import { createShiftAssignment } from '../api/shiftAssignments';
import { ApiError } from '../api/types';
import {
  MyScheduleDocument,
  ShiftByIdDocument,
  ShiftsWithAssignmentsDocument,
  UnderstaffedShiftIdsDocument,
  UnderstaffedShiftsDocument,
} from './generated';

/** Every schedule read that an assignment can change. Only the ones currently on screen are refetched. */
const SCHEDULE_QUERIES = [
  ShiftsWithAssignmentsDocument,
  UnderstaffedShiftsDocument,
  UnderstaffedShiftIdsDocument,
  MyScheduleDocument,
  ShiftByIdDocument,
];

/**
 * The read model trails the command side (normally < 1 s, contracts/graphql/scheduling.graphql), so
 * the view is refetched right after the 202 and once more after this delay.
 */
const READ_MODEL_CATCH_UP_MS = 1000;

/**
 * Assigns an employee to a shift: a REST POST to scheduling-command (`POST /api/v1/shiftassignments`,
 * contracts/openapi/scheduling.yaml), followed by a refetch of the GraphQL schedule queries on screen.
 * GraphQL has no mutations; writes are always REST.
 */
export function useAssignEmployee() {
  const client = useApolloClient();
  const [pendingKey, setPendingKey] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const refresh = useCallback(() => {
    void client.refetchQueries({ include: SCHEDULE_QUERIES });
  }, [client]);

  const assign = useCallback(
    async (shiftId: number, employeeId: number): Promise<boolean> => {
      setError(null);
      setPendingKey(`${shiftId}:${employeeId}`);
      try {
        await createShiftAssignment({
          shiftId,
          employeeId,
          assignmentStatus: 'ASSIGNED',
          assignedDatetime: new Date().toISOString(),
          checkInDatetime: null,
          checkOutDatetime: null,
        });
        refresh();
        window.setTimeout(refresh, READ_MODEL_CATCH_UP_MS);
        return true;
      } catch (err) {
        setError(err instanceof ApiError ? err.message : 'Assignment failed');
        return false;
      } finally {
        setPendingKey(null);
      }
    },
    [refresh]
  );

  /** True while the POST for this shift/employee pair is running. */
  const isPending = useCallback(
    (shiftId: number, employeeId: number) => pendingKey === `${shiftId}:${employeeId}`,
    [pendingKey]
  );

  return { assign, isPending, error, refresh };
}
