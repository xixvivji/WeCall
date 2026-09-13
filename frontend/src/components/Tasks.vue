<script setup lang="ts">
import { ref, onMounted, computed, nextTick } from "vue";
import { useRoute } from "vue-router";
import { api, user, label, errorText, dateText, type Assessment } from "../api";
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
const reviewer = computed(() => user.value?.roles.includes("REVIEWER")),
  canWork = computed(
    () => reviewer.value || selected.value?.assignee === user.value?.username,
  );
async function load() {
  try {
    tasks.value = await api(`/api/v1/recalls/${props.caseId}/tasks`);
  } catch (e) {
    error.value = errorText(e);
  }
}
async function select(id: string) {
  error.value = "";
  selected.value = undefined;
  note.value = "";
  proof.value = "";
  try {
    selected.value = await api(`/api/v1/recalls/${props.caseId}/tasks/${id}`);
    newAssignee.value = selected.value?.assignee || "";
  } catch (e) {
    error.value = errorText(e);
  }
}
async function create() {
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
    await load();
  } catch (e) {
    error.value = errorText(e);
  } finally {
    busy.value = false;
  }
}
async function act(path: string, body: Record<string, unknown>) {
  if (!selected.value) return;
  busy.value = true;
  error.value = "";
  try {
    selected.value = await api(
      `/api/v1/recalls/${props.caseId}/tasks/${selected.value.id}/${path}`,
      { ...body, expectedVersion: selected.value.version },
    );
    note.value = "";
    proof.value = "";
    await load();
  } catch (e) {
    error.value = errorText(e) + " 최신 작업을 다시 열고 확인하세요.";
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
          대응 작업 <span class="muted">{{ tasks.length }}</span>
        </h2>
        <button v-if="reviewer && !closed" @click="creating = !creating">
          {{ creating ? "등록 닫기" : "작업 만들기" }}
        </button>
      </div>
      <p class="note">
        작업 완료는 대상 판정이나 누락된 출고 연결을 변경하지 않습니다.
      </p>
      <p v-if="error" class="error" role="alert">{{ error }}</p>
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
      <div v-if="!tasks.length && !creating" class="empty">
        등록된 대응 작업이 없습니다.
      </div>
      <div v-for="task in tasks" :key="task.id" class="task-card">
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
    <section v-if="selected" class="panel">
      <div class="section-heading">
        <h2>{{ selected.title }}</h2>
        <button @click="selected = undefined">상세 닫기</button>
      </div>
      <p class="prewrap">{{ selected.instructions }}</p>
      <p class="muted small">
        처리 회차 {{ selected.reviewRound }} · 기록 버전
        {{ selected.version }} · 담당 {{ selected.assignee || "미배정" }}
      </p>
      <p v-if="error" class="error" role="alert">{{ error }}</p>
      <template v-if="!closed"
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
            >변경 사유<input v-model="note" required maxlength="2000" /></label
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
          ><button :disabled="busy">증빙 제출</button>
        </form>
        <form v-if="canWork && selected.status !== 'CANCELLED'" @submit.prevent>
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
        <span class="badge" :class="item.status">{{ label(item.status) }}</span
        ><span class="small muted">
          · {{ item.reviewRound }}회차 · {{ item.submittedBy }}</span
        >
        <p class="prewrap">{{ item.evidenceText }}</p>
        <p v-if="item.reviewNote" class="small">
          검토 기록: {{ item.reviewNote }}
        </p>
        <div
          v-if="
            !closed &&
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
        <p v-for="event in selected.events" :key="event.version" class="small">
          {{ dateText(event.at) }} · {{ event.actor }} · {{ event.type }} ({{
            event.version
          }})
        </p>
      </details>
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
