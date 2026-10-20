import { readFileSync } from 'node:fs'
import { afterEach, describe, expect, it, vi } from 'vitest'

vi.mock('./backendClient.js', () => ({}))
afterEach(() => { vi.unstubAllEnvs(); vi.resetModules() })
const source = path => readFileSync(new URL(`../../${path}`, import.meta.url), 'utf8')

describe('paired production async rollout', () => {
  it.each([
    [true, undefined, true], [false, undefined, true],
    [true, 'false', false], [false, 'true', true], [true, 'true', true],
  ])('production=%s flag=%s enables=%s', async (production, flag, enabled) => {
    vi.stubEnv('PROD', production)
    vi.stubEnv('VITE_ASYNC_COURSES_ENABLED', flag)
    const module = await import('./notifications.js')
    expect(module.ASYNC_COURSES_ENABLED).toBe(enabled)
  })
  it('Docker and Jenkins ship both sides enabled, including private-value precedence', () => {
    const docker = source('../cicd/frontend.Dockerfile')
    expect(docker).toContain('ARG VITE_ASYNC_COURSES_ENABLED=true')
    expect(docker).toContain('VITE_ASYNC_COURSES_ENABLED="${VITE_ASYNC_COURSES_ENABLED}"')
    const pipeline = source('../cicd/Jenkinsfile')
    expect(pipeline).toContain('--build-arg VITE_ASYNC_COURSES_ENABLED=true')
    expect(pipeline.match(/--set-string backend.configEnv.HANGAT_ASYNC_ENABLED=true/g)).toHaveLength(4)
    const deployment = source('../cicd/helm/templates/backend-deployment.yaml')
    expect(deployment).toContain('--hangat.async.enabled=%s')
    expect(deployment).toContain('.Values.backend.configEnv.HANGAT_ASYNC_ENABLED | toString')
  })
  it('keeps production migration and authorization contracts', () => {
    const prod = source('../backend/src/main/resources/application-prod.yaml')
    expect(prod).toContain('enabled: ${HANGAT_ASYNC_ENABLED:true}')
    expect(prod).toMatch(/flyway:\s+enabled: true/)
    expect(prod).toContain('baseline-on-migrate: false')
    expect(prod).toContain('ddl-auto: validate')
    const view = source('src/views/ai-course/AiCourseView.vue')
    expect(view).toContain('editing && auth.isLoggedIn && ASYNC_COURSES_ENABLED')
    expect(view).toContain('ASYNC_COURSES_ENABLED && auth.isAuthenticated')
  })
})
