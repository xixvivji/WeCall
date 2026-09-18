<script setup lang="ts">
import { ref, onMounted } from "vue";
import { api, dateText, errorText, type Page } from "../api";
const props = defineProps<{ caseId: string }>();
const emit = defineEmits<{ open: [tab: string] }>();
interface Entry {
  id: string;
  kind: string;
  type: string;
  occurredAt: string;
  actor: string | null;
  note: string | null;
  title: string;
  version: number | null;
  conditionId: string | null;
  evidenceId: string | null;
  taskId: string | null;
  proofId: string | null;
  assignee: string | null;
}
const data = ref<Page<Entry>>(),
  kind = ref("ALL"),
  order = ref("DESC"),
  page = ref(0),
  busy = ref(false),
  error = ref("");
const kinds: Record<string, string> = {
  ALL: "전체",
  CONDITION: "조건 승인",
  EVIDENCE: "입고 증거 검토",
  TASK: "작업 변경",
  PROOF: "증빙 제출·검토",
  LIFECYCLE: "사건 종료·재개",
};
const types: Record<string, string> = {
  CONDITION_SOURCE_REVIEWED: "원문 영향 없음 확인",
  CONDITION_WITHDRAWN: "조건 초안 철회",
  CONDITION_APPROVED: "조건 승인",
  EVIDENCE_APPROVED: "입고 증거 승인",
  EVIDENCE_REJECTED: "입고 증거 반려",
  TASK_CREATED: "작업 등록",
  TASK_ASSIGNED: "담당자 배정",
  TASK_START: "작업 시작",
  TASK_COMPLETE: "작업 완료",
  TASK_CANCEL: "작업 취소",
  TASK_REOPEN: "작업 재개",
  PROOF_ADDED: "증빙 제출",
  PROOF_ACCEPTED: "증빙 승인",
  PROOF_REJECTED: "증빙 반려",
  CASE_CLOSED: "사건 종료",
  CASE_REOPENED: "사건 재개",
};
async function load(reset = false) {
  if (busy.value) return;
  if (reset) page.value = 0;
  busy.value = true;
  error.value = "";
  data.value = undefined;
  try {
    data.value = await api(
      `/api/v1/recalls/${props.caseId}/history?` +
        new URLSearchParams({
          kind: kind.value,
          order: order.value,
          page: String(page.value),
          size: "20",
        }),
    );
  } catch (e) {
    error.value = errorText(e);
  } finally {
    busy.value = false;
  }
}
function destination(item: Entry) {
  return {
    path: `/recalls/${props.caseId}`,
    query: item.conditionId
      ? { condition: item.conditionId }
      : item.evidenceId
        ? { evidence: item.evidenceId }
        : item.taskId
          ? {
              task: item.taskId,
              ...(item.proofId ? { proof: item.proofId } : {}),
            }
          : { tab: "closure" },
  };
}
onMounted(() => load());
</script>
<template>
  <section class="panel">
    <h2>사건 업무 이력</h2>
    <p class="note">
      저장된 승인·검토·작업·종료 기록입니다. 처리자와 사유가 저장되지 않은
      항목은 기록 없음으로 표시합니다. 작업 완료는 대상 판정이나 실제 회수
      수량을 바꾸지 않습니다.
    </p>
    <form @submit.prevent="load(true)">
      <fieldset :disabled="busy" class="history-filters">
        <label
          >이력 유형<select v-model="kind" aria-label="이력 유형">
            <option v-for="(name, key) in kinds" :key="key" :value="key">
              {{ name }}
            </option>
          </select></label
        >
        <label
          >이력 정렬<select v-model="order" aria-label="이력 정렬">
            <option value="DESC">최신순</option>
            <option value="ASC">오래된순</option>
          </select></label
        >
        <button>이력 조회</button>
      </fieldset>
    </form>
    <p v-if="busy" role="status">업무 이력을 불러오는 중입니다…</p>
    <p v-if="error" class="error" role="alert">
      {{ error }} <button :disabled="busy" @click="load()">다시 시도</button>
    </p>
    <template v-if="data">
      <p v-if="!data.items.length" class="empty">
        조회 조건에 맞는 업무 이력이 없습니다.
      </p>
      <ol v-else class="history-list">
        <li v-for="item in data.items" :key="item.id" class="history-entry">
          <div class="section-heading">
            <strong>{{ types[item.type] || item.type }}</strong
            ><time :datetime="item.occurredAt">{{
              dateText(item.occurredAt)
            }}</time>
          </div>
          <p>
            {{ item.title
            }}<span v-if="item.version !== null" class="muted">
              · 기록 버전 {{ item.version }}</span
            >
          </p>
          <p class="small">
            처리자: {{ item.actor || "기록 없음"
            }}<span v-if="item.assignee">
              · 배정 담당자: {{ item.assignee }}</span
            >
          </p>
          <p class="prewrap">사유: {{ item.note || "기록 없음" }}</p>
          <RouterLink
            :to="destination(item)"
            @click="
              emit(
                'open',
                item.conditionId
                  ? 'overview'
                  : item.evidenceId
                    ? 'evidence'
                    : item.taskId
                      ? 'tasks'
                      : 'closure',
              )
            "
            >관련 상세 열기</RouterLink
          >
        </li>
      </ol>
      <footer class="pagination">
        <span
          >{{ data.totalElements }}건 · {{ data.totalPages ? page + 1 : 0 }} /
          {{ data.totalPages }} 페이지</span
        >
        <div>
          <button
            :disabled="busy || page === 0"
            @click="
              page--;
              load();
            "
          >
            이전
          </button>
          <button
            :disabled="busy || page + 1 >= data.totalPages"
            @click="
              page++;
              load();
            "
          >
            다음
          </button>
        </div>
      </footer>
    </template>
  </section>
</template>
<style scoped>
.history-filters {
  border: 0;
  padding: 0;
  min-inline-size: 0;
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  align-items: end;
}
.history-filters label {
  flex: 1;
  min-width: 150px;
}
.history-filters button {
  margin-bottom: 16px;
}
.history-list {
  list-style: none;
  margin: 0;
  padding: 0;
}
.history-entry {
  padding: 20px 0;
  border-bottom: 1px solid #e6ece8;
  overflow-wrap: anywhere;
}
.history-entry time {
  font-size: 13px;
  color: #56665e;
}
</style>
