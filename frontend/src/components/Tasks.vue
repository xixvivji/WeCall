<script setup lang="ts">
import { confirmDrafts, useDraftGuard } from "../drafts";
import { ref, onMounted, computed, nextTick } from "vue";
import { useRoute } from "vue-router";
import FilePicker from "./FilePicker.vue";
import Attachments from "./Attachments.vue";
import {
  withAttachments,
  api,
  ApiError,
  user,
  label,
  errorText,
  dateText,
  type Assessment,
} from "../api";
const conflict = ref(false),
  loading = ref(false),
  selecting = ref(false),
  listError = ref(""),
  refreshed = ref(false);
let detailGeneration = 0;
const latestClosed = ref(false);
const effectivelyClosed = computed(() => props.closed || latestClosed.value);
const proofFiles = ref<File[]>([]);
const proofPickerKey = ref(0);
const route = useRoute();
const props = defineProps<{
  caseId: string;
  initialTask?: string;
  closed: boolean;
  assessment?: Assessment;
}>();
interface Proof {
  id: string;
  reviewRound: number;
  evidenceText: string;
  status: string;
  submittedBy: string;
  reviewNote: string | null;
}
interface Task {
  id: string;
  title: string;
  instructions: string;
  status: string;
  version: number;
  assignee: string | null;
  taskType: string;
  targetType: string;
  targetId: string | null;
  assessmentId: string | null;
  reviewRound: number;
  proofs: Proof[];
  events: { version: number; type: string; actor: string; at: string }[];
}
const tasks = ref<Task[]>([]),
  selected = ref<Task>(),
  error = ref(""),
  busy = ref(false),
  creating = ref(false),
  users = ref<{ username: string; displayName: string; enabled: boolean }[]>(
    [],
  );
const title = ref(""),
  instructions = ref(""),
  taskType = ref("QUARANTINE"),
  targetType = ref("CASE"),
  targetId = ref(""),
  assignee = ref(""),
  note = ref(""),
  proof = ref(""),
  newAssignee = ref("");
const createDraft = useDraftGuard(() => ({
  title: title.value,
  instructions: instructions.value,
  taskType: taskType.value,
  targetType: targetType.value,
  targetId: targetId.value,
  assignee: assignee.value,
}));
const detailDraft = useDraftGuard(() => ({
  note: note.value,
  assignee: newAssignee.value,
}));
const proofDraft = useDraftGuard(() => ({
  proof: proof.value,
  files: proofFiles.value,
}));
function toggleCreate() {
  if (creating.value && !createDraft.discard()) return;
  if (creating.value) {
    title.value = "";
    instructions.value = "";
    taskType.value = "QUARANTINE";
    targetType.value = "CASE";
    targetId.value = "";
    assignee.value = "";
    createDraft.saved();
  }
  creating.value = !creating.value;
}
function closeDetail() {
  if (!confirmDrafts()) return;
  detailGeneration++;
  selected.value = undefined;
  selecting.value = false;
  note.value = "";
  proof.value = "";
  proofFiles.value = [];
  newAssignee.value = "";
  detailDraft.saved();
  proofDraft.saved();
}
const reviewer = computed(() => user.value?.roles.includes("REVIEWER")),
  canWork = computed(
    () => reviewer.value || selected.value?.assignee === user.value?.username,
  );
