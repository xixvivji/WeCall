<script setup lang="ts">
import { ref, onMounted } from "vue";
import { api, errorText, dateText } from "../api";
const props = defineProps<{ datasetId: string }>();
interface Provenance {
  source: "CSV" | "EVIDENCE" | "LEGACY";
  uploadedBy: string | null;
  createdAt: string;
  files: {
    type: string;
    filename: string | null;
    sha256: string;
    byteSize: number;
    rowCount: number;
  }[];
  derivation: null | {
    evidenceId: string;
    caseId: string;
    parentDatasetId: string;
    reviewedBy: string;
    reviewedAt: string;
  };
}
const data = ref<Provenance>(),
  error = ref("");
const types: Record<string, string> = {
  products: "상품",
  receipts: "입고",
  inventory: "재고",
  shipments: "출고",
  shipment_allocations: "출고·입고 연결",
};
async function load() {
  error.value = "";
  try {
    data.value = await api(`/api/v1/datasets/${props.datasetId}/provenance`);
  } catch (e) {
    error.value = errorText(e);
  }
}
onMounted(load);
</script>
<template>
  <section class="panel">
    <h2>데이터 출처·원본 추적</h2>
    <p v-if="error" class="error" role="alert">
      {{ error }} <button @click="load">출처 다시 조회</button>
    </p>
    <template v-if="data"
      ><template v-if="data.source === 'CSV'"
        ><p>
          CSV 등록 · 등록자 {{ data.uploadedBy ?? "기록 없음" }} ·
          {{ dateText(data.createdAt) }}
        </p>
        <p class="note">
          SHA-256은 업로드한 파일 바이트의 식별값이며 내용의 진위나 정확성을
          보증하지 않습니다. 원본 파일 자체는 보관하지 않아 여기서 다시
          다운로드할 수 없습니다.
        </p>
        <div v-for="file in data.files" :key="file.type" class="source-file">
          <h3>
            {{ types[file.type] ?? file.type }} ·
            {{ file.filename ?? "파일명 기록 없음" }}
          </h3>
          <p class="muted">
            {{ file.byteSize.toLocaleString() }} bytes · 데이터
            {{ file.rowCount.toLocaleString() }}행
          </p>
          <p class="hash">SHA-256: {{ file.sha256 }}</p>
        </div></template
      ><template v-else-if="data.source === 'EVIDENCE' && data.derivation"
        ><p>입고 증거 승인으로 생성한 보완 데이터입니다.</p>
        <p>
          승인자 {{ data.derivation.reviewedBy }} ·
          {{ dateText(data.derivation.reviewedAt) }}
        </p>
        <p class="identifier">
          기준 데이터 {{ data.derivation.parentDatasetId }}
        </p>
        <div class="inline">
          <RouterLink
            :to="`/datasets/${data.derivation.parentDatasetId}/readiness`"
            >기준 데이터 출처 확인</RouterLink
          ><RouterLink
            :to="{
              path: '/recalls/' + data.derivation.caseId,
              query: { evidence: data.derivation.evidenceId },
            }"
            >보완 증거 확인</RouterLink
          >
        </div>
        <p class="note">
          새로 업로드한 CSV가 아니므로 기준 파일의 해시를 이 버전의 원본 해시로
          복사하지 않습니다. 기준 데이터를 따라가서 업로드 기록을 확인하세요.
        </p></template
      >
      <p v-else class="note">
        원본 추적 정보 도입 이전 데이터이거나 등록 출처 기록이 없는 버전입니다.
        파일명·해시·등록자를 추정해서 표시하지 않습니다.
      </p></template
    >
  </section>
</template>
<style scoped>
.source-file {
  border-top: 1px solid #e6ece8;
  padding: 12px 0;
}
.source-file h3,
.identifier,
.hash {
  overflow-wrap: anywhere;
}
.hash {
  font-family: monospace;
  font-size: 12px;
}
.inline {
  gap: 18px;
}
</style>
