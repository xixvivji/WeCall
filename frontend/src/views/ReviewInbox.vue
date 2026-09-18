<script setup lang="ts">
import { ref, computed, onMounted } from "vue";
import { api, user, errorText, dateText, type Page } from "../api";
interface Review {
  kind: "CONDITION" | "SOURCE" | "EVIDENCE" | "PROOF";
  id: string;
  caseId: string;
  caseTitle: string;
  title: string;
  createdAt: string;
  taskId: string | null;
}
const names = {
  CONDITION: "조건 승인",
  SOURCE: "원문 재검토",
  EVIDENCE: "입고 증거",
  PROOF: "작업 증빙",
};
const data = ref<Page<Review> & { counts: Record<string, number> }>(),
  kind = ref("ALL"),
  q = ref(""),
  page = ref(0),
  busy = ref(false),
  error = ref("");
const reviewer = computed(() => user.value?.roles.includes("REVIEWER"));
async function load(reset = false) {
  if (busy.value) return;
  if (!reviewer.value) return;
  if (reset) page.value = 0;
  busy.value = true;
  error.value = "";
  data.value = undefined;
  try {
    data.value = await api(
      "/api/v1/workspace/reviews?" +
        new URLSearchParams({
          kind: kind.value,
          q: q.value,
          page: String(page.value),
        }),
    );
  } catch (e) {
    error.value = errorText(e);
  } finally {
    busy.value = false;
  }
}
function destination(row: Review) {
  return {
    path: "/recalls/" + row.caseId,
    query: ["CONDITION", "SOURCE"].includes(row.kind)
      ? { condition: row.id }
      : row.kind === "EVIDENCE"
        ? { evidence: row.id }
        : { task: row.taskId!, proof: row.id },
  };
}
onMounted(() => load());
</script>
<template>
  <div class="page-heading">
    <div>
      <p class="eyebrow">REVIEW INBOX</p>
      <h1>검토 대기함</h1>
      <p class="muted">
        진행 중 사건의 미검토 항목을 오래된 순서로 확인합니다.
      </p>
    </div>
  </div>
  <p v-if="!reviewer" class="note">
    검토 대기함은 검토자만 사용할 수 있습니다.
  </p>
  <template v-else
    ><p v-if="error" class="error" role="alert">
      {{ error }} <button :disabled="busy" @click="load()">다시 시도</button>
    </p>
    <section class="panel">
      <form @submit.prevent="load(true)">
        <fieldset :disabled="busy" class="filters">
          <label
            >검토 유형<select v-model="kind" aria-label="검토 유형">
              <option value="ALL">전체</option>
              <option v-for="(name, key) in names" :key="key" :value="key">
                {{ name }}
              </option>
            </select></label
          ><label
            >사건·검토 항목 검색<input v-model="q" maxlength="200" /></label
          ><button class="primary">대기 항목 조회</button>
        </fieldset>
      </form>
      <p class="note">
        조건 초안·최신 승인 조건의 원문 재검토·입고 증거·현재 처리 회차의 작업
        증빙을 표시합니다. 취소된 작업도 미검토 증빙이 있으면 포함됩니다. 검토
        열기로 근거를 확인한 뒤 기존 화면에서 처리하세요.
      </p>
      <p v-if="busy" role="status">불러오는 중…</p>
      <template v-if="data"
        ><p class="muted">
          검색어 기준 · 원문 재검토 {{ data.counts.SOURCE ?? 0 }}건 · 조건 승인
          {{ data.counts.CONDITION }}건 · 입고 증거 {{ data.counts.EVIDENCE }}건
          · 작업 증빙 {{ data.counts.PROOF }}건
        </p>
        <p v-if="!data.items.length" class="empty">
          조회 조건에 맞는 검토 대기 항목이 없습니다.
        </p>
        <div v-else class="table-scroll">
          <table>
            <thead>
              <tr>
                <th>유형</th>
                <th>사건</th>
                <th>항목</th>
                <th>등록 시각</th>
                <th>검토</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in data.items" :key="row.kind + row.id">
                <td>{{ names[row.kind] }}</td>
                <td>{{ row.caseTitle }}</td>
                <td>{{ row.title }}</td>
                <td>{{ dateText(row.createdAt) }}</td>
                <td>
                  <RouterLink :to="destination(row)">검토 열기</RouterLink>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <footer class="pagination">
          <span
            >{{ data.totalElements }}건 · {{ page + 1 }} /
            {{ Math.max(1, data.totalPages) }}</span
          >
          <div>
            <button
              :disabled="busy || page === 0"
              @click="
                page--;
                load();
              "
            >
              이전</button
            ><button
              :disabled="busy || page + 1 >= data.totalPages"
              @click="
                page++;
                load();
              "
            >
              다음
            </button>
          </div>
        </footer></template
      >
    </section></template
  >
</template>
<style scoped>
.filters {
  border: 0;
  padding: 0;
  display: flex;
  align-items: end;
  gap: 16px;
  flex-wrap: wrap;
  min-inline-size: 0;
}
.filters label {
  flex: 1;
  min-width: 150px;
}
.filters button {
  margin-bottom: 16px;
}
</style>
