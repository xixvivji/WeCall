<script setup lang="ts">
import { ref, watch, onUnmounted, computed } from "vue";
import { api, errorText, user, type CaseDetail } from "../api";
const props = defineProps<{
  recall: CaseDetail;
  assessmentId?: string;
  revision: number;
}>();
const emit = defineEmits<{ createCondition: [] }>();
interface Issue {
  code: string;
  message: string;
  count: number;
}
interface Check {
  status: string;
  ready: boolean;
  blockers: Issue[];
  warnings: Issue[];
}
const data = ref<Check>(),
  error = ref(""),
  loading = ref(false);
const reviewer = computed(() => user.value?.roles.includes("REVIEWER"));
let generation = 0;
async function load() {
  const ticket = ++generation;
  data.value = undefined;
  error.value = "";
  loading.value = true;
  try {
    const result = await api<Check>(
      `/api/v1/recalls/${props.recall.id}/closure-check` +
        (props.assessmentId ? `?assessmentId=${props.assessmentId}` : ""),
    );
    if (ticket === generation) data.value = result;
  } catch (e) {
    if (ticket === generation) error.value = errorText(e);
  } finally {
    if (ticket === generation) loading.value = false;
  }
}
const names: Record<string, [string, string]> = {
  NO_CONDITION: ["회수 조건 준비", "overview"],
  PENDING_CONDITIONS: ["조건 승인 검토", "overview"],
  NO_ASSESSMENT: ["기준 판정 준비", "overview"],
  OUTDATED_ASSESSMENT: ["최근 조건의 판정 확인", "overview"],
  UNRESOLVED_RECEIPTS: ["확인 필요 입고 보완", "evidence"],
  UNRESOLVED_SHIPMENTS: ["확인 필요 출고 확인", "impact"],
  INCOMPLETE_TASKS: ["남은 대응 작업 처리", "tasks"],
  NO_COMPLETED_RESPONSE: ["대상 범위의 대응 작업 검토", "tasks"],
  PENDING_RECEIPT_EVIDENCE: ["입고 증거 검토", "evidence"],
  PENDING_TASK_PROOFS: ["처리 증빙 검토", "tasks"],
};
function destination(issue: Issue) {
  const tab = names[issue.code]?.[1] || "closure";
  const condition =
    issue.code === "PENDING_CONDITIONS"
      ? props.recall.conditions.find((c) => c.status === "DRAFT")
      : issue.code === "NO_ASSESSMENT"
        ? props.recall.conditions
            .slice()
            .reverse()
            .find((c) => c.status === "APPROVED")
        : undefined;
  return {
    path: `/recalls/${props.recall.id}`,
    query: {
      tab,
      ...(condition ? { condition: condition.id } : {}),
      assessment: props.assessmentId || "",
    },
  };
}
watch(() => [props.recall.id, props.assessmentId, props.revision], load, {
  immediate: true,
});
onUnmounted(() => generation++);
</script>
<template>
  <section class="panel next-actions" aria-label="사건 다음 할 일">
    <div class="section-heading">
      <h2>다음 할 일</h2>
      <button :disabled="loading" @click="load">할 일 다시 확인</button>
    </div>
    <p class="note">
      현재 기준 판정과 서버에 기록된 업무 상태로 안내합니다. 작업 완료 건수는
      회수 완료 수량을 뜻하지 않습니다.
    </p>
    <p v-if="loading" role="status">남은 업무를 확인하는 중입니다…</p>
    <p v-if="error" class="error" role="alert">
      {{ error }} <button @click="load">할 일 다시 시도</button>
    </p>
    <template v-if="data">
      <template v-if="data.status === 'CLOSED'">
        <p>종료된 사건입니다. 대응 보고서와 종료 이력을 확인하세요.</p>
        <RouterLink
          :to="{
            path: `/recalls/${recall.id}`,
            query: { tab: 'report', assessment: assessmentId || '' },
          }"
          >대응 보고서 확인</RouterLink
        >
        <RouterLink
          v-if="reviewer"
          :to="{
            path: `/recalls/${recall.id}`,
            query: { tab: 'closure', assessment: assessmentId || '' },
          }"
          >종료 이력·재개 검토</RouterLink
        >
      </template>
      <template v-else>
        <div
          v-for="issue in data.blockers"
          :key="issue.code"
          class="next-action"
        >
          <div>
            <strong>{{ names[issue.code]?.[0] || issue.message }}</strong>
            <p>
              {{ issue.message }}
              <span
                v-if="
                  ![
                    'NO_CONDITION',
                    'NO_ASSESSMENT',
                    'NO_COMPLETED_RESPONSE',
                    'OUTDATED_ASSESSMENT',
                  ].includes(issue.code)
                "
                >· {{ issue.count }}
                {{ issue.code === "UNRESOLVED_SHIPMENTS" ? "EA" : "건" }}</span
              >
            </p>
          </div>
          <button
            v-if="issue.code === 'NO_CONDITION' && reviewer"
            @click="emit('createCondition')"
          >
            첫 조건 작성
          </button>
          <RouterLink v-else :to="destination(issue)"
            >{{ names[issue.code]?.[0] || "종료 점검" }} 열기</RouterLink
          >
        </div>
        <template v-if="data.ready"
          ><p class="success">
            시스템 점검에서 종료 차단 항목이 없습니다. 검토자가 실제 대응 범위를
            확인해야 합니다.
          </p>
          <RouterLink
            :to="{
              path: `/recalls/${recall.id}`,
              query: { tab: 'closure', assessment: assessmentId || '' },
            }"
            >최종 종료 점검 열기</RouterLink
          ></template
        >
        <p v-for="issue in data.warnings" :key="issue.code" class="small muted">
          추가 검토: {{ issue.message }} · {{ issue.count }}건
        </p>
        <p v-if="!reviewer" class="footnote">
          조건 승인·증빙 검토·사건 종료는 검토자가 처리합니다. 담당 작업은 대응
          작업에서 확인하세요.
        </p>
      </template>
    </template>
  </section>
</template>
<style scoped>
.next-actions {
  margin-bottom: 24px;
}
.next-action {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
  border-bottom: 1px solid #e6ece8;
  padding: 14px 0;
}
.next-action p {
  margin: 6px 0 0;
}
.next-action a,
.next-action button {
  flex-shrink: 0;
}
.next-actions > a {
  display: inline-block;
  margin-right: 20px;
}
@media (max-width: 600px) {
  .next-action {
    align-items: flex-start;
    flex-direction: column;
    gap: 8px;
  }
  .next-action a {
    max-width: 100%;
    overflow-wrap: anywhere;
  }
}
</style>
