<script setup lang="ts">
import { useDraftGuard } from "../drafts";
import { ref, watch } from "vue";
import {
  api,
  errorText,
  type Page,
  type Product,
  type Rule,
  type Definition,
} from "../api";
import DatasetPicker from "./DatasetPicker.vue";
import RuleEditor from "./RuleEditor.vue";
const props = defineProps<{ caseId: string }>(),
  emit = defineEmits<{ saved: [] }>();
const datasetId = ref(""),
  quote = ref(""),
  rule = ref<Rule>({ op: "EQ", field: "LOT_NUMBER", values: [""] }),
  reviews = ref<Definition["productReviews"]>({}),
  products = ref<Page<Product>>(),
  page = ref(0),
  q = ref(""),
  error = ref(""),
  busy = ref(false),
  loading = ref(false);
const draft = useDraftGuard(() => ({
  datasetId: datasetId.value,
  quote: quote.value,
  rule: rule.value,
  reviews: reviews.value,
}));
let generation = 0;
async function load(reset = false) {
  if (reset) page.value = 0;
  const ticket = ++generation;
  loading.value = true;
  try {
    const result = await api<Page<Product>>(
      `/api/v1/datasets/${datasetId.value}/products?` +
        new URLSearchParams({
          page: String(page.value),
          size: "10",
          q: q.value,
        }),
    );
    if (ticket === generation) products.value = result;
  } catch (e) {
    if (ticket === generation) error.value = errorText(e);
  } finally {
    if (ticket === generation) loading.value = false;
  }
}
watch(datasetId, () => {
  reviews.value = {};
  products.value = undefined;
  q.value = "";
  if (datasetId.value) load(true);
});
function setReview(id: string, event: Event) {
  const status = (event.target as HTMLSelectElement).value;
  if (!status) delete reviews.value[id];
  else reviews.value[id] = { status, reason: reviews.value[id]?.reason || "" };
}
async function save() {
  if (busy.value) return;
  error.value = "";
  busy.value = true;
  try {
    await api(`/api/v1/recalls/${props.caseId}/conditions`, {
      datasetId: datasetId.value,
      sourceQuote: quote.value,
      rule: rule.value,
      productReviews: reviews.value,
    });
    draft.saved();
    emit("saved");
  } catch (e) {
    error.value = errorText(e);
  } finally {
    busy.value = false;
  }
}
</script>
<template>
  <form @submit.prevent="save">
    <h2>새 조건 초안</h2>
    <DatasetPicker v-model="datasetId" /><label class="spaced"
      >원문 근거 인용<textarea
        v-model="quote"
        required
        maxlength="10000"
        rows="3"
        placeholder="회수 원문에서 조건을 설명하는 문장을 그대로 입력하세요"
      />
    </label>
    <h3>회수 대상 조건</h3>
    <RuleEditor v-model="rule" /><template v-if="datasetId"
      ><h3 class="spaced">상품 연결 검토</h3>
      <p class="note">
        연결 확인한 상품에만 조건을 적용합니다. 검토하지 않은 상품은 확인 필요로
        남습니다. 최소 한 상품을 연결 확인하세요.
      </p>
      <div class="inline">
        <input
          v-model="q"
          aria-label="상품 검색"
          placeholder="상품명 또는 상품 ID 검색"
          maxlength="200"
        /><button type="button" :disabled="loading" @click="load(true)">
          상품 검색
        </button>
      </div>
      <div class="table-scroll">
        <table>
          <thead>
            <tr>
              <th>상품</th>
              <th>제조사 · 규격</th>
              <th>연결 검토</th>
              <th>검토 근거</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="product in products?.items" :key="product.id">
              <td>
                {{ product.name }}<small>{{ product.id }}</small>
              </td>
              <td>{{ product.manufacturer }} · {{ product.packSize }}</td>
              <td>
                <select
                  :value="reviews[product.id]?.status || ''"
                  :aria-label="product.id + ' 연결 검토'"
                  @change="setReview(product.id, $event)"
                >
                  <option value="">미검토</option>
                  <option value="MATCHED">연결 확인</option>
                  <option value="EXCLUDED">연결 제외</option>
                </select>
              </td>
              <td>
                <input
                  v-if="reviews[product.id]"
                  v-model="reviews[product.id]!.reason"
                  required
                  maxlength="2000"
                  :aria-label="product.id + ' 검토 근거'"
                  placeholder="제조사·규격 등 확인 근거"
                />
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div class="pagination">
        <span
          >{{ Object.keys(reviews).length }}개 상품 검토 · {{ page + 1 }} /
          {{ products?.totalPages || 1 }} 페이지</span
        >
        <div>
          <button
            type="button"
            :disabled="page === 0 || loading"
            @click="
              page--;
              load();
            "
          >
            이전</button
          ><button
            type="button"
            :disabled="!products || page + 1 >= products.totalPages || loading"
            @click="
              page++;
              load();
            "
          >
            다음
          </button>
        </div>
      </div>
      <details v-if="Object.keys(reviews).length">
        <summary>선택한 모든 상품 검토 확인</summary>
        <p v-for="(review, id) in reviews" :key="id">
          {{ id }} ·
          {{ review.status === "MATCHED" ? "연결 확인" : "연결 제외" }} ·
          {{ review.reason || "근거 미입력" }}
        </p>
      </details></template
    >
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <button class="primary" :disabled="busy || loading || !datasetId">
      {{ busy ? "저장 중…" : "조건 초안 저장" }}
    </button>
    <p class="footnote spaced">
      저장 후 별도 승인이 필요합니다. 승인 전에는 판정할 수 없습니다.
    </p>
  </form>
</template>
<style scoped>
.spaced {
  margin-top: 24px;
}
.inline input {
  flex: 1;
  min-width: 150px;
}
td select {
  min-width: 130px;
}
td input {
  min-width: 200px;
}
</style>
