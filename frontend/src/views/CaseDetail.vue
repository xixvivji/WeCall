<script setup lang="ts">
import { confirmDrafts } from "../drafts";
import { ref, onMounted, computed, nextTick } from "vue";
import { useRoute, useRouter } from "vue-router";
import {
  api,
  downloadAssessment,
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
import ExtractionPanel from "../components/ExtractionPanel.vue";
import NextActions from "../components/NextActions.vue";
import CaseReport from "../components/CaseReport.vue";
import CaseHistory from "../components/CaseHistory.vue";
import ConditionForm from "../components/ConditionForm.vue";
import Tasks from "../components/Tasks.vue";
import Closure from "../components/Closure.vue";
import Comparison from "../components/Comparison.vue";
import Evidence from "../components/Evidence.vue";
const route = useRoute(),
  router = useRouter();
const tabs = [
  "overview",
  "ai",
  "impact",
  "comparison",
  "evidence",
  "tasks",
  "closure",
  "history",
  "report",
];
const requestedTab =
  typeof route.query.tab === "string" && tabs.includes(route.query.tab)
    ? route.query.tab
    : "overview";
const explicitRun = typeof route.query.assessment === "string";
const linkNotice = ref("");
async function changeRun(event: Event) {
  const select = event.target as HTMLSelectElement;
  await navigate(tab.value, select.value);
  select.value = runId.value;
}
function navigate(tabName: string, selectedRun = runId.value) {
  return router.push({
    path: route.path,
    query: { tab: tabName, assessment: selectedRun },
  });
}
async function copyViewLink() {
  linkNotice.value = "";
  const href = router.resolve({
    path: route.path,
    query: { ...route.query, tab: tab.value, assessment: runId.value },
  }).href;
  try {
    await navigator.clipboard.writeText(
      new URL(href, window.location.href).href,
    );
    linkNotice.value = "현재 탭과 기준 판정의 링크를 복사했습니다.";
  } catch {
    linkNotice.value =
      "링크를 복사하지 못했습니다. 브라우저 주소를 복사해 주세요.";
  }
}
const id = String(route.params.id),
  revision = ref(0),
  recall = ref<CaseDetail>(),
  conditions = ref<Condition[]>([]),
  runs = ref<RunRow[]>([]),
  assessment = ref<Assessment>(),
  runId = ref(explicitRun ? String(route.query.assessment) : ""),
  tab = ref(
    useRoute().query.task
      ? "tasks"
      : useRoute().query.evidence
        ? "evidence"
        : route.query.condition
          ? "overview"
          : requestedTab,
  ),
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
    if (!explicitRun && !runId.value && r[0]) runId.value = r[0].id;
    if (runId.value && !r.some((run) => run.id === runId.value)) {
      throw new Error(
        "이 사건에서 찾을 수 없는 기준 판정입니다. 판정을 다시 선택하세요.",
      );
    }
    if (runId.value) await loadRun();
    revision.value++;
  } catch (e) {
    error.value = errorText(e);
  }
}
async function loadRun() {
  const generation = ++runGeneration;
  // Stored assessments are immutable; a same-run refresh must not reset child forms.
  if (assessment.value?.id !== runId.value) assessment.value = undefined;
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
async function exportResult(type: "inventory" | "shipments") {
  if (!assessment.value || busy.value) return;
  const selectedRun = assessment.value.id;
  busy.value = true;
  error.value = "";
  try {
    await downloadAssessment(id, selectedRun, type);
  } catch (e) {
    error.value = errorText(e);
  } finally {
    busy.value = false;
  }
}
async function approve(condition: Condition) {
  if (busy.value) return;
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
  if (busy.value) return;
  busy.value = true;
  error.value = "";
  try {
    const run = await api<Assessment>(`/api/v1/recalls/${id}/assessments`, {
      conditionId: condition.id,
    });
    await load();
    await navigate("impact", run.id);
    success.value = "판정 결과를 저장했습니다.";
  } catch (e) {
    error.value = errorText(e);
  } finally {
    busy.value = false;
  }
}
async function showEvidenceAssessment(id: string) {
  await navigate("impact", id);
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
onMounted(async () => {
  await load();
  await nextTick();
  if (typeof route.query.condition === "string")
    document
      .getElementById("condition-" + route.query.condition)
      ?.scrollIntoView({ block: "center" });
});
</script>
<template>
  <RouterLink to="/" class="back-link">← 사건 목록</RouterLink>
  <p v-if="error" class="error" role="alert">
    {{ error }} <button @click="load">다시 조회</button>
  </p>
  <p v-if="!recall && !error" class="loading" role="status">
    사건을 불러오는 중입니다…
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
    <div class="inline">
      <button @click="copyViewLink">현재 화면 링크 복사</button>
      <p v-if="linkNotice" role="status" class="small muted">
        {{ linkNotice }}
      </p>
    </div>
    <nav class="tabs" aria-label="사건 업무">
      <button
        v-for="[value, name] in [
          ['overview', '원문 · 조건 검토'],
          ['ai', 'AI 조건 추출'],
          ['impact', '영향 조회'],
          ['comparison', '판정 비교'],
          ['evidence', '입고 증거'],
          ['tasks', '대응 작업'],
          ['closure', '종료 점검'],
          ['history', '업무 이력'],
          ['report', '대응 보고서'],
        ]"
        :key="value"
        :class="{ active: tab === value }"
        :aria-current="tab === value ? 'page' : undefined"
        @click="navigate(value!)"
      >
        {{ name }}
      </button>
    </nav>
    <ExtractionPanel
      v-if="tab === 'ai'"
      :case-id="id"
      :closed="closed"
      @updated="load" />
    <template v-if="tab === 'overview'"
      ><NextActions
        :recall="recall"
        :assessment-id="assessment?.id"
        :revision="revision"
        @create-condition="creating = true" />
      <div class="two-col">
        <section class="panel">
          <h2>회수 요청 원문</h2>
          <div class="prewrap">{{ recall.sourceText }}</div>
        </section>
        <section class="panel">
          <div class="section-heading">
            <h2>조건 검토</h2>
            <button
              v-if="reviewer && !closed"
              @click="
                !creating || confirmDrafts()
                  ? (creating = !creating)
                  : undefined
              "
            >
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
            :id="'condition-' + condition.id"
            :class="{
              'review-highlight': $route.query.condition === condition.id,
            }"
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
      ><section v-if="!['comparison', 'history'].includes(tab)" class="panel">
        <label
          >조회·작업 기준 판정<select :value="runId" @change="changeRun">
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
          <div class="inline">
            <button :disabled="busy" @click="exportResult('inventory')">
              재고 CSV 다운로드</button
            ><button :disabled="busy" @click="exportResult('shipments')">
              출고 CSV 다운로드
            </button>
          </div>
          <p class="note">
            현재 선택한 판정의 저장 결과입니다. 출고의 연결 미확인 수량은 확인
            필요 수량에 포함되며, 현재 작업 상태를 뜻하지 않습니다.
          </p>
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
      <CaseReport
        v-if="tab === 'report'"
        :case-id="id"
        :assessment-id="runId || undefined"
      />
      <CaseHistory
        v-if="tab === 'history'"
        :case-id="id"
        @open="tab = $event"
      />
      <Comparison v-if="tab === 'comparison'" :case-id="id" :runs="runs" />
      <Evidence
        v-if="tab === 'evidence'"
        :initial-evidence="String($route.query.evidence || '')"
        :case-id="id"
        :closed="closed"
        :assessment="assessment"
        @updated="load"
        @show-assessment="showEvidenceAssessment"
      />
      <Tasks
        v-if="tab === 'tasks'"
        :case-id="id"
        :initial-task="String($route.query.task || '')"
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

<style scoped>
.review-highlight {
  outline: 2px solid #126f58;
  outline-offset: 4px;
}
</style>
