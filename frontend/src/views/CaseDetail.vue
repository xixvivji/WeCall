<script setup lang="ts">
import { ref, onMounted, computed } from "vue";
import { useRoute } from "vue-router";
import {
  api,
  user,
  errorText,
  label,
  dateText,
  type CaseDetail,
  type Condition,
  type RunRow,
  type Assessment,
  type Rule,
} from "../api";
import ConditionForm from "../components/ConditionForm.vue";
import Tasks from "../components/Tasks.vue";
import Closure from "../components/Closure.vue";
const id = String(useRoute().params.id),
  recall = ref<CaseDetail>(),
  conditions = ref<Condition[]>([]),
  runs = ref<RunRow[]>([]),
  assessment = ref<Assessment>(),
  runId = ref(""),
  tab = ref("overview"),
  error = ref(""),
  success = ref(""),
  busy = ref(false),
  creating = ref(false),
  confirm = ref<Record<string, boolean>>({});
const reviewer = computed(() => user.value?.roles.includes("REVIEWER")),
  closed = computed(() => recall.value?.status === "CLOSED");
let runGeneration = 0;
async function load() {
  error.value = "";
  try {
    const [c, r] = await Promise.all([
      api<CaseDetail>("/api/v1/recalls/" + id),
      api<RunRow[]>(`/api/v1/recalls/${id}/assessments`),
    ]);
    recall.value = c;
    runs.value = r;
    conditions.value = await Promise.all(
      c.conditions.map((v) =>
        api<Condition>(`/api/v1/recalls/${id}/conditions/${v.id}`),
      ),
    );
    if (!runId.value && r[0]) runId.value = r[0].id;
    if (runId.value) await loadRun();
  } catch (e) {
    error.value = errorText(e);
  }
}
async function loadRun() {
  const generation = ++runGeneration;
  assessment.value = undefined;
  try {
    if (runId.value) {
      const result = await api<Assessment>(
        `/api/v1/recalls/${id}/assessments/${runId.value}`,
      );
      if (generation === runGeneration) assessment.value = result;
    }
  } catch (e) {
    if (generation === runGeneration) error.value = errorText(e);
  }
}
async function approve(condition: Condition) {
  busy.value = true;
  error.value = "";
  try {
    await api(`/api/v1/recalls/${id}/conditions/${condition.id}/approval`, {});
    success.value = "조건을 승인했습니다. 이제 판정을 실행할 수 있습니다.";
    await load();
  } catch (e) {
    error.value = errorText(e);
  } finally {
    busy.value = false;
  }
}
async function assess(condition: Condition) {
  busy.value = true;
  error.value = "";
  try {
    const run = await api<Assessment>(`/api/v1/recalls/${id}/assessments`, {
      conditionId: condition.id,
    });
    runId.value = run.id;
    await load();
    tab.value = "impact";
    success.value = "판정 결과를 저장했습니다.";
  } catch (e) {
    error.value = errorText(e);
  } finally {
    busy.value = false;
  }
}
function ruleText(rule: Rule): string {
  if (rule.children)
    return (
      "(" +
      rule.children
        .map(ruleText)
        .join(rule.op === "AND" ? " 그리고 " : " 또는 ") +
      ")"
    );
  return `${rule.field === "LOT_NUMBER" ? "제조번호" : "소비기한"} ${rule.values?.join(rule.op === "BETWEEN" ? " ~ " : " / ")} ${rule.op === "BETWEEN" ? "범위" : rule.op === "IN" ? "중 하나" : "일치"}`;
}
async function saved() {
  creating.value = false;
  success.value = "조건 초안을 저장했습니다. 내용을 검토한 후 승인하세요.";
  await load();
}
onMounted(load);
</script>
<template>
  <RouterLink to="/" class="back-link">← 사건 목록</RouterLink>
  <p v-if="error" class="error" role="alert">
    {{ error }} <button @click="load">다시 조회</button>
  </p>
  <p v-if="success" class="success" role="status">{{ success }}</p>
  <template v-if="recall"
    ><div class="page-heading">
      <div>
        <p class="eyebrow">CASE {{ id.slice(0, 8).toUpperCase() }}</p>
        <h1>{{ recall.title }}</h1>
        <p class="muted">
          {{ label(recall.sourceType) }} · {{ dateText(recall.createdAt) }}
        </p>
      </div>
      <span class="badge" :class="recall.status">{{
        label(recall.status)
      }}</span>
    </div>
    <nav class="tabs" aria-label="사건 업무">
      <button
        v-for="[value, name] in [
          ['overview', '원문 · 조건 검토'],
          ['impact', '영향 조회'],
          ['tasks', '대응 작업'],
          ['closure', '종료 점검'],
        ]"
        :key="value"
        :class="{ active: tab === value }"
        :aria-current="tab === value ? 'page' : undefined"
        @click="tab = value!"
      >
        {{ name }}
      </button>
    </nav>
    <template v-if="tab === 'overview'"
      ><div class="two-col">
        <section class="panel">
          <h2>회수 요청 원문</h2>
          <div class="prewrap">{{ recall.sourceText }}</div>
        </section>
        <section class="panel">
          <div class="section-heading">
            <h2>조건 검토</h2>
            <button v-if="reviewer && !closed" @click="creating = !creating">
              {{ creating ? "작성 닫기" : "새 조건 작성" }}
            </button>
          </div>
          <p class="note">
            상품 연결을 검토하고 회수 조건을 승인하면 재고·출고 영향을 판정할 수
            있습니다.
          </p>
          <p v-if="!conditions.length" class="muted">
            등록된 조건이 없습니다. 원문을 확인하고 첫 조건을 작성하세요.
          </p>
          <div
            v-for="condition in conditions.slice().reverse()"
            :key="condition.id"
            class="task-card"
          >
            <div class="section-heading">
              <strong>조건 v{{ condition.version }}</strong
              ><span class="badge" :class="condition.status">{{
                label(condition.status)
              }}</span>
            </div>
            <p>{{ ruleText(condition.definition.rule) }}</p>
            <details>
              <summary>근거와 상품 연결 확인</summary>
              <blockquote class="prewrap">
                {{ condition.definition.sourceQuote }}
              </blockquote>
              <p class="small muted">
                데이터 {{ condition.definition.datasetId.slice(0, 8) }}
              </p>
              <p
                v-for="(review, productId) in condition.definition
                  .productReviews"
                :key="productId"
                class="small"
              >
                {{ productId }} · {{ label(review.status) }} ·
                {{ review.reason }}
              </p>
            </details>
            <template v-if="reviewer && !closed"
              ><label v-if="condition.status === 'DRAFT'" class="check-label"
                ><input v-model="confirm[condition.id]" type="checkbox" />원문,
                데이터 버전, 상품 연결과 조건을 확인했습니다.</label
              ><button
                v-if="condition.status === 'DRAFT'"
                class="primary"
                :disabled="busy || !confirm[condition.id]"
                @click="approve(condition)"
              >
                조건 승인</button
              ><button v-else :disabled="busy" @click="assess(condition)">
                이 조건으로 판정 실행
              </button></template
            >
            <p v-if="condition.approvedBy" class="footnote">
              승인 {{ condition.approvedBy }} ·
              {{ dateText(condition.approvedAt) }}
            </p>
          </div>
        </section>
      </div>
      <section v-if="creating && reviewer && !closed" class="panel">
        <ConditionForm :case-id="id" @saved="saved" /></section
    ></template>
    <template v-else
      ><section class="panel">
        <label
          >조회·작업 기준 판정<select v-model="runId" @change="loadRun">
            <option value="">판정 선택</option>
            <option v-for="run in runs" :key="run.id" :value="run.id">
              조건 v{{
                conditions.find((c) => c.id === run.conditionId)?.version
              }}
              · {{ dateText(run.createdAt) }} · {{ run.id.slice(0, 8) }}
            </option>
          </select></label
        >
        <p v-if="!runs.length" class="muted">
          승인된 조건으로 판정을 실행하면 결과가 표시됩니다.
        </p>
      </section>
      <template v-if="tab === 'impact' && assessment"
        ><section class="panel">
          <h2>
            재고 영향
            <small class="muted">수량 단위 EA · 판정 시점의 결과</small>
          </h2>
          <div class="metrics">
            <div class="metric">
              <span>회수 대상</span
              ><strong>{{
                assessment.inventoryTotals.target.toLocaleString()
              }}</strong>
            </div>
            <div class="metric">
              <span>비대상</span
              ><strong>{{
                assessment.inventoryTotals.nonTarget.toLocaleString()
              }}</strong>
            </div>
            <div class="metric warn">
              <span>확인 필요</span
              ><strong>{{
                assessment.inventoryTotals.needsReview.toLocaleString()
              }}</strong>
            </div>
          </div>
          <div class="table-scroll">
            <table>
              <thead>
                <tr>
                  <th>재고</th>
                  <th>입고</th>
                  <th>창고</th>
                  <th>수량</th>
                  <th>대상 판정</th>
                  <th>기존 격리 상태</th>
                </tr>
              </thead>
              <tbody>
                <tr
                  v-for="item in assessment.inventory"
                  :key="item.inventoryId"
                >
                  <td>{{ item.inventoryId }}</td>
                  <td>{{ item.receiptId }}</td>
                  <td>{{ item.warehouse }}</td>
                  <td>{{ item.quantity }} EA</td>
                  <td>
                    <span class="badge" :class="item.decision">{{
                      label(item.decision)
                    }}</span>
                  </td>
                  <td>{{ item.holdStatus === "HELD" ? "격리" : "미격리" }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>
        <section class="panel">
          <h2>출고 영향</h2>
          <p class="note">
            출고·입고 연결 기록이 없는 수량은 확인 필요로 남습니다. 상품이나
            날짜가 같아도 연결을 추정하지 않습니다.
          </p>
          <div class="metrics">
            <div class="metric">
              <span>회수 대상</span
              ><strong>{{
                assessment.shipmentTotals.target.toLocaleString()
              }}</strong>
            </div>
            <div class="metric">
              <span>비대상</span
              ><strong>{{
                assessment.shipmentTotals.nonTarget.toLocaleString()
              }}</strong>
            </div>
            <div class="metric warn">
              <span>확인 필요</span
              ><strong>{{
                assessment.shipmentTotals.needsReview.toLocaleString()
              }}</strong>
            </div>
          </div>
          <div class="table-scroll">
            <table>
              <thead>
                <tr>
                  <th>출고</th>
                  <th>주문</th>
                  <th>총수량</th>
                  <th>대상</th>
                  <th>비대상</th>
                  <th>확인 필요</th>
                  <th>연결 없음</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="item in assessment.shipments" :key="item.shipmentId">
                  <td>{{ item.shipmentId }}</td>
                  <td>{{ item.orderId }}</td>
                  <td>{{ item.quantity }}</td>
                  <td>{{ item.target }}</td>
                  <td>{{ item.nonTarget }}</td>
                  <td>{{ item.needsReview }}</td>
                  <td>{{ item.unlinked }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </section></template
      >
      <Tasks
        v-if="tab === 'tasks'"
        :case-id="id"
        :closed="closed"
        :assessment="assessment"
      />
      <Closure
        v-if="tab === 'closure'"
        :recall="recall"
        :assessment-id="assessment?.id"
        @changed="load"
      /> </template
  ></template>
</template>
