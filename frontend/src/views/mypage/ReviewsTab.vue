<script setup>
/**
 * 작성한 리뷰 (요구사항 정의서 MY_004 · MY_005).
 *
 *  MY_004 — 내 리뷰만 조회 / 장소명·별점·내용 일부·작성일·수정일·첨부 사진 표시
 *           장소 선택 시 상세로 이동 / 최신·별점 정렬 / 더보기 / 긴 글 접기 / 빈 상태
 *  MY_005 — 삭제 전 장소명 + 리뷰 내용 확인 → 최종 확인, 사진 함께 제거,
 *           장소 리뷰 수·평균 별점 재계산, 중복 삭제해도 오류 없음
 *
 * ⚠️ 2026-08-15 — 카드 아래 버튼 3줄(`장소 보기` · `수정` · `삭제`)을 걷어내고
 *    알림 카드와 같은 형태로 **오른쪽 위에 장소 보기 + X** 두 개만 뒀다.
 *    그래서 **리뷰 수정이 화면에서 닿을 수 없다** — MY_004 의 '수정' 항목이 빠진다.
 *    API(`updateMyReview`)와 테스트는 그대로 살아 있어 버튼만 다시 붙이면 된다.
 *    X 는 삭제다. 되돌릴 수 없으므로 확인 창(MY_005)은 그대로 남긴다.
 */
import { onMounted, ref, watch } from 'vue'
import StarRating from '../../components/mypage/StarRating.vue'
import EmptyState from '../../components/common/EmptyState.vue'
import StateBlock from '../../components/common/StateBlock.vue'
import SortSeg from '../../components/mypage/SortSeg.vue'
import ConfirmDeleteDialog from '../../components/mypage/ConfirmDeleteDialog.vue'
import PlaceThumb from '../../components/mypage/PlaceThumb.vue'
import { listMyReviews, deleteMyReview, REVIEW_SORTS } from '../../api/mypage.js'
import { useUiStore } from '../../stores/ui.js'
import { useApiError } from '../../composables/useApiError.js'
import { fmtK, truncate } from '../../utils/format.js'

const ui = useUiStore()
const toMessage = useApiError()

// 한국어는 글자당 정보량이 커서 60자면 카드 두 줄쯤 된다
const PREVIEW_LEN = 60
const PAGE_SIZE = 4

const sort = ref('created_desc')
const pageNo = ref(1)
const loading = ref(true)
const error = ref(null)
const data = ref({ items: [], total: 0, hasMore: false })
const expanded = ref(new Set())

const deleting = ref(null)
const deleteBusy = ref(false)

const CROWD_KO = { calm: '한산했어요', mid: '보통이었어요', busy: '혼잡했어요' }

async function fetchList (reset = false) {
  if (reset) pageNo.value = 1
  loading.value = data.value.items.length === 0 || reset
  error.value = null
  try {
    data.value = await listMyReviews({ sort: sort.value, page: pageNo.value, size: PAGE_SIZE })
  } catch (e) {
    const msg = toMessage(e)
    if (msg) error.value = msg
  } finally {
    loading.value = false
  }
}

onMounted(() => fetchList(true))
watch(sort, () => fetchList(true))

async function loadMore () {
  pageNo.value += 1
  await fetchList()
}

function toggle (id) {
  const s = new Set(expanded.value)
  s.has(id) ? s.delete(id) : s.add(id)
  expanded.value = s
}

/* ── MY_005 삭제 ── */
async function confirmDelete () {
  if (!deleting.value) return
  deleteBusy.value = true
  try {
    const res = await deleteMyReview(deleting.value.reviewId)
    const r = res.placeRating
    ui.toast(
      res.alreadyDeleted
        ? '이미 지워진 리뷰였어요'
        : r && r.count
          ? `리뷰를 삭제했어요 · ${deleting.value.placeName} 평점 ${r.average}점 (${r.count}개)로 다시 계산했어요`
          : `리뷰를 삭제했어요 · ${deleting.value.placeName}에는 이제 리뷰가 없어요`
    )
    deleting.value = null
    await fetchList(true)
  } catch (e) {
    const msg = toMessage(e)
    if (msg) ui.toast(msg)
  } finally {
    deleteBusy.value = false
  }
}

/** 첨부 사진: 실제 이미지가 없어 'photo:카테고리' 형태의 자리표시자로 저장돼 있다 */
const photoCategory = url => String(url || '').replace(/^photo:/, '').replace(/\d+$/, '')
</script>

