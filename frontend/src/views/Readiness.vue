<script setup lang="ts">
import { ref, onMounted } from "vue";
import Provenance from "../components/Provenance.vue";
import { useRoute } from "vue-router";
import { api, errorText, dateText, type Page } from "../api";
const id = String(useRoute().params.id);
interface Summary {
  id: string;
  asOf: string;
  productCount: number;
  receiptCount: number;
  inventoryCount: number;
  shipmentCount: number;
  shipment_allocationCount: number;
  receipts: {
    missingLot: number;
    missingExpiry: number;
    missingEither: number;
  };
  shipments: {
    unlinkedCount: number;
    partialCount: number;
    fullyLinkedCount: number;
    zeroQuantityCount: number;
    unlinkedQuantity: number;
  };
}
interface Row {
  id: string;
  productId: string;
  lotNumber?: string | null;
  expiryDate?: string | null;
  orderId?: string;
  quantity: number;
  linked?: number;
  unlinked?: number;
}
const summary = ref<Summary>(),
  rows = ref<Page<Row>>(),
  type = ref("receipts"),
  page = ref(0),
  busy = ref(false),
  error = ref("");
async function load(reset = false) {
  if (reset) page.value = 0;
  busy.value = true;
  error.value = "";
  rows.value = undefined;
  try {
    const [s, r] = await Promise.all([
      api<Summary>(`/api/v1/datasets/${id}/readiness`),
      api<Page<Row>>(
        `/api/v1/datasets/${id}/readiness/issues?type=${type.value}&page=${page.value}`,
      ),
    ]);
    summary.value = s;
    rows.value = r;
  } catch (e) {
    error.value = errorText(e);
  } finally {
    busy.value = false;
  }
}
onMounted(() => load());
</script>
<template>
  <RouterLink to="/datasets">← 데이터 목록</RouterLink>
  <div class="page-heading">
    <div>
      <p class="eyebrow">DATA READINESS</p>
      <h1>데이터 준비 상태</h1>
      <p class="version">{{ id }}</p>
      <p v-if="summary" class="muted">기준 시각 {{ dateText(summary.asOf) }}</p>
    </div>
  </div>
  <p v-if="error" class="error" role="alert">{{ error }}</p>
  <p v-if="busy" role="status">점검 결과를 불러오는 중…</p>
  <Provenance v-if="summary" :dataset-id="id" />
  <template v-if="summary"
    ><section class="panel">
      <h2>등록 현황</h2>
      <div class="counts">
        <p>
          상품 <strong>{{ summary.productCount }}건</strong>
        </p>
        <p>
          입고 <strong>{{ summary.receiptCount }}건</strong>
        </p>
        <p>
          재고 <strong>{{ summary.inventoryCount }}건</strong>
        </p>
        <p>
          출고 <strong>{{ summary.shipmentCount }}건</strong>
        </p>
        <p>
          출고·입고 연결
          <strong>{{ summary.shipment_allocationCount }}건</strong>
        </p>
      </div>
    </section>
    <section class="panel">
      <h2>누락·연결 점검</h2>
      <p>
        제조번호 누락 입고 {{ summary.receipts.missingLot }}건 · 소비기한 누락
        입고 {{ summary.receipts.missingExpiry }}건
      </p>
      <p>
        하나 이상 누락된 입고
        <strong>{{ summary.receipts.missingEither }}건</strong> (중복 제외)
      </p>
      <p>
        출고 연결 없음 {{ summary.shipments.unlinkedCount }}건 · 일부 연결
        {{ summary.shipments.partialCount }}건 · 전체 수량 연결
        {{ summary.shipments.fullyLinkedCount }}건
      </p>
      <p>
        0수량 출고 {{ summary.shipments.zeroQuantityCount }}건 · 연결 미확인
        수량 <strong>{{ summary.shipments.unlinkedQuantity }} EA</strong>
      </p>
      <p class="note">
        이 화면은 데이터 누락과 연결 기록을 점검합니다. 누락이 있어도 회수
        조건에 따라 판정 결과는 달라지며, 연결이 완전해도 회수 대상 여부가
        확정되는 것은 아닙니다. 원본 값을 임의로 채우거나 출고 연결을 추정하지
        않습니다.
      </p>
    </section></template
  >
  <section class="panel">
    <h2>보완할 기록</h2>
    <label
      >점검 항목<select
        v-model="type"
        :disabled="busy"
        aria-label="점검 항목"
        @change="load(true)"
      >
        <option value="receipts">제조번호·소비기한 누락 입고</option>
        <option value="shipments">연결 미확인 수량이 있는 출고</option>
      </select></label
    >
    <template v-if="rows"
      ><p v-if="!rows.items.length" class="empty">
        이 항목에 해당하는 기록이 없습니다.
      </p>
      <div v-else class="table-scroll">
        <table>
          <thead>
            <tr>
              <th>기록 ID</th>
              <th>상품 ID</th>
              <template v-if="type === 'receipts'"
                ><th>제조번호</th>
                <th>소비기한</th>
                <th>입고 수량 EA</th></template
              ><template v-else
                ><th>주문 ID</th>
                <th>출고 수량 EA</th>
                <th>연결 수량 EA</th>
                <th>연결 미확인 EA</th></template
              >
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in rows.items" :key="row.id">
              <td>{{ row.id }}</td>
              <td>{{ row.productId }}</td>
              <template v-if="type === 'receipts'"
                ><td>{{ row.lotNumber ?? "누락" }}</td>
                <td>{{ row.expiryDate ?? "누락" }}</td>
                <td>{{ row.quantity }}</td></template
              ><template v-else
                ><td>{{ row.orderId }}</td>
                <td>{{ row.quantity }}</td>
                <td>{{ row.linked }}</td>
                <td>{{ row.unlinked }}</td></template
              >
            </tr>
          </tbody>
        </table>
      </div>
      <footer class="pagination">
        <span
          >{{ rows.totalElements }}건 · {{ page + 1 }} /
          {{ Math.max(1, rows.totalPages) }}</span
        >
        <div>
          <button
            :disabled="busy || page === 0"
            @click="
              page--;
              load();
            "
          >
            이전</button
          ><button
            :disabled="busy || page + 1 >= rows.totalPages"
            @click="
              page++;
              load();
            "
          >
            다음
          </button>
        </div>
      </footer></template
    >
    <p class="note">
      입고 누락값은 해당 사건의 입고 증거 검토로 보완한 새 데이터 버전에서
      확인합니다. 출고 연결은 실제 확인된 기록으로 새 CSV 버전을 등록하세요. 이
      화면은 원본을 수정하지 않습니다.
    </p>
  </section>
</template>
<style scoped>
.counts {
  display: flex;
  gap: 24px;
  flex-wrap: wrap;
}
.counts strong {
  display: block;
  margin-top: 8px;
}
.version {
  overflow-wrap: anywhere;
  font-size: 13px;
}
</style>
