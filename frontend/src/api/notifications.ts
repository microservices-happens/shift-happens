import { apiRequest, pathWithParams } from './client';
import type { paths } from './generated/notification';
import type { AppNotification, NotificationUpdate, V1Path } from './types';

// notification-service, contracts/openapi/notification.yaml
const NOTIFICATIONS = '/notifications' satisfies V1Path<keyof paths>;
const NOTIFICATION = '/notifications/{id}' satisfies V1Path<keyof paths>;

// Background polling is best-effort: a 401 here must not log the user out.
export function listNotifications(unreadOnly?: boolean): Promise<AppNotification[]> {
  const query = unreadOnly === undefined ? '' : `?unreadOnly=${unreadOnly}`;
  return apiRequest<AppNotification[]>(`${NOTIFICATIONS}${query}`, { ignoreUnauthorized: true });
}

export function markNotificationRead(id: number, read = true): Promise<AppNotification> {
  const body: NotificationUpdate = { read };
  return apiRequest<AppNotification>(pathWithParams(NOTIFICATION, { id }), {
    method: 'PATCH',
    body,
    ignoreUnauthorized: true,
  });
}
