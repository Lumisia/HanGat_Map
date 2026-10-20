import { expect, it } from 'vitest'
import { useCourseTabs } from './useCourseTabs'

it('목록은 닫히지 않고 같은 코스는 중복 탭을 만들지 않는다', () => {
  const tabs = useCourseTabs()
  tabs.open({ id: '1', title: '서부' }); tabs.open({ id: '1', title: '서부' })
  expect(tabs.opened.value).toHaveLength(1)
  tabs.close('')
  expect(tabs.active.value).toBe('1')
  tabs.showList()
  expect(tabs.active.value).toBe('')
  expect(tabs.opened.value).toHaveLength(1)
})
it('현재 탭을 닫으면 옆 탭으로, 마지막 탭을 닫으면 목록으로 이동한다', () => {
  const tabs = useCourseTabs()
  tabs.open({ id: '1', title: '서부' }); tabs.open({ id: '2', title: '동부' })
  tabs.close('2'); expect(tabs.active.value).toBe('1')
  tabs.close('1'); expect(tabs.active.value).toBe('')
})
