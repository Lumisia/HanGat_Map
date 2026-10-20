<script setup>
/** 내 리뷰 조회·삭제. 서버가 반환한 실제 사진과 장소 ID를 사용한다. */
import { onBeforeUnmount, ref, watch } from 'vue'
import StarRating from '../../components/mypage/StarRating.vue'
import EmptyState from '../../components/common/EmptyState.vue'
import StateBlock from '../../components/common/StateBlock.vue'
import ConfirmDeleteDialog from '../../components/mypage/ConfirmDeleteDialog.vue'
import PlaceImage from '../../components/common/PlaceImage.vue'
import ReviewEditDialog from '../../components/review/ReviewEditDialog.vue'
import { useReviewEditWindow } from '../../composables/useReviewEditWindow.js'
import { listMyReviews, deleteMyReview, REVIEW_SORTS, mediaUrl, canHandleActivityError } from '../../api/myActivity.js'
import { getBackendSessionVersion } from '../../api/backendClient.js'
import { useAuthStore } from '../../stores/auth.js'
import { useUiStore } from '../../stores/ui.js'
import { useApiError } from '../../composables/useApiError.js'
import { useMyPageRows } from '../../composables/useMyPageRows.js'
import { fmtK } from '../../utils/format.js'
import ListPagination from '../../components/mypage/ListPagination.vue'

const auth = useAuthStore()
const emit = defineEmits(['reviews-changed'])
const ui = useUiStore()
const toMessage = useApiError()
const sort = ref('created_desc')
const { items, total, number, totalPages, loading, error, load, retry } = useMyPageRows(listMyReviews, sort, 10, toMessage)
const deleting = ref(null)
const editing = ref(null)
const { canEdit } = useReviewEditWindow()
async function onEdited() {
  editing.value = null
  ui.toast('리뷰를 저장했어요')
  emit('reviews-changed')
  await load()
}
const deleteBusy = ref(false)
let alive = true
let deleteVersion = 0
watch(() => auth.user?.userId, () => { deleteVersion += 1; deleting.value = null; deleteBusy.value = false }, { flush: 'sync' })
watch(() => auth.user?.userId, () => { editing.value = null }, { flush: 'sync' })
onBeforeUnmount(() => { alive = false })

async function confirmDelete() {
  if (!deleting.value || deleteBusy.value) return
  const review = deleting.value
  const version = ++deleteVersion
  const userId = auth.user?.userId
  const epoch = getBackendSessionVersion()
  const isCurrent = () => alive && epoch === getBackendSessionVersion()
  deleteBusy.value = true
  try {
    // 기존 삭제 API의 성공 결과는 평점 객체가 아닌 null이다.
    await deleteMyReview(review.reviewId)
    if (!isCurrent()) return
    deleting.value = null
    ui.toast('리뷰를 삭제했어요')
    emit('reviews-changed')
    await load()
  } catch (e) {
    if (!alive || version !== deleteVersion || !canHandleActivityError(e, epoch, userId, auth.user?.userId)) return
    const message = toMessage(e)
    if (message) ui.toast(message)
  } finally {
    if (alive && version === deleteVersion) deleteBusy.value = false
  }
}
</script>