async function load() {
  loading.value = true;
  listError.value = "";
  try {
    tasks.value = await api(`/api/v1/recalls/${props.caseId}/tasks`);
  } catch (e) {
    listError.value = errorText(e);
  } finally {
    loading.value = false;
  }
}
async function select(id: string, preserveDraft = false) {
  if (!preserveDraft && !confirmDrafts()) return;
  const ticket = ++detailGeneration;
  selecting.value = true;
  error.value = "";
  refreshed.value = false;
  if (!preserveDraft) {
    selected.value = undefined;
    note.value = "";
    proof.value = "";
    proofFiles.value = [];
    proofPickerKey.value++;
  }
  try {
    const [task, recall] = await Promise.all([
      api<Task>(`/api/v1/recalls/${props.caseId}/tasks/${id}`),
      api<{ status: string }>(`/api/v1/recalls/${props.caseId}`),
    ]);
    if (ticket !== detailGeneration) return;
    selected.value = task;
    latestClosed.value = recall.status === "CLOSED";
    newAssignee.value = task.assignee || "";
    conflict.value = false;
    refreshed.value = preserveDraft;
    if (!preserveDraft) {
      detailDraft.saved();
      proofDraft.saved();
    }
  } catch (e) {
    if (ticket === detailGeneration) error.value = errorText(e);
  } finally {
    if (ticket === detailGeneration) selecting.value = false;
  }
}
async function create() {
  if (busy.value) return;
  busy.value = true;
  error.value = "";
  try {
    await api(`/api/v1/recalls/${props.caseId}/tasks`, {
      title: title.value,
      instructions: instructions.value,
      taskType: taskType.value,
      targetType: targetType.value,
      targetId: targetType.value === "CASE" ? null : targetId.value,
      assessmentId: props.assessment?.id || null,
      assignee: assignee.value || null,
    });
    creating.value = false;
    title.value = "";
    instructions.value = "";
    createDraft.saved();
    await load();
  } catch (e) {
    error.value = errorText(e);
  } finally {
    busy.value = false;
  }
}
async function act(path: string, body: Record<string, unknown>) {
  if (busy.value) return;
  if (
    !selected.value ||
    conflict.value ||
    selecting.value ||
    effectivelyClosed.value
  )
    return;
  busy.value = true;
  error.value = "";
  try {
    selected.value = await api(
      `/api/v1/recalls/${props.caseId}/tasks/${selected.value.id}/${path}`,
      path === "proofs"
        ? withAttachments(
            { ...body, expectedVersion: selected.value.version },
            proofFiles.value,
          )
        : { ...body, expectedVersion: selected.value.version },
    );
    refreshed.value = false;
    if (path === "proofs") {
      proof.value = "";
      proofFiles.value = [];
      proofPickerKey.value++;
      proofDraft.saved();
    } else {
      note.value = "";
      newAssignee.value = selected.value?.assignee || "";
      detailDraft.saved();
    }
    await load();
  } catch (e) {
    conflict.value = e instanceof ApiError && e.status === 409;
    error.value =
      errorText(e) +
      (conflict.value
        ? " 최신 작업 불러오기로 변경 내용을 확인한 뒤 다시 처리하세요. 입력 내용은 유지됩니다."
        : "");
  } finally {
    busy.value = false;
  }
}
onMounted(async () => {
  await load();
  if (props.initialTask) {
    await select(props.initialTask);
    await nextTick();
    if (typeof route.query.proof === "string")
      document
        .getElementById("proof-" + route.query.proof)
        ?.scrollIntoView({ block: "center" });
  }
  if (reviewer.value)
    try {
      users.value = await api("/api/users");
    } catch (e) {
      error.value = errorText(e);
    }
});
</script>
<template>
  <fieldset :disabled="busy" class="task-surface">
    <section class="panel">
      <div class="section-heading">
        <h2>
          대응 작업
          <span v-if="!loading && !listError" class="muted">{{
            tasks.length
          }}</span>
        </h2>
        <button v-if="reviewer && !effectivelyClosed" @click="toggleCreate">
          {{ creating ? "등록 닫기" : "작업 만들기" }}
        </button>
      </div>
      <p class="note">
        작업 완료는 대상 판정이나 누락된 출고 연결을 변경하지 않습니다.
      </p>
      <p v-if="error && !selected" class="error" role="alert">{{ error }}</p>
      <p v-if="listError" class="error" role="alert">
        {{ listError }} <button @click="load">작업 목록 다시 시도</button>
      </p>
      <p v-if="loading" role="status">작업을 불러오는 중입니다…</p>
      <form v-if="creating" @submit.prevent="create">
        <label
          >작업 제목<input v-model="title" required maxlength="200"
        /></label>
        <div class="form-grid">
          <label
            >작업 유형<select v-model="taskType">
              <option
                v-for="type in [
                  'QUARANTINE',
                  'SHIPMENT_HOLD',
                  'SALES_HOLD',
                  'SUPPLIER_CHECK',
                  'RETURN_CONFIRMATION',
                  'NOTICE_PREPARATION',
                ]"
                :key="type"
                :value="type"
              >
                {{ label(type) }}
              </option>
            </select></label
          ><label
            >대상 범위<select v-model="targetType" @change="targetId = ''">
              <option value="CASE">사건 전체</option>
              <option :disabled="!assessment" value="INVENTORY">
                선택 판정의 재고
              </option>
              <option :disabled="!assessment" value="SHIPMENT">
                선택 판정의 출고
              </option>
            </select></label
          >
        </div>
        <label v-if="targetType !== 'CASE'"
          >작업 대상<select v-model="targetId" required>
            <option value="">대상 선택</option>
            <template v-if="targetType === 'INVENTORY'"
              ><option
                v-for="item in assessment?.inventory"
                :key="item.inventoryId"
                :value="item.inventoryId"
              >
                {{ item.inventoryId }} · {{ item.warehouse }} ·
                {{ label(item.decision) }} · {{ item.quantity }} EA
              </option></template
            ><template v-else
              ><option
                v-for="item in assessment?.shipments"
                :key="item.shipmentId"
                :value="item.shipmentId"
              >
                {{ item.shipmentId }} · 대상 {{ item.target }} / 확인 필요
                {{ item.needsReview }} EA
              </option></template
            >
          </select></label
        ><label
          >담당자<select v-model="assignee" aria-label="담당자">
            <option value="">미배정</option>
            <option
              v-for="person in users.filter((u) => u.enabled)"
              :key="person.username"
              :value="person.username"
            >
              {{ person.displayName }} ({{ person.username }})
            </option>
          </select></label
        ><label
          >작업 지시<textarea
            v-model="instructions"
            required
            maxlength="10000"
          /></label
        ><button class="primary" :disabled="busy">작업 등록</button>
      </form>
      <div
        v-if="!loading && !listError && !tasks.length && !creating"
        class="empty"
      >
        등록된 대응 작업이 없습니다.
      </div>
      <div
        v-for="task in loading || listError ? [] : tasks"
        :key="task.id"
        class="task-card"
      >
        <div class="section-heading">
          <div>
            <h3>{{ task.title }}</h3>
            <span class="muted small"
              >{{ label(task.taskType) }} · {{ label(task.targetType) }}
              {{ task.targetId }} · {{ task.assignee || "미배정" }}</span
            >
          </div>
          <span class="badge" :class="task.status">{{
            label(task.status)
          }}</span>
        </div>
        <button @click="select(task.id)">작업 상세</button>
      </div>
    </section>
    <p v-if="selecting" role="status">작업 상세를 불러오는 중입니다…</p>
    <section v-if="selected" class="panel">
      <div class="section-heading">
        <h2>{{ selected.title }}</h2>
        <button @click="closeDetail">상세 닫기</button>
      </div>
      <button :disabled="selecting" @click="select(selected.id, true)">
        최신 작업 불러오기
      </button>
      <p v-if="refreshed" class="note" role="status">
        최신 작업을 불러왔습니다. 담당자·상태·처리 회차를 확인하고 유지된 입력
        내용을 검토하세요.
      </p>
      <p v-if="error" class="error" role="alert">{{ error }}</p>
      <fieldset :disabled="conflict || selecting" class="task-surface">
        <p class="prewrap">{{ selected.instructions }}</p>
        <p class="muted small">
          처리 회차 {{ selected.reviewRound }} · 기록 버전
          {{ selected.version }} · 담당 {{ selected.assignee || "미배정" }}
        </p>
        <template v-if="!effectivelyClosed"
          ><form
            v-if="reviewer && ['OPEN', 'IN_PROGRESS'].includes(selected.status)"
            @submit.prevent="act('assignment', { assignee: newAssignee, note })"
          >
            <label
              >담당자 변경<select v-model="newAssignee" required>
                <option value="">담당자 선택</option>
                <option
                  v-for="person in users.filter((u) => u.enabled)"
                  :key="person.username"
                  :value="person.username"
                >
                  {{ person.displayName }} ({{ person.username }})
                </option>
              </select></label
            ><label
              >변경 사유<input
                v-model="note"
                required
                maxlength="2000" /></label
            ><button :disabled="busy">담당자 저장</button>
          </form>
          <form
            v-if="canWork && selected.status === 'IN_PROGRESS'"
            @submit.prevent="act('proofs', { evidenceText: proof })"
          >
            <label
              >처리 증빙<textarea
                v-model="proof"
                required
                maxlength="100000"
                placeholder="실제 조치 대상·수량·처리 내용과 근거를 기록하세요"
              /></label
            ><FilePicker
              :key="proofPickerKey"
              v-model="proofFiles"
              label="작업 증빙 파일"
            /><button :disabled="busy">증빙 제출</button>
          </form>
          <form
            v-if="canWork && selected.status !== 'CANCELLED'"
            @submit.prevent
          >
            <label
              >상태 변경 사유<input
                v-model="note"
                maxlength="2000"
                placeholder="시작·완료·취소·재개 사유"
            /></label>
            <div class="inline">
              <button
                v-if="selected.status === 'OPEN'"
                :disabled="busy || !note.trim() || !selected.assignee"
                @click="act('transitions', { action: 'START', note })"
              >
                작업 시작</button
              ><button
                v-if="reviewer && selected.status === 'IN_PROGRESS'"
                class="primary"
                :disabled="busy || !note.trim()"
                @click="act('transitions', { action: 'COMPLETE', note })"
              >
                검토 후 작업 완료</button
              ><button
                v-if="reviewer && selected.status === 'COMPLETED'"
                :disabled="busy || !note.trim()"
                @click="act('transitions', { action: 'REOPEN', note })"
              >
                작업 재개</button
              ><button
                v-if="
                  reviewer && ['OPEN', 'IN_PROGRESS'].includes(selected.status)
                "
                class="danger"
                :disabled="busy || !note.trim()"
                @click="act('transitions', { action: 'CANCEL', note })"
              >
                작업 취소
              </button>
            </div>
          </form></template
        >
        <h3 class="spaced">처리 증빙 이력</h3>
        <div
          v-for="item in selected.proofs"
          :key="item.id"
          :id="'proof-' + item.id"
          :class="{ 'review-highlight': route.query.proof === item.id }"
          class="proof"
        >
          <span class="badge" :class="item.status">{{
            label(item.status)
          }}</span
          ><span class="small muted">
            · {{ item.reviewRound }}회차 · {{ item.submittedBy }}</span
          >
          <p class="prewrap">{{ item.evidenceText }}</p>
          <Attachments :case-id="caseId" :target-id="item.id" type="proofId" />
          <p v-if="item.reviewNote" class="small">
            검토 기록: {{ item.reviewNote }}
          </p>
          <div
            v-if="
              !effectivelyClosed &&
              reviewer &&
              item.status === 'PENDING' &&
              item.reviewRound === selected.reviewRound
            "
            class="inline"
          >
            <input
              v-model="note"
              aria-label="증빙 검토 사유"
              placeholder="증빙 검토 사유"
              maxlength="2000"
            /><button
              :disabled="busy || !note.trim()"
              @click="
                act('proofs/' + item.id + '/review', {
                  decision: 'ACCEPTED',
                  note,
                })
              "
            >
              증빙 승인</button
            ><button
              :disabled="busy || !note.trim()"
              @click="
                act('proofs/' + item.id + '/review', {
                  decision: 'REJECTED',
                  note,
                })
              "
            >
              증빙 반려
            </button>
          </div>
        </div>
        <details>
          <summary>작업 변경 이력</summary>
          <p
            v-for="event in selected.events"
            :key="event.version"
            class="small"
          >
            {{ dateText(event.at) }} · {{ event.actor }} · {{ event.type }} ({{
              event.version
            }})
          </p>
        </details>
      </fieldset>
    </section>
  </fieldset>
</template>
<style scoped>
.review-highlight {
  outline: 2px solid #126f58;
  outline-offset: 4px;
}
.task-surface {
  border: 0;
  padding: 0;
  margin: 0;
  min-inline-size: 0;
}
form {
  border-bottom: 1px solid #e6ece8;
  padding: 16px 0;
  margin-bottom: 15px;
}
.spaced {
  margin-top: 25px;
}
.inline input {
  min-width: 160px;
  flex: 1;
}
</style>
