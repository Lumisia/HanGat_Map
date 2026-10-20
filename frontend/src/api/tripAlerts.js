import { apiRequest } from './backendClient.js'
const auth = { auth: true, sessionBound: true, timeoutMs: 15000 }
export const getConfirmedTrip = () => apiRequest('/users/me/trip-confirmation', auth)
export const confirmTrip = (courseId, expectedVersion) => apiRequest('/users/me/trip-confirmation', {
  ...auth, method: 'PUT', body: { courseId, expectedVersion }
})
export const cancelConfirmedTrip = version => apiRequest(`/users/me/trip-confirmation?version=${encodeURIComponent(version)}`, { ...auth, method: 'DELETE' })
export const getNotificationPreferences = () => apiRequest('/users/me/notification-preferences', auth)
export const saveNotificationPreferences = preferences => apiRequest('/users/me/notification-preferences', { ...auth, method: 'PUT', body: preferences })