<template>
  <section>
    <div class="bar-top">
      <div class="heading"><h2 class="sect">작성한 리뷰 <span class="cnt tnum">총 {{ total }}건</span></h2>
        <p class="intro">직접 다녀온 제주의 순간을 기록하고 공유해요.</p></div>
      <label class="review-sort">정렬
        <select v-model="sort" aria-label="리뷰 정렬 기준">
          <option v-for="option in REVIEW_SORTS" :key="option.key" :value="option.key">{{ option.label }}</option>
        </select>
      </label>
    </div>
    <StateBlock :loading="loading && !items.length" :error="error" :rows="3" @retry="retry()" />
    <EmptyState v-if="!loading && !error && !items.length" title="작성한 리뷰가 없어요"
      hint="지도에서 장소를 열고 별점과 혼잡 제보를 남기면 여기에 모여요."
      action-label="장소 둘러보기" action-to="/map" />
    <ul v-if="items.length" class="list">
      <li v-for="r in items" :key="r.reviewId">
        <article class="card">
          <div class="top">
            <RouterLink :to="{ name: 'map', query: { place: r.placeId } }" class="place">{{ r.placeName }}</RouterLink>
            <span v-if="r.placeCategory" class="bdg neutral">{{ r.placeCategory }}</span>
          </div>
          <div class="rate">
            <template v-if="r.rating != null">
              <StarRating :model-value="r.rating" :size="14" />
              <span class="score tnum">{{ Number(r.rating).toFixed(1) }}</span>
            </template>
            <span v-else class="note">별점 없음</span>
            <!-- 혼잡 제보 배지(한산했어요 등)는 더 이상 보여주지 않는다(2026-09-18) - 작성·수정 화면에서 제보 입력을 뺐다 -->
          </div>
          <p v-if="r.content" class="content">{{ r.content }}</p>
          <div v-if="r.imageUrls?.length" class="photos">
            <template v-for="(url, index) in r.imageUrls" :key="`${index}-${url}`">
              <a v-if="mediaUrl(url)" :href="mediaUrl(url)" target="_blank" rel="noopener noreferrer" :aria-label="`${r.placeName} 리뷰 사진 ${index + 1} 크게 보기`">
                <PlaceImage :src="mediaUrl(url)" :alt="`${r.placeName} 리뷰 사진 ${index + 1}`" />
              </a>
            </template>
          </div>
          <footer class="review-footer">
            <p class="dates note">{{ fmtK(r.createdAt) }} 작성 <span v-if="r.editedAt">(수정)</span></p>
            <div class="review-actions">
              <RouterLink class="cbtn" :to="{ name: 'map', query: { place: r.placeId } }">장소 보기</RouterLink>
              <button v-if="canEdit(r)" class="cbtn" type="button" :aria-label="`${r.placeName} 리뷰 수정`" @click="editing = r">수정</button>
              <button class="cbtn delete" type="button" :aria-label="`${r.placeName} 리뷰 삭제`" @click="deleting = r">삭제</button>
            </div>
          </footer>
        </article>
      </li>
    </ul>
    <ListPagination :page="number" :total-pages="totalPages" :busy="loading" label="리뷰 페이지" @change="load" />
    <ReviewEditDialog v-if="editing" :key="editing.reviewId" :review="editing" @close="editing = null" @saved="onEdited" />
    <ConfirmDeleteDialog v-if="deleting" :key="deleting.reviewId" title="이 리뷰를 삭제할까요?"
      :subject="deleting.placeName" :detail="deleting.content || ''"
      :warning="deleting.imageUrls?.length ? `첨부한 사진 ${deleting.imageUrls.length}장도 함께 지워져요.` : ''"
      confirm-label="삭제할게요" :busy="deleteBusy"
      @close="!deleteBusy && (deleting = null)" @confirm="confirmDelete" />
  </section>
</template>

<style scoped>
.bar-top { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-bottom: 24px; flex-wrap: wrap; }
.heading { min-width: 0; }
.intro { font-size: 13px; color: var(--tx2); margin-top: 6px; line-height: 1.6; }
.review-sort { display: flex; align-items: center; gap: 8px; color: var(--tx2); font-size: 12px; }
.review-sort select { min-height: 40px; max-width: 100%; padding: 8px 28px 8px 10px; border: 1px solid var(--line); border-radius: 8px; background: var(--surf2); color: var(--tx2); font: inherit; }
.review-sort select:focus-visible { outline: 2px solid var(--ac); outline-offset: 2px; }
.bar-top .sect { margin: 0; flex: 1; }
.cnt { color: var(--tx3); font-weight: 700; }
.list { display: flex; flex-direction: column; gap: 24px; list-style: none; padding: 0; }
.card { padding: 24px; }
.cbtn { display: inline-flex; align-items: center; min-height: 44px; padding: 8px 10px; border-radius: 8px; color: var(--tx2); font-size: 12px; font-weight: 600; white-space: nowrap; }
.cbtn:hover { background: var(--ac-bg); color: var(--ac-dk); }
.delete { color: var(--busy); }
.delete:hover { background: var(--busy-bg); color: var(--busy); }
.top { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.place { font-size: 17px; font-weight: 800; overflow-wrap: anywhere; }
.place:hover { color: var(--ac-dk); }
.rate { display: flex; align-items: center; gap: 8px; margin: 10px 0 18px; flex-wrap: wrap; }
.score { font-size: 12.5px; font-weight: 800; }
.content { font-size: 14px; color: var(--tx2); line-height: 1.8; white-space: pre-wrap; overflow-wrap: anywhere; }
.photos { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; margin-top: 20px; }
.photos a { min-width: 0; }
.photos img { display: block; width: 100%; aspect-ratio: 4 / 3; height: auto; object-fit: cover; border-radius: 12px; border: 1px solid var(--line); }
.review-footer { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 8px; border-top: 1px solid var(--line); margin-top: 20px; padding-top: 12px; }
.review-actions { display: flex; gap: 4px; margin-left: auto; }
.dates { margin: 0; font-size: 11px; }
button:focus-visible, a:focus-visible { outline: 2px solid var(--ac); outline-offset: 3px; }
@media (max-width: 480px) {
  .card { padding: 18px; }
  .list { gap: 16px; }
  .photos { grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; }
}
</style>