<template>
  <section>
    <div class="bar-top">
      <div class="sect">작성한 리뷰 <span class="cnt tnum">{{ data.total }}</span></div>
      <SortSeg v-model="sort" :options="REVIEW_SORTS" label="리뷰 정렬 기준" />
    </div>

    <StateBlock :loading="loading" :error="error" :rows="3" @retry="fetchList(true)" />

    <template v-if="!loading && !error">
      <EmptyState
        v-if="!data.items.length"
        title="작성한 리뷰가 없어요"
        hint="지도에서 장소를 열고 별점과 혼잡 제보를 남기면 여기에 모여요."
        action-label="장소 둘러보기"
        action-to="/map"
      />

      <ul v-else class="list">
        <li v-for="r in data.items" :key="r.reviewId">
          <article class="card">
            <!-- 알림 카드와 같은 자리 — 오른쪽 위에 장소 보기 + 삭제 -->
            <div class="corner">
              <RouterLink
                class="cbtn"
                :to="{ name: 'map', query: { place: r.placeId } }"
                :aria-label="`${r.placeName} 장소 보기`"
              >장소 보기</RouterLink>
              <button
                class="x"
                type="button"
                :aria-label="`${r.placeName} 리뷰 삭제`"
                @click="deleting = r"
              >
                <svg width="14" height="14" viewBox="0 0 24 24" aria-hidden="true">
                  <path d="M6 6l12 12M18 6L6 18" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" />
                </svg>
              </button>
            </div>

            <div class="top">
              <RouterLink :to="{ name: 'map', query: { place: r.placeId } }" class="place">
                {{ r.placeName }}
              </RouterLink>
              <span v-if="r.placeCategory" class="bdg neutral">{{ r.placeCategory }}</span>
              <span v-if="r.crowdReport" class="bdg" :class="r.crowdReport">{{ CROWD_KO[r.crowdReport] }}</span>
            </div>

            <div class="rate">
              <StarRating :model-value="r.rating" :size="14" />
              <span class="score tnum">{{ r.rating }}.0</span>
              <span class="dates note">
                {{ fmtK(r.createdAt) }} 작성<template v-if="r.edited"> · {{ fmtK(r.updatedAt) }} 수정</template>
              </span>
            </div>

            <p class="content">
              {{ expanded.has(r.reviewId) ? r.content : truncate(r.content, PREVIEW_LEN) }}
              <button
                v-if="r.content.length > PREVIEW_LEN"
                class="expand"
                @click="toggle(r.reviewId)"
              >{{ expanded.has(r.reviewId) ? '접기' : '더보기' }}</button>
            </p>

            <div v-if="r.photos.length" class="photos">
              <PlaceThumb
                v-for="p in r.photos"
                :key="p.sortOrder"
                :category="photoCategory(p.url)"
                :name="r.placeName"
                size="56px"
                radius="10px"
              />
              <span class="note ph">사진 {{ r.photos.length }}장</span>
            </div>

          </article>
        </li>
      </ul>

      <button v-if="data.hasMore" class="btn2 more-btn" @click="loadMore">
        더보기 <span class="tnum">({{ data.items.length }} / {{ data.total }})</span>
      </button>
    </template>

    <!-- MY_005 삭제 확인: 장소명 + 리뷰 내용 노출 -->
    <ConfirmDeleteDialog
      v-if="deleting"
      title="이 리뷰를 삭제할까요?"
      :subject="deleting.placeName"
      :detail="deleting.content"
      :warning="deleting.photos.length ? `첨부한 사진 ${deleting.photos.length}장도 함께 지워져요.` : ''"
      confirm-label="삭제할게요"
      :busy="deleteBusy"
      @close="deleting = null"
      @confirm="confirmDelete"
    />

  </section>
</template>

<style scoped>
.bar-top { display: flex; align-items: center; gap: 12px; margin-bottom: 12px; flex-wrap: wrap; }
.bar-top .sect { margin: 0; flex: 1; }
.cnt { color: var(--tx3); font-weight: 700; }

.list { display: flex; flex-direction: column; gap: 10px; }

/* 오른쪽 위 조작 묶음 (알림 카드의 X 자리와 같은 좌표) */
.card { position: relative; }
.corner {
  position: absolute; top: 10px; right: 10px;
  display: flex; align-items: center; gap: 4px;
}
.cbtn {
  padding: 6px 12px; border-radius: var(--rp);
  background: var(--surf2); color: var(--tx2);
  font-family: var(--font-head); font-size: 11.5px; font-weight: 700; white-space: nowrap;
  transition: background .15s, color .15s;
}
.cbtn:hover { background: var(--ac-bg); color: var(--ac-dk); }
.x {
  width: 28px; height: 28px; border-radius: 50%;
  display: inline-flex; align-items: center; justify-content: center;
  color: var(--tx3); flex-shrink: 0;
  transition: background .15s, color .15s;
}
.x:hover { background: var(--busy-bg); color: var(--busy); }

/* 조작 묶음과 겹치지 않게 첫 줄 오른쪽을 비워 둔다 */
.top { display: flex; align-items: center; gap: 7px; flex-wrap: wrap; padding-right: 108px; }
.place { font-size: 15px; font-weight: 800; letter-spacing: -.03em; }
.place:hover { color: var(--ac-dk); }

.bdg.calm { background: var(--calm-bg); color: var(--calm); }
.bdg.mid  { background: var(--mid-bg);  color: var(--mid); }
.bdg.busy { background: var(--busy-bg); color: var(--busy); }

.rate { display: flex; align-items: center; gap: 7px; margin: 8px 0 6px; flex-wrap: wrap; }
.score { font-size: 12.5px; font-weight: 800; }
.dates { margin-left: 2px; }

.content { font-size: 12.5px; color: var(--tx2); line-height: 1.65; }
.expand { font-size: 11.5px; font-weight: 700; color: var(--ac-dk); margin-left: 4px; }
.expand:hover { text-decoration: underline; }

.photos { display: flex; align-items: center; gap: 6px; margin-top: 10px; flex-wrap: wrap; }
.photos .ph { margin-left: 2px; }

.more-btn { width: 100%; margin-top: 10px; }

/* 좁은 화면에서는 첫 줄을 조작 묶음 아래로 내린다 (여백만 비우면 글자가 눌린다) */
@media (max-width: 480px) {
  .corner { position: static; justify-content: flex-end; margin-bottom: 8px; }
  .top { padding-right: 0; }
}
</style>
