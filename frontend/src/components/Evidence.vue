<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch } from "vue";
import {
  api,
  user,
  errorText,
  label,
  dateText,
  type Page,
  type Assessment,
} from "../api";
const props = defineProps<{
    caseId: string;
    closed: boolean;
    assessment?: Assessment;
  }>(),
  emit = defineEmits<{ updated: []; showAssessment: [id: string] }>();
interface Row {
  id: string;
  receiptId: string;
  status: string;
  createdAt: string;
  reviewedBy: string | null;
  resultAssessmentId: string | null;
}
interface Facts {
  productId: string;
  receivedQuantity: number;
  receivedAt: string;
  lotNumber: string | null;
  expiryDate: string | null;
}
interface Proposal {
  baseAssessmentId: string;
  receiptId: string;
  documentText: string;
  sourceQuote: string;
  observedProductId: string;
  observedReceivedQuantity: number;
  observedReceivedAt: string;
  lotNumber: string | null;
  expiryDate: string | null;
}
interface Detail extends Row {
  proposal: Proposal;
  before: Facts;
  after?: Facts;
  issues: { field: string; code: string; message: string }[];
  reviewNote: string | null;
}
const data = ref<Page<Row>>(),
  page = ref(0),
  status = ref(""),
  selected = ref<Detail>(),
  error = ref(""),
  success = ref(""),
  busy = ref(false),
  loading = ref(false),
  creating = ref(false),
  confirmed = ref(false),
  note = ref("");
const receiptId = ref(""),
  documentText = ref(""),
  sourceQuote = ref(""),
  product = ref(""),
  quantity = ref<number>(),
  receivedAt = ref(""),
  lot = ref(""),
  expiry = ref("");
const reviewer = computed(() => user.value?.roles.includes("REVIEWER"));
let generation = 0,
  detailGeneration = 0;
