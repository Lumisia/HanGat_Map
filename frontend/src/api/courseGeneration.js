import { apiRequest, getBackendUserId } from './backendClient.js'

const auth = { auth: true, sessionBound: true, timeoutMs: 15000 }
export const getGenerationJob = id => apiRequest(`/course-generation-jobs/${encodeURIComponent(id)}`, auth)
export const getGenerationResult = id => apiRequest(`/course-generation-jobs/${encodeURIComponent(id)}/result`, auth)
export const listGenerationJobs = (page = 0, size = 20) => apiRequest(`/users/me/course-generation-jobs?page=${page}&size=${size}`, auth)

/** 응답 유실 후 다시 눌러도 같은 요청 키로 접수한다. 토큰은 브라우저 저장소에 넣지 않는다. */
export async function submitGenerationJob (request) {
  const owner = getBackendUserId()
  if (owner == null) throw new Error('로그인이 필요합니다.')
  const key = `hangat:pending-generation:${owner}`
  const serialized = JSON.stringify(request)
  let pending
  try { pending = JSON.parse(sessionStorage.getItem(key) || 'null') } catch { /* 저장소 사용 불가 */ }
  if (!pending || pending.request !== serialized) pending = { request: serialized, requestKey: crypto.randomUUID() }
  try { sessionStorage.setItem(key, JSON.stringify(pending)) } catch { /* 현재 호출은 계속 */ }
  const job = await apiRequest('/course-generation-jobs', { ...auth, method: 'POST', body: { requestKey: pending.requestKey, request } })
  if (!/^[a-f\d]{8}(-[a-f\d]{4}){3}-[a-f\d]{12}$/i.test(job?.jobId)) throw new Error('작업 접수 응답 형식을 확인해 주세요.')
  try { sessionStorage.removeItem(key) } catch { /* 저장소 사용 불가 */ }
  return job
}
