<script setup lang="ts">
import { ref, computed, onMounted } from "vue";
import { api, user, label, errorText, type Page } from "../api";
interface Row {
  id: string;
  caseId: string;
  caseTitle: string;
  title: string;
  status: string;
  assignee: string | null;
  assigneeActive: boolean;
  caseStatus: string;
}
const summary = ref<Record<string, number>>(),
  rows = ref<Page<Row>>(),
  scope = ref("mine"),
  status = ref("ACTIVE"),
  q = ref(""),
  page = ref(0),
  busy = ref(false),
  error = ref("");
const reviewer = computed(() => user.value?.roles.includes("REVIEWER"));
async function load(reset = false) {
  if (busy.value) return;
  if (reset) page.value = 0;
  busy.value = true;
  error.value = "";
  rows.value = undefined;
  summary.value = undefined;
  try {
    const query = new URLSearchParams({
      scope: scope.value,
      status: status.value,
      q: q.value,
      page: String(page.value),
    });
    const [s, r] = await Promise.all([
      api<Record<string, number>>("/api/v1/workspace/summary"),
      api<Page<Row>>("/api/v1/workspace/tasks?" + query),
    ]);
    summary.value = s;
    rows.value = r;
  } catch (e) {
    error.value = errorText(e);
  } finally {
    busy.value = false;
  }
}
onMounted(() => load());
</script>
<template>
  <div class="page-heading">
    <div>
      <p class="eyebrow">WORKSPACE</p>
      <h1>업무 대시보드</h1>
      <p class="muted">사건 현황과 내 담당 작업을 확인합니다.</p>
    </div>
  </div>
  <p v-if="error" class="error" role="alert">
    {{ error }} <button :disabled="busy" @click="load()">다시 시도</button>
  </p>
  <section v-if="summary" class="panel">
    <div class="summary-grid">
      <div
        v-for="(title, key) in {
          openCases: '진행 중 사건',
          reviewCases: '확인 필요 사건',
          unassessedCases: '미판정 사건',
          openTasks: '전체 미완료 작업',
          myTasks: '내 미완료 작업',
        }"
        :key="key"
      >
        <span class="muted">{{ title }}</span>
        <h2>{{ summary[key] }}건</h2>
      </div>
    </div>
    <p class="note">
      확인 필요 사건은 진행 중 사건의 최신 판정에 확인 필요 재고·출고 수량이
      있는 사건입니다. 미판정 사건은 별도로 표시합니다. 사건 간 수량을 합산하지
      않으며 작업 완료와 대상 판정은 별개입니다.
    </p>
  </section>
  <section class="panel">
    <RouterLink v-if="reviewer" to="/reviews">검토 대기함 열기</RouterLink>
    <h2>내 할 일 · 작업 찾기</h2>
    <form @submit.prevent="load(true)">
      <fieldset :disabled="busy" class="filters">
        <label
          >작업 범위<select v-model="scope">
            <option value="mine">내 할 일</option>
            <template v-if="reviewer"
              ><option value="all">전체 작업</option>
              <option value="reassign">재배정 필요</option></template
            >
          </select></label
        ><label
          >작업 상태<select v-model="status" aria-label="작업 상태">
            <option value="ACTIVE">미완료</option>
            <option value="ALL">전체 상태</option>
            <option
              v-for="s in ['OPEN', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED']"
              :key="s"
              :value="s"
            >
              {{ label(s) }}
            </option>
          </select></label
        ><label>사건·작업 검색<input v-model="q" maxlength="200" /></label
        ><button class="primary">작업 조회</button>
      </fieldset>
    </form>
    <p v-if="scope === 'reassign'" class="note">
      미배정 또는 비활성·존재하지 않는 계정에 배정된 미완료 작업입니다. 작업을
      열어 활성 담당자를 지정하세요.
    </p>
    <p v-if="busy" role="status">불러오는 중…</p>
    <template v-if="rows"
      ><p class="muted">{{ rows.totalElements }}건</p>
      <p v-if="!rows.items.length" class="empty">
        조회 조건에 맞는 작업이 없습니다.
      </p>
      <div v-else class="table-scroll">
        <table>
          <thead>
            <tr>
              <th>작업</th>
              <th>사건</th>
              <th>상태</th>
              <th>담당자</th>
              <th>바로가기</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="r in rows.items" :key="r.id">
              <td>{{ r.title }}</td>
              <td>
                <RouterLink :to="'/recalls/' + r.caseId">{{
                  r.caseTitle
                }}</RouterLink>
              </td>
              <td>{{ label(r.status) }}</td>
              <td>
                {{ r.assignee || "미배정"
                }}<span v-if="r.assignee && !r.assigneeActive"> (비활성)</span>
              </td>
              <td>
                <RouterLink
                  :to="{ path: '/recalls/' + r.caseId, query: { task: r.id } }"
                  >작업 열기</RouterLink
                >
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div class="inline">
        <button
          :disabled="busy || page === 0"
          @click="
            page--;
            load();
          "
        >
          이전</button
        ><span>{{ page + 1 }} / {{ Math.max(1, rows.totalPages) }}</span
        ><button
          :disabled="busy || page + 1 >= rows.totalPages"
          @click="
            page++;
            load();
          "
        >
          다음
        </button>
      </div></template
    >
  </section>
</template>
<style scoped>
.summary-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
  gap: 20px;
}
.filters {
  border: 0;
  padding: 0;
  display: flex;
  align-items: end;
  gap: 16px;
  flex-wrap: wrap;
}
.filters label {
  flex: 1;
  min-width: 150px;
}
.filters button {
  margin-bottom: 16px;
}
</style>
