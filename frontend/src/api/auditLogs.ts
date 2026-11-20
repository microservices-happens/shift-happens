import { apiRequest } from './client';
import type { paths } from './generated/audit';
import type { AuditEntry, V1Path } from './types';

// audit-service, contracts/openapi/audit.yaml. Administrator only (the service answers 403 for others).
const AUDIT_LOGS = '/auditlogs' satisfies V1Path<keyof paths>;

export interface AuditFilters {
  eventType?: string;
  aggregateId?: number;
  correlationId?: string;
  /** ISO-8601 instants, as sent to the service. */
  from?: string;
  to?: string;
}

/** Newest first, at most AUDIT_MAX_ROWS (1000) entries; empty filters return the most recent page. */
export function searchAuditLogs(filters: AuditFilters = {}): Promise<AuditEntry[]> {
  const params = new URLSearchParams();
  if (filters.eventType) params.set('eventType', filters.eventType);
  if (filters.aggregateId !== undefined && !Number.isNaN(filters.aggregateId)) {
    params.set('aggregateId', String(filters.aggregateId));
  }
  if (filters.correlationId) params.set('correlationId', filters.correlationId);
  if (filters.from) params.set('from', filters.from);
  if (filters.to) params.set('to', filters.to);
  const query = params.toString();
  return apiRequest<AuditEntry[]>(query ? `${AUDIT_LOGS}?${query}` : AUDIT_LOGS);
}
