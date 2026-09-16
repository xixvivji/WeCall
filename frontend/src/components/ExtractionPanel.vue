<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from "vue";
import { api, user, errorText, type Rule } from "../api";
import { confirmDrafts, useDraftGuard } from "../drafts";
import ConditionForm from "./ConditionForm.vue";
interface Job {
  id: string;
  sourceText: string;
  status: string;
  reviewStatus: string;
  errorCode: string | null;
  conditionId: string | null;
  reviewNote: string | null;
  output: {
    mode: string;
    provider: string;
    model: string;
    rule: Rule;
    sourceQuote: string;
    warnings: string[];
  } | null;
}
const props = defineProps<{ caseId: string; closed: boolean }>();
const emit = defineEmits<{ updated: [] }>();
const rows = ref<Job[]>([]),
  selectedId = ref(""),
  error = ref(""),
  loading = ref(true),
  busy = ref(false),
  editing = ref(false),
  note = ref("");
const reviewer = computed(() => user.value?.roles.includes("REVIEWER"));
const selected = computed(() =>
  rows.value.find((r) => r.id === selectedId.value),
);
const running = computed(() =>
  rows.value.some((r) => ["QUEUED", "RUNNING"].includes(r.status)),
);
const draft = useDraftGuard(() => note.value);
let disposed = false,
  timer: ReturnType<typeof setTimeout> | undefined;