async function load(reset = false) {
  if (reset) page.value = 0;
  const ticket = ++generation;
  loading.value = true;
  try {
    const result = await api<Page<Row>>(
      `/api/v1/recalls/${props.caseId}/evidence?` +
        new URLSearchParams({
          status: status.value,
          page: String(page.value),
          size: "10",
        }),
    );
    if (ticket === generation) data.value = result;
  } catch (e) {
    if (ticket === generation) error.value = errorText(e);
  } finally {
    if (ticket === generation) loading.value = false;
  }
}
async function select(id: string) {
  const ticket = ++detailGeneration;
  selected.value = undefined;
  confirmed.value = false;
  note.value = "";
  error.value = "";
  try {
    const result = await api<Detail>(
      `/api/v1/recalls/${props.caseId}/evidence/${id}`,
    );
    if (ticket === detailGeneration) selected.value = result;
  } catch (e) {
    if (ticket === detailGeneration) error.value = errorText(e);
  }
}
async function create() {
  if (!props.assessment) return;
  busy.value = true;
  error.value = "";
  success.value = "";
  try {
    if (!lot.value && !expiry.value)
      throw new Error("제조번호 또는 소비기한 보완값을 입력하세요.");
    const result = await api<Detail>(
      `/api/v1/recalls/${props.caseId}/evidence`,
      {
        baseAssessmentId: props.assessment.id,
        receiptId: receiptId.value,
        documentText: documentText.value,
        sourceQuote: sourceQuote.value,
        observedProductId: product.value,
        observedReceivedQuantity: quantity.value,
        observedReceivedAt: receivedAt.value,
        lotNumber: lot.value || null,
        expiryDate: expiry.value || null,
      },
    );
    selected.value = result;
    creating.value = false;
    confirmed.value = false;
    note.value = "";
    success.value =
      "증거를 등록했습니다. 검토·승인 전에는 판정이 바뀌지 않습니다.";
    await load(true);
    emit("updated");
  } catch (e) {
    error.value = errorText(e);
  } finally {
    busy.value = false;
  }
}
async function review(approve: boolean) {
  if (!selected.value) return;
  busy.value = true;
  error.value = "";
  try {
    selected.value = await api<Detail>(
      `/api/v1/recalls/${props.caseId}/evidence/${selected.value.id}/${approve ? "approval" : "rejection"}`,
      {
        note: note.value,
        ...(approve ? { receiptAndSingleLotConfirmed: confirmed.value } : {}),
      },
    );
    success.value = approve
      ? "승인과 재판정을 완료했습니다. 기존 판정은 보존됩니다."
      : "증거를 반려했습니다.";
    confirmed.value = false;
    note.value = "";
    await load();
    emit("updated");
  } catch (e) {
    error.value = errorText(e);
  } finally {
    busy.value = false;
  }
}
watch(
  () => props.assessment?.id,
  () => {
    creating.value = false;
    receiptId.value = "";
    documentText.value = "";
    sourceQuote.value = "";
    product.value = "";
    quantity.value = undefined;
    receivedAt.value = "";
    lot.value = "";
    expiry.value = "";
  },
);
onMounted(() => load());
onUnmounted(() => {
  generation++;
  detailGeneration++;
});
</script>
<template>
  <fieldset :disabled="busy" class="evidence-surface">
    <section class="panel">
      <div class="section-heading">
        <h2>입고 증거 보완</h2>
        <button
          v-if="!closed"
          :disabled="!assessment"
          @click="creating = !creating"
        >
          {{ creating ? "등록 닫기" : "입고 증거 등록" }}
        </button>
      </div>
      <p class="note">
        문서로 확인한 누락 제조번호·소비기한만 보완합니다. 승인하면 새 데이터와
        조건 버전으로 재판정하며, 출고 연결을 추정하지 않습니다.
      </p>
      <p v-if="error" class="error" role="alert">{{ error }}</p>
      <p v-if="success" class="success" role="status">{{ success }}</p>
      <form v-if="creating && assessment" @submit.prevent="create">
        <p class="small muted">
          기준 판정 {{ assessment.id.slice(0, 8) }} · 문서에서 확인한 사실을
          직접 입력하세요.
        </p>
        <label
          >보완할 입고<select
            v-model="receiptId"
            required
            aria-label="보완할 입고"
          >
            <option value="">입고 선택</option>
            <option
              v-for="receipt in assessment.receipts"
              :key="receipt.receiptId"
              :value="receipt.receiptId"
            >
              {{ receipt.receiptId }} · {{ receipt.productId }} ·
              {{ label(receipt.decision) }}
            </option>
          </select></label
        ><label
          >입고 증거 원문<textarea
            v-model="documentText"
            required
            maxlength="100000"
            rows="5"
          /></label
        ><label
          >증거 근거 인용<textarea
            v-model="sourceQuote"
            required
            maxlength="10000"
            rows="3"
          />
        </label>
        <div class="form-grid">
          <label
            >문서의 상품 ID<input
              v-model="product"
              required
              maxlength="500" /></label
          ><label
            >문서의 전체 입고 수량<input
              v-model.number="quantity"
              required
              type="number"
              min="0"
              max="1000000000"
              step="1"
          /></label>
        </div>
        <label
          >문서의 입고일<input v-model="receivedAt" required type="date"
        /></label>
        <div class="form-grid">
          <label>보완 제조번호<input v-model="lot" maxlength="500" /></label
          ><label>보완 소비기한<input v-model="expiry" type="date" /></label>
        </div>
        <button class="primary">증거 제안 저장</button>
      </form>
      <div class="inline">
        <select v-model="status" aria-label="증거 상태" @change="load(true)">
          <option value="">모든 증거</option>
          <option value="PENDING">검토 대기</option>
          <option value="APPROVED">승인 완료</option>
          <option value="REJECTED">반려</option></select
        ><button @click="load()">새로고침</button>
      </div>
      <p v-if="loading" class="empty" role="status">
        증거를 불러오는 중입니다…
      </p>
      <p v-else-if="!data?.items.length" class="empty">
        등록된 입고 증거가 없습니다.
      </p>
      <div v-else class="table-scroll">
        <table>
          <thead>
            <tr>
              <th>입고</th>
              <th>상태</th>
              <th>등록일</th>
              <th>검토자</th>
              <th>상세</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in data.items" :key="item.id">
              <td>{{ item.receiptId }}</td>
              <td>
                <span class="badge" :class="item.status">{{
                  label(item.status)
                }}</span>
              </td>
              <td>{{ dateText(item.createdAt) }}</td>
              <td>{{ item.reviewedBy || "미검토" }}</td>
              <td><button @click="select(item.id)">증거 상세</button></td>
            </tr>
          </tbody>
        </table>
      </div>
      <footer class="pagination">
        <span
          >{{ data?.totalPages ? page + 1 : 0 }} /
          {{ data?.totalPages || 0 }} 페이지</span
        >
        <div>
          <button
            :disabled="page === 0 || loading"
            @click="
              page--;
              load();
            "
          >
            이전</button
          ><button
            :disabled="!data || page + 1 >= data.totalPages || loading"
            @click="
              page++;
              load();
            "
          >
            다음
          </button>
        </div>
      </footer>
    </section>
    <section v-if="selected" class="panel">
      <div class="section-heading">
        <h2>{{ selected.proposal.receiptId }} 증거 검토</h2>
        <span class="badge" :class="selected.status">{{
          label(selected.status)
        }}</span>
      </div>
      <p class="small muted">
        기준 판정 {{ selected.proposal.baseAssessmentId.slice(0, 8) }}
      </p>
      <h3>증거 원문</h3>
      <p class="prewrap">{{ selected.proposal.documentText }}</p>
      <h3>인용 근거</h3>
      <blockquote class="prewrap">
        {{ selected.proposal.sourceQuote }}
      </blockquote>
      <div class="table-scroll">
        <table>
          <thead>
            <tr>
              <th>확인 항목</th>
              <th>기존 데이터</th>
              <th>문서에서 확인한 사실</th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <th>상품 ID</th>
              <td>{{ selected.before.productId }}</td>
              <td>{{ selected.proposal.observedProductId }}</td>
            </tr>
            <tr>
              <th>전체 입고 수량</th>
              <td>{{ selected.before.receivedQuantity }}</td>
              <td>{{ selected.proposal.observedReceivedQuantity }}</td>
            </tr>
            <tr>
              <th>입고일</th>
              <td>{{ selected.before.receivedAt }}</td>
              <td>{{ selected.proposal.observedReceivedAt }}</td>
            </tr>
            <tr>
              <th>제조번호</th>
              <td>{{ selected.before.lotNumber || "누락" }}</td>
              <td>{{ selected.proposal.lotNumber || "보완 없음" }}</td>
            </tr>
            <tr>
              <th>소비기한</th>
              <td>{{ selected.before.expiryDate || "누락" }}</td>
              <td>{{ selected.proposal.expiryDate || "보완 없음" }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <p
        v-for="issue in selected.issues"
        :key="issue.field + issue.code"
        class="error"
      >
        {{ issue.message }}
      </p>
      <p v-if="selected.reviewNote" class="note">
        검토 기록: {{ selected.reviewNote }} · {{ selected.reviewedBy }}
      </p>
      <form
        v-if="reviewer && !closed && selected.status === 'PENDING'"
        @submit.prevent
      >
        <label class="check-label"
          ><input v-model="confirmed" type="checkbox" />입고 건·상품·전체
          수량·입고일이 일치하고 단일 제조분임을 증거로 확인했습니다.</label
        ><label
          >입고 증거 검토 사유<textarea
            v-model="note"
            maxlength="2000"
            required
          />
        </label>
        <div class="inline">
          <button
            class="primary"
            :disabled="!confirmed || !note.trim() || !!selected.issues.length"
            @click="review(true)"
          >
            증거 승인 및 재판정</button
          ><button
            class="danger"
            :disabled="!note.trim()"
            @click="review(false)"
          >
            증거 반려
          </button>
        </div>
      </form>
      <button
        v-if="selected.resultAssessmentId"
        class="primary"
        @click="emit('showAssessment', selected.resultAssessmentId)"
      >
        보완 후 판정 보기
      </button>
    </section>
  </fieldset>
</template>
<style scoped>
.evidence-surface {
  border: 0;
  padding: 0;
  margin: 0;
  min-inline-size: 0;
}
form {
  margin: 22px 0;
}
.inline select {
  max-width: 220px;
}
</style>
