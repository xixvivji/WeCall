<script setup lang="ts">
import { ref, onMounted } from "vue";
import {
  api,
  user,
  errorText,
  dateText,
  type Page,
  type Dataset,
} from "../api";
const page = ref(0),
  data = ref<Page<Dataset>>(),
  error = ref(""),
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
async function load() {
  error.value = "";
  try {
    data.value = await api("/api/v1/datasets?page=" + page.value + "&size=20");
  } catch (e) {
    error.value = errorText(e);
  }
}
async function upload() {
  busy.value = true;
  error.value = "";
  success.value = "";
  try {
    const body = new FormData(form.value);
    body.set("asOf", new Date(asOf.value).toISOString());
    const result = await api<{
      datasetId: string;
      unlinkedShipmentQuantity: number;
    }>("/api/v1/datasets", body);
    success.value = `등록 완료 · 연결 기록이 없는 출고 ${result.unlinkedShipmentQuantity} EA는 확인 필요로 남습니다.`;
    form.value?.reset();
    asOf.value = "";
    page.value = 0;
    await load();
  } catch (e) {
    error.value = errorText(e);
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
  <p v-if="error" class="error" role="alert">{{ error }}</p>
  <p v-if="success" class="success" role="status">{{ success }}</p>
  <section v-if="user?.roles.includes('REVIEWER')" class="panel">
    <h2>CSV 데이터 등록</h2>
    <p class="note">
      UTF-8 CSV 5종을 함께 등록하세요. 파일당 5MB, 10,000행까지 지원합니다. 연결
      기록은 실제 확인된 내용만 입력하세요.
    </p>
    <form ref="form" @submit.prevent="upload">
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
      ><span>{{ data?.totalElements ?? 0 }}건</span>
    </div>
    <div v-if="!data?.items.length" class="empty">
      등록된 데이터가 없습니다.
    </div>
    <div v-else class="table-scroll">
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
    <footer class="pagination">
      <span
        >{{ data?.totalPages ? page + 1 : 0 }} /
        {{ data?.totalPages ?? 0 }} 페이지</span
      >
      <div>
        <button
          :disabled="page === 0"
          @click="
            page--;
            load();
          "
        >
          이전</button
        ><button
          :disabled="!data || page + 1 >= data.totalPages"
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