const prefix = `/api/v1/recalls/${props.caseId}/extractions`;
const statusText: Record<string, string> = {
  QUEUED: "대기 중",
  RUNNING: "분석 중",
  SUCCEEDED: "추출 완료",
  FAILED: "추출 실패",
  PENDING: "검토 대기",
  ACCEPTED: "조건 초안 생성",
  DISMISSED: "기각",
};
const errors: Record<string, string> = {
  AI_REJECTED_INPUT:
    "명확한 조건을 추출하지 못했거나 로컬 입력 한도를 넘었습니다. 원문을 확인해 수동으로 조건을 작성하세요.",
  AI_TIMEOUT:
    "모델 응답 시간이 초과됐습니다. 실행 환경을 확인한 뒤 다시 요청하세요.",
  AI_UNAVAILABLE:
    "로컬 모델이 준비되지 않았거나 응답 검증에 실패했습니다. 실행 상태를 확인하세요.",
  SERVICE_NOT_CONFIGURED: "AI 서비스 연결 설정이 필요합니다.",
  INVALID_AI_RESPONSE:
    "검증할 수 없는 응답이므로 조건 초안에 사용하지 않았습니다.",
};
function describe(rule: Rule): string {
  if (rule.op === "AND" || rule.op === "OR")
    return (
      "(" +
      (rule.children ?? [])
        .map(describe)
        .join(rule.op === "AND" ? " 그리고 " : " 또는 ") +
      ")"
    );
  const field = rule.field === "LOT_NUMBER" ? "제조번호" : "소비기한";
  const values = rule.values ?? [];
  if (rule.op === "BETWEEN")
    return `${field} ${values.join(" ~ ")} (양 끝 포함)`;
  return `${field} ${values.join(" 또는 ")} 일치`;
}
async function load() {
  clearTimeout(timer);
  error.value = "";
  try {
    const result = await api<Job[]>(prefix);
    if (disposed) return;
    rows.value = result;
    if (!selectedId.value) selectedId.value = result.at(-1)?.id ?? "";
    if (running.value) timer = setTimeout(load, 2000);
  } catch (e) {
    if (!disposed) error.value = errorText(e);
  } finally {
    if (!disposed) loading.value = false;
  }
}
function select(id: string) {
  if (!confirmDrafts()) return;
  selectedId.value = id;
  editing.value = false;
  note.value = "";
  draft.saved();
}
function toggleEditing() {
  if (!confirmDrafts()) return;
  editing.value = !editing.value;
  note.value = "";
  draft.saved();
}
async function request() {
  if (busy.value || !confirmDrafts()) return;
  busy.value = true;
  error.value = "";
  try {
    const job = await api<Job>(prefix, {});
    editing.value = false;
    note.value = "";
    draft.saved();
    selectedId.value = job.id;
    await load();
    emit("updated");
  } catch (e) {
    error.value = errorText(e);
  } finally {
    busy.value = false;
  }
}
async function dismiss() {
  if (busy.value || !selected.value) return;
  busy.value = true;
  error.value = "";
  try {
    await api(`${prefix}/${selected.value.id}/dismissal`, { note: note.value });
    note.value = "";
    draft.saved();
    await load();
    emit("updated");
  } catch (e) {
    error.value = errorText(e);
  } finally {
    busy.value = false;
  }
}
async function saved() {
  editing.value = false;
  await load();
  emit("updated");
}
onMounted(load);
onUnmounted(() => {
  disposed = true;
  clearTimeout(timer);
});
</script>
<template>
  <section class="panel" aria-label="AI 조건 추출">
    <div class="section-heading">
      <h2>AI 조건 추출</h2>
      <button
        v-if="reviewer && !closed"
        class="primary"
        :disabled="busy || running || loading || !!error"
        @click="request"
      >
        원문 분석 요청
      </button>
    </div>
    <p class="note">
      등록한 사건 원문에서 조건 초안을 추출합니다. 현재 로컬 모델 입력 한도는
      UTF-8 6,000바이트·80줄입니다. 상품 연결과 조건은 사람이 검토하며, 초안을
      저장한 뒤에도 별도 승인이 필요합니다.
    </p>
    <p v-if="error" class="error" role="alert">
      {{ error }}
      <button :disabled="busy" @click="load">분석 이력 다시 조회</button>
    </p>
    <p v-if="loading" class="empty" role="status">
      분석 이력을 불러오는 중입니다…
    </p>
    <p v-else-if="!rows.length && !error" class="empty">
      아직 요청한 분석이 없습니다.
    </p>
    <div class="inline">
      <button
        v-for="(job, index) in rows"
        :key="job.id"
        :disabled="busy"
        :aria-pressed="selectedId === job.id"
        @click="select(job.id)"
      >
        분석 {{ index + 1 }} · {{ statusText[job.status] }} ·
        {{ statusText[job.reviewStatus] }}
      </button>
    </div>
    <template v-if="selected">
      <p
        v-if="['QUEUED', 'RUNNING'].includes(selected.status)"
        class="note"
        role="status"
      >
        로컬 모델이 분석 중입니다. 결과를 자동으로 확인합니다. 다른 화면으로
        이동해도 서버 작업은 유지됩니다.
      </p>
      <p v-if="selected.status === 'FAILED'" class="error" role="alert">
        {{
          errors[selected.errorCode ?? ""] ??
          "분석에 실패했습니다. 원문과 실행 환경을 확인하세요."
        }}
        ({{ selected.errorCode }})
      </p>
      <details>
        <summary>분석에 사용한 원문 확인</summary>
        <p class="prewrap">{{ selected.sourceText }}</p>
      </details>
      <template v-if="selected.output">
        <p class="small muted">
          {{ selected.output.mode === "MOCK" ? "모의 응답" : "실제 모델 응답" }}
          · {{ selected.output.provider }} · {{ selected.output.model }}
        </p>
        <h3>원문 근거</h3>
        <p class="prewrap">{{ selected.output.sourceQuote }}</p>
        <h3>추출 조건</h3>
        <p class="prewrap">{{ describe(selected.output.rule) }}</p>
        <ul>
          <li v-for="warning in selected.output.warnings" :key="warning">
            {{ warning }}
          </li>
        </ul>
      </template>
      <p v-if="selected.conditionId" class="success">
        조건 초안을 만들었습니다.
        <RouterLink
          :to="{
            path: `/recalls/${caseId}`,
            query: { condition: selected.conditionId },
          }"
          >조건 검토로 이동 →</RouterLink
        >
      </p>
      <p v-if="selected.reviewNote" class="note">
        검토 사유: {{ selected.reviewNote }}
      </p>
      <template
        v-if="
          reviewer &&
          !closed &&
          selected.reviewStatus === 'PENDING' &&
          ['SUCCEEDED', 'FAILED'].includes(selected.status)
        "
      >
        <button v-if="selected.output" :disabled="busy" @click="toggleEditing">
          {{ editing ? "검토 작성 닫기" : "조건과 상품 연결 검토" }}
        </button>
        <ConditionForm
          v-if="editing && selected.output"
          :key="selected.id"
          :case-id="caseId"
          :extraction="{
            id: selected.id,
            rule: selected.output.rule,
            sourceQuote: selected.output.sourceQuote,
          }"
          @saved="saved"
        />
        <form v-else @submit.prevent="dismiss">
          <label
            >분석 기각 사유<textarea
              v-model="note"
              required
              maxlength="2000"
            /></label
          ><button :disabled="busy">분석 기각</button>
        </form>
      </template>
      <p v-if="!reviewer" class="note">
        분석 요청과 검토는 검토자 권한으로 진행합니다.
      </p>
    </template>
  </section>
</template>
