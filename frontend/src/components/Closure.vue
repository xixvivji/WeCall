<script setup lang="ts">
import { ref, onMounted, watch, computed } from "vue";
import { api, user, errorText, dateText, type CaseDetail } from "../api";
const props = defineProps<{ recall: CaseDetail; assessmentId?: string }>(),
  emit = defineEmits<{ changed: [] }>();
interface Check {
  ready: boolean;
  version: number;
  blockers: { code: string; message: string; count: number }[];
  warnings: { code: string; message: string; count: number }[];
}
const check = ref<Check>(),
  history = ref<
    {
      type: string;
      version: number;
      at: string;
      reviewer: string;
      note: string;
    }[]
  >([]),
  error = ref(""),
  busy = ref(false),
  loading = ref(false),
  confirmed = ref(false),
  note = ref(""),
  reviewer = computed(() => user.value?.roles.includes("REVIEWER"));
let generation = 0;
async function load() {
  const ticket = ++generation;
  loading.value = true;
  error.value = "";
  check.value = undefined;
  confirmed.value = false;
  try {
    const [c, h] = await Promise.all([
      api<Check>(
        `/api/v1/recalls/${props.recall.id}/closure-check` +
          (props.assessmentId ? "?assessmentId=" + props.assessmentId : ""),
      ),
      api<{ history: typeof history.value }>(
        `/api/v1/recalls/${props.recall.id}/lifecycle`,
      ),
    ]);
    if (ticket !== generation) return;
    check.value = c;
    history.value = h.history;
  } catch (e) {
    if (ticket === generation) error.value = errorText(e);
  } finally {
    if (ticket === generation) loading.value = false;
  }
}
async function save() {
  if (busy.value || loading.value || !check.value) return;
  busy.value = true;
  error.value = "";
  try {
    const closed = props.recall.status === "CLOSED";
    await api(
      `/api/v1/recalls/${props.recall.id}/${closed ? "reopen" : "closure"}`,
      {
        expectedVersion: check.value?.version ?? props.recall.lifecycleVersion,
        note: note.value,
        ...(!closed
          ? {
              assessmentId: props.assessmentId,
              responseCoverageConfirmed: confirmed.value,
            }
          : {}),
      },
    );
    emit("changed");
    note.value = "";
    await load();
  } catch (e) {
    error.value = errorText(e);
    check.value = undefined;
    confirmed.value = false;
  } finally {
    busy.value = false;
  }
}
onMounted(load);
watch(() => props.assessmentId, load);
</script>
<template>
  <section class="panel">
    <div class="section-heading">
      <h2>사건 종료 점검</h2>
      <button :disabled="busy || loading" @click="load">다시 점검</button>
    </div>
    <p class="note">
      선택한 판정과 현재 대응 이력을 기준으로 점검합니다. 대상 범위의 실제 대응
      여부는 검토자가 확인해야 합니다.
    </p>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <p v-if="loading" role="status">종료 조건을 점검하는 중입니다…</p>
    <template v-if="check"
      ><p v-if="check.ready" class="success">
        시스템 점검을 통과했습니다. 대응 범위를 최종 확인하세요.
      </p>
      <div
        v-for="item in check.blockers"
        :key="item.code"
        class="evidence-line"
      >
        <strong>{{ item.message }}</strong
        ><small class="muted">{{ item.count }}건/수량</small>
      </div>
      <div v-for="item in check.warnings" :key="item.code" class="note">
        {{ item.message }} · {{ item.count }}
      </div></template
    >
    <form v-if="reviewer" @submit.prevent="save">
      <label v-if="recall.status !== 'CLOSED'" class="check-label"
        ><input v-model="confirmed" type="checkbox" required />회수 대상 범위,
        취소된 작업과 이전 판정 작업까지 확인했으며 필요한 대응을
        완료했습니다.</label
      ><label
        >{{ recall.status === "CLOSED" ? "재개 사유" : "종료 검토 기록"
        }}<textarea v-model="note" required maxlength="2000" /></label
      ><button
        class="primary"
        :disabled="
          busy ||
          !check ||
          (recall.status !== 'CLOSED' && (!check.ready || !confirmed))
        "
      >
        {{
          recall.status === "CLOSED" ? "사건 재개" : "최종 검토 후 사건 종료"
        }}
      </button>
    </form>
  </section>
  <section class="panel">
    <h2>종료 · 재개 이력</h2>
    <p v-if="!history.length" class="muted">아직 종료·재개 기록이 없습니다.</p>
    <div v-for="event in history" :key="event.version" class="evidence-line">
      <strong>{{ event.type === "CLOSED" ? "사건 종료" : "사건 재개" }}</strong>
      <p class="prewrap">{{ event.note }}</p>
      <small class="muted"
        >{{ event.reviewer }} · {{ dateText(event.at) }}</small
      >
    </div>
  </section>
</template>
