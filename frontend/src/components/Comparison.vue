<script setup lang="ts">
import { ref, computed, watch, onBeforeUnmount } from "vue";
import { api, errorText, dateText, type Assessment, type RunRow } from "../api";
import { compareAssessments } from "../comparison";
const props = defineProps<{ caseId: string; runs: RunRow[] }>();
const beforeId = ref(""),
  afterId = ref(""),
  before = ref<Assessment>(),
  after = ref<Assessment>(),
  error = ref(""),
  busy = ref(false),
  onlyChanged = ref(true),
  kind = ref<"receipts" | "inventory" | "shipments">("inventory"),
  page = ref(0);
let generation = 0;
function clear() {
  ++generation;
  before.value = undefined;
  after.value = undefined;
  error.value = "";
  busy.value = false;
  page.value = 0;
}
watch(
  () => props.runs,
  () => {
    clear();
    afterId.value = props.runs[0]?.id ?? "";
    beforeId.value = props.runs[1]?.id ?? "";
  },
  { immediate: true },
);
watch([beforeId, afterId], clear);
watch([kind, onlyChanged], () => (page.value = 0));
onBeforeUnmount(() => ++generation);
async function compare() {
  clear();
  if (!beforeId.value || !afterId.value || beforeId.value === afterId.value) {
    error.value = "서로 다른 두 판정을 선택하세요.";
    return;
  }
  const ticket = generation;
  busy.value = true;
  try {
    const [a, b] = await Promise.all([
      api<Assessment>(
        `/api/v1/recalls/${props.caseId}/assessments/${beforeId.value}`,
      ),
      api<Assessment>(
        `/api/v1/recalls/${props.caseId}/assessments/${afterId.value}`,
      ),
    ]);
    if (ticket === generation) {
      before.value = a;
      after.value = b;
    }
  } catch (e) {
    if (ticket === generation) error.value = errorText(e);
  } finally {
    if (ticket === generation) busy.value = false;
  }
}
const all = computed(() =>
  before.value && after.value
    ? compareAssessments(before.value, after.value)
    : undefined,
);
const rows = computed(
  () =>
    all.value?.[kind.value].filter(
      (row) => !onlyChanged.value || row.changed,
    ) ?? [],
);
const visible = computed(() =>
  rows.value.slice(page.value * 20, page.value * 20 + 20),
);
const status = {
  added: "비교 쪽에만 있음",
  removed: "기준 쪽에만 있음",
  changed: "값 변경",
  same: "동일",
};
</script>
<template>
  <section class="panel">
    <h2>판정 결과 비교</h2>
    <p class="note">
      기준 판정과 비교 판정의 저장 결과를 나란히 봅니다. 증감은 비교 − 기준이며,
      비교 순서는 직접 선택합니다. 작업 완료나 실제 회수 실적을 뜻하지 않습니다.
    </p>
    <p v-if="runs.length < 2" class="empty">
      비교하려면 저장된 판정이 2개 이상 필요합니다.
    </p>
    <form v-else @submit.prevent="compare">
      <fieldset :disabled="busy" class="selection">
        <label
          >기준 판정<select v-model="beforeId" aria-label="기준 판정">
            <option value="">선택</option>
            <option v-for="r in runs" :key="r.id" :value="r.id">
              {{ dateText(r.createdAt) }} · {{ r.id }}
            </option>
          </select></label
        ><label
          >비교 판정<select v-model="afterId" aria-label="비교 판정">
            <option value="">선택</option>
            <option v-for="r in runs" :key="r.id" :value="r.id">
              {{ dateText(r.createdAt) }} · {{ r.id }}
            </option>
          </select></label
        ><button
          class="primary"
          :disabled="!beforeId || !afterId || beforeId === afterId"
        >
          {{ busy ? "비교 중…" : "판정 비교 실행" }}
        </button>
      </fieldset>
    </form>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
  </section>
  <template v-if="before && after"
    ><section class="panel">
      <h2>비교 기준</h2>
      <p class="identifier">
        기준 조건 {{ before.conditionId }} · 데이터 {{ before.datasetId }}
      </p>
      <p class="identifier">
        비교 조건 {{ after.conditionId }} · 데이터 {{ after.datasetId }}
      </p>
      <p v-if="before.datasetId !== after.datasetId" class="note">
        데이터 버전이 다릅니다. 아래 행은 같은 기록 ID를 기준으로 배치한 값
        비교이며, 같은 실물·거래라는 보장은 없습니다. 기록 추가·제외와 수량
        변화가 섞일 수 있으므로 판정 개선이나 회수 실적으로 해석하지 마세요.
      </p>
      <p v-else class="muted">같은 데이터 버전의 판정입니다.</p>
      <div class="table-scroll">
        <table aria-label="판정 수량 비교">
          <thead>
            <tr>
              <th>범위</th>
              <th>분류</th>
              <th>기준 EA</th>
              <th>비교 EA</th>
              <th>증감 EA</th>
            </tr>
          </thead>
          <tbody>
            <template
              v-for="[field, title] in [
                ['inventoryTotals', '재고'],
                ['shipmentTotals', '출고'],
              ] as const"
              :key="field"
              ><tr
                v-for="[metric, name] in [
                  ['target', '대상'],
                  ['nonTarget', '비대상'],
                  ['needsReview', '확인 필요'],
                ] as const"
                :key="metric"
              >
                <td>{{ title }}</td>
                <td>{{ name }}</td>
                <td>{{ before[field][metric] }}</td>
                <td>{{ after[field][metric] }}</td>
                <td>
                  {{
                    after[field][metric] - before[field][metric] > 0 ? "+" : ""
                  }}{{ after[field][metric] - before[field][metric] }}
                </td>
              </tr></template
            >
          </tbody>
        </table>
      </div>
    </section>
    <section class="panel">
      <h2>기록별 비교</h2>
      <div class="form-grid">
        <label
          >비교 기록<select v-model="kind" aria-label="비교 기록">
            <option value="inventory">재고</option>
            <option value="shipments">출고</option>
            <option value="receipts">입고 판정·근거</option>
          </select></label
        ><label class="check-label"
          ><input type="checkbox" v-model="onlyChanged" />변경된 기록만
          보기</label
        >
      </div>
      <p class="note">
        한쪽에 없는 기록은 ‘기록 없음’으로 표시합니다. 출고의 연결 미확인은 확인
        필요 수량에 포함되며 연결을 새로 추정하지 않습니다.
      </p>
      <p v-if="!rows.length" class="empty">
        표시할 변경 기록이 없습니다. 전체 기록은 필터를 해제해서 확인하세요.
      </p>
      <div v-else class="table-scroll">
        <table aria-label="기록별 판정 비교">
          <thead>
            <tr>
              <th>기록 ID</th>
              <th>변화</th>
              <th>기준</th>
              <th>비교</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in visible" :key="row.id">
              <td>{{ row.id }}</td>
              <td>{{ status[row.kind] }}</td>
              <td class="description">{{ row.before ?? "기록 없음" }}</td>
              <td class="description">{{ row.after ?? "기록 없음" }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <footer class="pagination">
        <span
          >{{ rows.length }}건 · {{ page + 1 }} /
          {{ Math.max(1, Math.ceil(rows.length / 20)) }}</span
        >
        <div>
          <button :disabled="page === 0" @click="page--">이전</button
          ><button :disabled="(page + 1) * 20 >= rows.length" @click="page++">
            다음
          </button>
        </div>
      </footer>
    </section></template
  >
</template>
<style scoped>
.selection {
  border: 0;
  padding: 0;
  margin: 0;
  min-inline-size: 0;
}
.identifier {
  overflow-wrap: anywhere;
  font-size: 13px;
}
.description {
  white-space: normal;
  min-width: 240px;
  overflow-wrap: anywhere;
}
</style>
