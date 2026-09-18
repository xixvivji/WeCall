<script setup lang="ts">
import AiInputStatus from "./AiInputStatus.vue";
import { ref, onMounted } from "vue";
import { api, downloadSource, errorText, dateText, user } from "../api";
import { useDraftGuard } from "../drafts";
const props = defineProps<{ caseId: string; closed: boolean }>();
const emit = defineEmits<{ updated: [] }>();
interface Revision {
  version: number;
  actor: string | null;
  note: string;
  createdAt: string;
  sourceText: string;
  extractedText: string | null;
}
interface History {
  currentVersion: number;
  page: number;
  totalPages: number;
  items: Revision[];
  document: {
    id: string;
    filename: string;
    sha256: string;
    uploadedBy: string;
    createdAt: string;
    scanStatus: string;
  } | null;
}
const data = ref<History>(),
  selected = ref<Revision>(),
  error = ref(""),
  loading = ref(false),
  busy = ref(false),
  editing = ref(false),
  text = ref(""),
  note = ref(""),
  page = ref(0);
const draft = useDraftGuard(() => ({ text: text.value, note: note.value }));
const prefix = `/api/v1/recalls/${props.caseId}/source`;
async function load() {
  if (loading.value) return;
  loading.value = true;
  error.value = "";
  try {
    data.value = await api<History>(`${prefix}?page=${page.value}`);
    selected.value = await api<Revision>(
      `${prefix}/revisions/${data.value.currentVersion}`,
    );
  } catch (e) {
    error.value = errorText(e);
  } finally {
    loading.value = false;
  }
}
async function select(version: number) {
  if (!draft.discard()) return;
  editing.value = false;
  text.value = "";
  note.value = "";
  draft.saved();
  loading.value = true;
  error.value = "";
  try {
    selected.value = await api<Revision>(`${prefix}/revisions/${version}`);
  } catch (e) {
    error.value = errorText(e);
  } finally {
    loading.value = false;
  }
}
async function changePage(delta: number) {
  if (!draft.discard()) return;
  editing.value = false;
  text.value = "";
  note.value = "";
  draft.saved();
  page.value += delta;
  await load();
}
function edit() {
  if (
    !selected.value ||
    !data.value ||
    selected.value.version !== data.value.currentVersion
  )
    return;
  text.value = selected.value.sourceText;
  note.value = "";
  editing.value = true;
  draft.saved();
}
function cancel() {
  if (!draft.discard()) return;
  editing.value = false;
  text.value = "";
  note.value = "";
  draft.saved();
}
async function save() {
  if (busy.value || !selected.value) return;
  busy.value = true;
  error.value = "";
  try {
    await api(`${prefix}/revisions`, {
      expectedVersion: selected.value.version,
      sourceText: text.value,
      note: note.value,
    });
    editing.value = false;
    text.value = "";
    note.value = "";
    draft.saved();
    page.value = 0;
    await load();
    emit("updated");
  } catch (e) {
    error.value = errorText(e);
  } finally {
    busy.value = false;
  }
}
async function download() {
  if (!data.value?.document) return;
  busy.value = true;
  error.value = "";
  try {
    const doc = data.value.document;
    await downloadSource(props.caseId, doc.id, doc.filename);
  } catch (e) {
    error.value = errorText(e);
  } finally {
    busy.value = false;
  }
}
onMounted(load);
</script>
<template>
  <section class="panel">
    <h2>원본 · 수정 이력</h2>
    <p v-if="error" role="alert" class="error">
      {{ error }}
      <button v-if="!editing" :disabled="loading" @click="load">
        원문 이력 다시 조회
      </button>
    </p>
    <p v-if="loading" role="status">원문 이력을 불러오는 중입니다…</p>
    <template v-if="data">
      <div v-if="data.document">
        <button :disabled="busy" @click="download">
          {{ data.document.filename }} 원본 다운로드
        </button>
        <p class="muted">
          등록자 {{ data.document.uploadedBy }} ·
          {{ dateText(data.document.createdAt) }} ·
          {{
            data.document.scanStatus === "CLEAN"
              ? "등록 시 위협 미탐지"
              : "악성 파일 미검사"
          }}
        </p>
        <p class="hash">SHA-256 {{ data.document.sha256 }}</p>
      </div>
      <p v-else class="muted">
        보관된 PDF 원본이 없습니다. 직접 입력했거나 원본 보관 기능 도입 전
        등록한 사건입니다.
      </p>
      <p>
        현재 원문 v{{ data.currentVersion }}. 수정 전 AI 분석·조건·판정 기록은
        유지됩니다. 원문 변경 후에는 다시 분석하고 관련 조건을 검토하세요.
      </p>
      <div class="versions">
        <button
          v-for="item in data.items"
          :key="item.version"
          :disabled="loading || busy"
          :aria-pressed="selected?.version === item.version"
          @click="select(item.version)"
        >
          원문 v{{ item.version }} · {{ item.actor || "등록자 기록 없음" }}
        </button>
      </div>
      <div class="pagination">
        <button
          :disabled="loading || busy || page === 0"
          @click="changePage(-1)"
        >
          이전 원문 이력</button
        ><span>{{ page + 1 }} / {{ data.totalPages }}</span
        ><button
          :disabled="loading || busy || page + 1 >= data.totalPages"
          @click="changePage(1)"
        >
          다음 원문 이력
        </button>
      </div>
    </template>
    <template v-if="selected && !loading">
      <h3>원문 v{{ selected.version }}</h3>
      <p>
        {{ selected.actor || "등록자 기록 없음" }} ·
        {{ dateText(selected.createdAt) }} · {{ selected.note }}
      </p>
      <div class="two-col">
        <div>
          <h3>PDF에서 추출한 원문</h3>
          <pre v-if="selected.extractedText !== null">{{
            selected.extractedText
          }}</pre>
          <p v-else class="muted">PDF 추출본이 없습니다.</p>
        </div>
        <div>
          <h3>사람이 확인한 원문</h3>
          <pre>{{ selected.sourceText }}</pre>
        </div>
      </div>
      <p v-if="selected.extractedText !== null" class="note">
        {{
          selected.extractedText === selected.sourceText
            ? "PDF 추출본과 동일합니다."
            : "PDF 추출본과 다릅니다. 두 원문을 대조해 주세요."
        }}
      </p>
      <button
        v-if="
          !editing &&
          !closed &&
          user?.roles.includes('REVIEWER') &&
          selected.version === data?.currentVersion
        "
        :disabled="busy"
        @click="edit"
      >
        원문 수정
      </button>
      <form v-if="editing" @submit.prevent="save">
        <label
          >수정할 원문<textarea
            v-model="text"
            required
            maxlength="100000"
            rows="10"
            :disabled="busy"
          />
        </label>
        <AiInputStatus :text="text" />
        <label
          >원문 수정 사유<textarea
            v-model="note"
            required
            maxlength="2000"
            rows="2"
            :disabled="busy"
          />
        </label>
        <p class="muted">
          기존 버전과 PDF 원본을 유지하며 새 버전을 추가합니다. 변경 전 분석
          결과는 새 조건으로 전환할 수 없습니다.
        </p>
        <button
          class="primary"
          :disabled="busy || !text.trim() || !note.trim()"
        >
          새 원문 버전 저장
        </button>
        <button type="button" :disabled="busy" @click="cancel">
          수정 취소
        </button>
      </form>
    </template>
  </section>
</template>
<style scoped>
pre {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  max-height: 480px;
  overflow-y: auto;
  font: inherit;
  background: #f6f8f6;
  padding: 12px;
}
.hash {
  overflow-wrap: anywhere;
  font-size: 12px;
}
.versions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
button {
  max-width: 100%;
  overflow-wrap: anywhere;
}
.two-col > div {
  min-width: 0;
}
</style>
