<script setup lang="ts">
import { ref, onMounted } from "vue";
import { api, downloadAttachment, errorText, dateText } from "../api";
const props = defineProps<{
  caseId: string;
  targetId: string;
  type: "evidenceId" | "proofId";
}>();
interface FileRow {
  id: string;
  filename: string;
  byteSize: number;
  sha256: string;
  uploadedBy: string;
  createdAt: string;
}
const rows = ref<FileRow[]>(),
  error = ref(""),
  busy = ref(false);
async function load() {
  error.value = "";
  try {
    rows.value = await api(
      `/api/v1/recalls/${props.caseId}/attachments?${props.type}=${props.targetId}`,
    );
  } catch (e) {
    error.value = errorText(e);
  }
}
async function download(row: FileRow) {
  busy.value = true;
  error.value = "";
  try {
    await downloadAttachment(props.caseId, row.id, row.filename);
  } catch (e) {
    error.value = errorText(e);
  } finally {
    busy.value = false;
  }
}
onMounted(load);
</script>
<template>
  <div class="attachments">
    <h3>첨부 파일</h3>
    <p v-if="error" class="error" role="alert">
      {{ error }} <button @click="load">첨부 다시 조회</button>
    </p>
    <p v-if="rows && !rows.length" class="muted">첨부 파일이 없습니다.</p>
    <div v-for="row in rows" :key="row.id" class="attachment">
      <button :disabled="busy" @click="download(row)">
        {{ row.filename }} 다운로드
      </button>
      <p class="muted">
        {{ row.byteSize.toLocaleString() }} bytes · {{ row.uploadedBy }} ·
        {{ dateText(row.createdAt) }}
      </p>
      <p class="hash">SHA-256 {{ row.sha256 }}</p>
    </div>
    <p v-if="rows?.length" class="footnote">
      내용·진위 확인은 검토자의 판단이 필요합니다. 미리보기와 OCR은 제공하지
      않습니다.
    </p>
  </div>
</template>
<style scoped>
.attachments {
  padding: 12px 0;
}
.attachment {
  border-bottom: 1px solid #e6ece8;
  padding: 10px 0;
}
.attachment button {
  max-width: 100%;
  overflow-wrap: anywhere;
}
.hash {
  font-size: 12px;
  font-family: monospace;
  overflow-wrap: anywhere;
}
</style>
