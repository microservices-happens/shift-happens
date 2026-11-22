// Domain types for the REST client. Everything here is derived from the types generated from
// contracts/openapi/*.yaml (see src/api/generated, `npm run generate:api`); do not hand-write
// payload shapes. Only the conveniences below are added on top.
import type { components as Audit } from './generated/audit';
import type { components as Identity } from './generated/identity';
import type { components as Leave } from './generated/leave';
import type { components as Notification } from './generated/notification';
import type { components as Scheduling } from './generated/scheduling';
import type { components as Workforce } from './generated/workforce';

/**
 * The contracts mark server-assigned ids as `readOnly` (optional in the generated type), so the
 * same schema can be used as request body. A resource returned by the server always carries its
 * id, so response types make that field required.
 */
type Persisted<T, K extends keyof T> = Omit<T, K> & { readonly [P in K]-?: Exclude<T[P], undefined | null> };

/** Request body for create/update: the schema without its server-assigned (readOnly) fields. */
type Writable<T, K extends keyof T> = Omit<T, K>;

/** One string per contract path under /v1, with the `/v1` prefix removed (the base URL adds it). */
export type V1Path<P> = P extends `/v1${infer Rest}` ? Rest : never;

export type Problem = Identity['schemas']['Problem'];

// identity-service
export type LoginRequest = Identity['schemas']['LoginRequest'];
export type LoginResponse = Identity['schemas']['LoginResponse'];
export type RoleName = LoginResponse['roleName'];
export type AuthUser = Omit<LoginResponse, 'token'>;
export type SetPasswordRequest = Identity['schemas']['SetPasswordRequest'];

// audit-service (Administrator only)
export type AuditEntry = Audit['schemas']['AuditEntry'];

// workforce-service
type EmployeeSchema = Workforce['schemas']['Employee'];
export type Employee = Persisted<EmployeeSchema, 'employeeId'>;
export type NewEmployee = Writable<EmployeeSchema, 'employeeId'>;

type DepartmentSchema = Workforce['schemas']['Department'];
export type Department = Persisted<DepartmentSchema, 'departmentId'>;
export type NewDepartment = Writable<DepartmentSchema, 'departmentId'>;

export type WorkLocation = Persisted<Workforce['schemas']['WorkLocation'], 'workLocationId'>;
export type JobRole = Persisted<Workforce['schemas']['JobRole'], 'jobRoleId'>;

// scheduling-service (REST covers writes only; reads are GraphQL, P5.2)
type ShiftSchema = Scheduling['schemas']['Shift'];
export type Shift = Persisted<ShiftSchema, 'shiftId'>;
/** The form always sends a staffing plan (possibly empty); the contract property is optional. */
export type NewShift = Writable<ShiftSchema, 'shiftId'> & {
  requiredJobRoles: NonNullable<ShiftSchema['requiredJobRoles']>;
};

type ShiftAssignmentSchema = Scheduling['schemas']['ShiftAssignment'];
export type ShiftAssignment = Persisted<ShiftAssignmentSchema, 'shiftAssignmentId'>;
export type NewShiftAssignment = Writable<ShiftAssignmentSchema, 'shiftAssignmentId'>;

// leave-service
export type LeaveType = Persisted<Leave['schemas']['LeaveType'], 'leaveTypeId'>;
export type NewLeaveType = Writable<Leave['schemas']['LeaveType'], 'leaveTypeId'>;

type LeaveRequestSchema = Leave['schemas']['LeaveRequest'];
export type LeaveRequest = Persisted<LeaveRequestSchema, 'leaveRequestId'>;
export type LeaveRequestStatus = NonNullable<LeaveRequestSchema['requestStatus']>;
export type NewLeaveRequest = Writable<
  LeaveRequestSchema,
  'leaveRequestId' | 'requestStatus' | 'rejectionReason' | 'requestedDatetime'
>;

type LeaveApprovalSchema = Leave['schemas']['LeaveApproval'];
export type LeaveApproval = LeaveApprovalSchema;
export type LeaveDecision = LeaveApprovalSchema['decision'];
export type NewLeaveApproval = Writable<LeaveApprovalSchema, 'leaveApprovalId' | 'decisionDatetime'>;

type LeaveLedgerEntrySchema = Leave['schemas']['LeaveLedgerEntry'];
export type LeaveLedgerEntry = Persisted<LeaveLedgerEntrySchema, 'leaveLedgerId'>;
export type NewLeaveLedgerEntry = Writable<
  LeaveLedgerEntrySchema,
  'leaveLedgerId' | 'transactionDatetime'
>;

// scheduling-service (shift swaps)
type ShiftSwapSchema = Scheduling['schemas']['ShiftSwap'];
export type ShiftSwap = Persisted<ShiftSwapSchema, 'shiftSwapId'>;
export type NewShiftSwap = Writable<ShiftSwapSchema, 'shiftSwapId' | 'swapStatus' | 'requestDatetime'>;

type ShiftSwapApprovalSchema = Scheduling['schemas']['ShiftSwapApproval'];
export type ShiftSwapApproval = Persisted<ShiftSwapApprovalSchema, 'shiftSwapApprovalId'>;
export type NewShiftSwapApproval = Writable<
  ShiftSwapApprovalSchema,
  'shiftSwapApprovalId' | 'decisionDatetime'
>;

// workforce-service (employee job roles)
type EmployeeJobRoleSchema = Workforce['schemas']['EmployeeJobRole'];
export type EmployeeJobRole = Persisted<EmployeeJobRoleSchema, 'employeeJobRoleId'>;
export type NewEmployeeJobRole = Writable<EmployeeJobRoleSchema, 'employeeJobRoleId'>;

export class ApiError extends Error {
  constructor(
    public status: number,
    public body: unknown,
    message: string
  ) {
    super(message);
  }
}

// notification-service
export type AppNotification = Notification['schemas']['Notification'];
export type NotificationUpdate = Notification['schemas']['NotificationUpdate'];
