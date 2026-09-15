<script setup lang="ts">
import { useDraftGuard } from "../drafts";
import { ref, onMounted, computed, nextTick } from "vue";
import {
  api,
  ApiError,
  type ValidationIssue,
  user,
  errorText,
  dateText,
  type Page,
  type Dataset,
} from "../api";
import CsvTemplates from "../components/CsvTemplates.vue";
const errorNotice = ref<HTMLElement>(),
  successNotice = ref<HTMLElement>();
const issues = ref<ValidationIssue[]>([]),
  issueCount = ref(0),
  issueFile = ref(""),
  issuePage = ref(0),
  uploadedNames = ref<Record<string, string>>({});
const filteredIssues = computed(() =>
  issues.value.filter(
    (row) => !issueFile.value || row.file === issueFile.value,
  ),
);
const visibleIssues = computed(() =>
  filteredIssues.value.slice(issuePage.value * 20, (issuePage.value + 1) * 20),
);
const page = ref(0),
  data = ref<Page<Dataset>>(),
  error = ref(""),
  listError = ref(""),
  loading = ref(false),
  success = ref(""),
  busy = ref(false),
  asOf = ref(""),
  form = ref<HTMLFormElement>();
const files = [
  ["products", "상품"],
  ["receipts", "입고"],
  ["inventory", "재고"],
  ["shipments", "출고"],
  ["shipmentAllocations", "출고·입고 연결"],
] as const;
const selectedFiles = ref<File[]>([]);
const draft = useDraftGuard(() => ({
  asOf: asOf.value,
  files: selectedFiles.value,
}));
function filesChanged() {
  selectedFiles.value = [
    ...(form.value?.querySelectorAll<HTMLInputElement>('input[type="file"]') ||
      []),
  ].flatMap((input) => Array.from(input.files || []));
}
let generation = 0;
async function load() {
  const ticket = ++generation;
  loading.value = true;
  listError.value = "";
  data.value = undefined;
  try {
    const result = await api<Page<Dataset>>(
      "/api/v1/datasets?page=" + page.value + "&size=20",
    );
    if (ticket === generation) data.value = result;
  } catch (e) {
    if (ticket === generation) listError.value = errorText(e);
  } finally {
    if (ticket === generation) loading.value = false;
  }
}
async function upload() {
  if (busy.value) return;
  busy.value = true;
  error.value = "";
  success.value = "";
  issues.value = [];
  issueCount.value = 0;
  issueFile.value = "";
  issuePage.value = 0;
  try {
    const body = new FormData(form.value);
    uploadedNames.value = Object.fromEntries(
      files.map(([key]) => [
        key === "shipmentAllocations"
          ? "shipment_allocations.csv"
          : key + ".csv",
        (body.get(key) as File)?.name || "",
      ]),
    );
    body.set("asOf", new Date(asOf.value).toISOString());
    const result = await api<{
      datasetId: string;
      unlinkedShipmentQuantity: number;
    }>("/api/v1/datasets", body);
    success.value = `등록 완료 · 연결 기록이 없는 출고 ${result.unlinkedShipmentQuantity} EA는 확인 필요로 남습니다.`;
    form.value?.reset();
    asOf.value = "";
    selectedFiles.value = [];
    draft.saved();
    page.value = 0;
    await load();
    await nextTick();
    successNotice.value?.focus();
  } catch (e) {
    if (e instanceof ApiError && e.issues.length) {
      issues.value = e.issues;
      issueCount.value = e.issueCount;
      error.value = "CSV 검증에 실패했습니다. 데이터는 등록되지 않았습니다.";
    } else error.value = errorText(e);
  } finally {
    busy.value = false;
  }
}
onMounted(load);
</script>
<template>
  <div class="page-heading">
    <div>
      <p class="eyebrow">DATA SNAPSHOTS</p>
      <h1>데이터 관리</h1>
      <p class="muted">
        상품·입고·재고·출고 정보를 한 시점의 데이터로 등록합니다.
      </p>
    </div>
  </div>
  <p v-if="error" ref="errorNotice" tabindex="-1" class="error" role="alert">
    {{ error }}
  </p>
  <section
    v-if="issues.length"
    class="panel csv-errors"
    aria-label="CSV 오류 내역"
  >
    <h2>CSV 오류 내역</h2>
    <p>
      {{ issueCount }}건의 오류를 확인했습니다.
      <span v-if="issueCount > issues.length"
        >앞 {{ issues.length }}건을 표시합니다. 수정 후 다시 등록하면 나머지
        오류를 확인할 수 있습니다.</span
      >
    </p>
    <p class="note">
      행 번호는 헤더를 1행으로 센 CSV 기록 번호입니다. 셀 안의 줄바꿈은 새
      행으로 세지 않습니다. 파일 전체 오류는 행 번호를 특정하지 못한 경우입니다.
      선택한 파일은 유지됩니다. 원본을 수정한 뒤 파일을 다시 선택하고 5종을 함께
      등록하세요.
    </p>
    <label
      >오류 파일<select
        v-model="issueFile"
        aria-label="오류 파일"
        @change="issuePage = 0"
      >
        <option value="">모든 파일</option>
        <option
          v-for="file in [
            ...new Set(issues.map((e) => e.file).filter(Boolean)),
          ]"
          :key="file"
          :value="file"
        >
          {{ file }}
        </option>
      </select></label
    >
    <div class="table-scroll">
      <table aria-label="CSV 검증 오류">
        <thead>
          <tr>
            <th>파일 종류 / 선택 파일</th>
            <th>행</th>
            <th>항목</th>
            <th>수정할 내용</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(issue, index) in visibleIssues" :key="index">
            <td>
              {{ issue.file || "파일 전체"
              }}<small v-if="issue.file && uploadedNames[issue.file]">{{
                uploadedNames[issue.file]
              }}</small>
            </td>
            <td>{{ issue.row ? issue.row + "행" : "파일 전체" }}</td>
            <td>{{ issue.field || "전체" }}</td>
            <td>{{ issue.message }}</td>
          </tr>
        </tbody>
      </table>
    </div>
    <footer class="pagination">
      <span
        >{{ filteredIssues.length }}건 중 {{ issuePage * 20 + 1 }}–{{
          Math.min((issuePage + 1) * 20, filteredIssues.length)
        }}</span
      >
      <div>
        <button :disabled="issuePage === 0" @click="issuePage--">
          오류 이전</button
        ><button
          :disabled="(issuePage + 1) * 20 >= filteredIssues.length"
          @click="issuePage++"
        >
          오류 다음
        </button>
      </div>
    </footer>
  </section>
  <p
    v-if="success"
    ref="successNotice"
    tabindex="-1"
    class="success"
    role="status"
  >
    {{ success }}
  </p>
  <section v-if="user?.roles.includes('REVIEWER')" class="panel">
    <h2>CSV 데이터 등록</h2>
    <CsvTemplates />
    <p class="note">
      UTF-8 CSV 5종을 함께 등록하세요. 파일당 5 MiB, 데이터 10,000행까지
      지원합니다. 연결 기록은 실제 확인된 내용만 입력하세요.
    </p>
    <form ref="form" @change="filesChanged" @submit.prevent="upload">
      <label
        >데이터 기준 시각 (현재 기기 시간대)<input
          v-model="asOf"
          type="datetime-local"
          required
      /></label>
      <div class="form-grid">
        <label v-for="[key, name] in files" :key="key"
          >{{ name }} CSV<input
            type="file"
            :name="key"
            required
            accept=".csv,text/csv"
        /></label>
      </div>
      <button class="primary" :disabled="busy">
        {{ busy ? "검증·등록 중…" : "데이터 검증 후 등록" }}
      </button>
    </form>
  </section>
  <section class="panel list-panel">
    <div class="list-caption">
      <strong>등록된 데이터</strong
      ><span v-if="data">{{ data.totalElements }}건</span>
    </div>
    <p v-if="listError" class="error" role="alert">
      {{ listError }}
      <button :disabled="loading" @click="load">다시 시도</button>
    </p>
    <p v-if="loading" class="empty" role="status">
      데이터를 불러오는 중입니다…
    </p>
    <div v-else-if="!listError && data && !data.items.length" class="empty">
      등록된 데이터가 없습니다.
    </div>
    <div v-else-if="!listError && data" class="table-scroll">
      <table>
        <thead>
          <tr>
            <th>데이터 버전</th>
            <th>기준 시각</th>
            <th>등록 시각</th>
            <th>점검</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="item in data.items" :key="item.id">
            <td>{{ item.id.slice(0, 8) }}</td>
            <td>{{ dateText(item.asOf) }}</td>
            <td>{{ dateText(item.createdAt) }}</td>
            <td>
              <RouterLink :to="`/datasets/${item.id}/readiness`"
                >준비 상태 점검</RouterLink
              >
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <footer v-if="data && !listError" class="pagination">
      <span
        >{{ data?.totalPages ? page + 1 : 0 }} /
        {{ data?.totalPages ?? 0 }} 페이지</span
      >
      <div>
        <button
          :disabled="page === 0 || loading || busy"
          @click="
            page--;
            load();
          "
        >
          이전</button
        ><button
          :disabled="!data || page + 1 >= data.totalPages || loading || busy"
          @click="
            page++;
            load();
          "
        >
          다음
        </button>
      </div>
    </footer>
  </section>
</template>

<style scoped>
.csv-errors td {
  overflow-wrap: anywhere;
  max-width: 420px;
}
.csv-errors small {
  display: block;
  color: #56665e;
}
</style>
