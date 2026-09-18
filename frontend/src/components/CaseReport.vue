<script setup lang="ts">
import { ref, computed, watch } from "vue";
import {
  api,
  errorText,
  dateText,
  label,
  type CaseDetail,
  type Condition,
  type Assessment,
  type Rule,
} from "../api";
const props = defineProps<{ caseId: string; assessmentId?: string }>();
interface Task {
  id: string;
  title: string;
  status: string;
  assignee: string | null;
  assessmentId: string | null;
  targetType: string;
  targetId: string | null;
  taskType: string;
  version: number;
}
interface Review {
  id: string;
  status: string;
  reviewedBy: string | null;
  reviewNote: string | null;
  reviewedAt: string | null;
}
interface Report {
  case: CaseDetail;
  generatedAt: string;
  assessment: Assessment | null;
  condition: Condition | null;
  assessmentSourceVersion?: number | null;
  assessmentCreatedAt: string | null;
  datasetAsOf: string | null;
  tasks: Task[];
  proofs: (Review & {
    taskId: string;
    reviewRound: number;
    currentRound: number;
  })[];
  evidence: (Review & { receiptId: string; assessmentId: string })[];
  blockers: { code: string; message: string; count: number }[];
  warnings: { code: string; message: string; count: number }[];
  lifecycle: {
    id: string;
    version: number;
    type: string;
    assessmentId: string | null;
    reviewer: string;
    note: string;
    at: string;
  }[];
}
const report = ref<Report>(),
  loading = ref(false),
  error = ref("");
let generation = 0;
async function load() {
  const ticket = ++generation;
  report.value = undefined;
  loading.value = true;
  error.value = "";
  try {
    const data = await api<Report>(
      `/api/v1/recalls/${props.caseId}/report` +
        (props.assessmentId ? `?assessmentId=${props.assessmentId}` : ""),
    );
    if (ticket === generation) report.value = data;
  } catch (e) {
    if (ticket === generation) error.value = errorText(e);
  } finally {
    if (ticket === generation) loading.value = false;
  }
}
const assignees = computed(() => {
  const rows = new Map<string, Record<string, number>>();
  for (const task of report.value?.tasks || []) {
    const name = task.assignee || "미배정";
    const row = rows.get(name) || {
      OPEN: 0,
      IN_PROGRESS: 0,
      COMPLETED: 0,
      CANCELLED: 0,
    };
    row[task.status] = (row[task.status] || 0) + 1;
    rows.set(name, row);
  }
  return [...rows.entries()];
});
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
function scope(id: string | null) {
  return !id
    ? "사건 전체"
    : id === report.value?.assessment?.id
      ? "선택 판정"
      : "다른 판정";
}
function printReport() {
  if (report.value && !loading.value) window.print();
}
watch(() => [props.caseId, props.assessmentId], load, { immediate: true });
</script>
<template>
  <section class="case-report panel" aria-label="사건 대응 보고서">
    <div class="section-heading report-controls">
      <h2>대응 보고서</h2>
      <div class="inline">
        <button :disabled="loading" @click="load">보고서 새로 조회</button
        ><button
          class="primary"
          :disabled="loading || !report"
          @click="printReport"
        >
          인쇄 · PDF 저장
        </button>
      </div>
    </div>
    <p v-if="loading" role="status">보고서를 불러오는 중입니다…</p>
    <p v-if="error" class="error" role="alert">
      {{ error }} <button @click="load">다시 시도</button>
    </p>
    <article v-if="report">
      <p class="eyebrow">WECALL · 사건별 대응 결과</p>
      <h1>{{ report.case.title }}</h1>
      <p>
        사건 상태: {{ label(report.case.status) }} ·
        {{ label(report.case.sourceType) }} · 상태 버전
        {{ report.case.lifecycleVersion }}
      </p>
      <p class="small">
        사건 ID: {{ report.case.id }}<br />사건 등록:
        {{ dateText(report.case.createdAt) }}<br />보고서 조회 기준:
        {{ dateText(report.generatedAt) }} ({{ report.generatedAt }})
      </p>
      <p class="note">
        판정 수량은 저장된 판정 시점의 결과이며, 업무 현황은 위 조회 기준 시점의
        기록입니다. 작업 완료 건수는 실제 회수·반품 완료 수량을 뜻하지 않습니다.
        다시 조회하면 업무 현황이 변경될 수 있습니다.
      </p>
      <h2>1. 사건 개요</h2>
      <p class="small">
        현재 원문 버전: {{ report.case.sourceVersion ?? "기록 없음" }}. 아래
        원문은 보고서 조회 시점 기준이며, 과거 판정 당시 원문과 다를 수
        있습니다.
      </p>
      <p class="prewrap">{{ report.case.sourceText }}</p>
      <h2>2. 기준 판정과 승인 조건</h2>
      <template v-if="report.assessment && report.condition">
        <p>
          판정 생성 당시 원문 버전:
          {{ report.assessmentSourceVersion ?? "기록 없음" }}. 현재 원문과의
          조건 재검토:
          {{
            report.condition.sourceReview?.required
              ? "필요"
              : report.condition.sourceReview
                ? "확인됨"
                : "기록 없음"
          }}.
        </p>
        <p
          v-for="review in report.condition.sourceReview?.reviews || []"
          :key="review.sourceVersion"
        >
          원문 v{{ review.sourceVersion }} 영향 없음 확인 ·
          {{ review.reviewer }} · {{ review.note }}
        </p>
        <p class="small">
          판정 ID: {{ report.assessment.id }}<br />판정 생성:
          {{ dateText(report.assessmentCreatedAt) }}<br />데이터 ID:
          {{ report.assessment.datasetId }}<br />데이터 기준:
          {{ dateText(report.datasetAsOf) }}<br />조건 ID:
          {{ report.condition.id }} · v{{ report.condition.version }}<br />승인:
          {{ report.condition.approvedBy || "기록 없음" }} ·
          {{ dateText(report.condition.approvedAt) }}
        </p>
        <p>{{ ruleText(report.condition.definition.rule) }}</p>
        <blockquote class="prewrap">
          {{ report.condition.definition.sourceQuote }}
        </blockquote>
        <h3>상품 연결 검토</h3>
        <p
          v-for="(review, id) in report.condition.definition.productReviews"
          :key="id"
        >
          {{ id }} · {{ label(review.status) }} · {{ review.reason }}
        </p>
        <div class="table-scroll">
          <table aria-label="보고서 판정 수량">
            <thead>
              <tr>
                <th>구분 (EA)</th>
                <th>대상</th>
                <th>비대상</th>
                <th>확인 필요</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="[name, totals] in [
                  ['재고', report.assessment.inventoryTotals],
                  ['출고', report.assessment.shipmentTotals],
                ] as const"
                :key="name"
              >
                <td>{{ name }}</td>
                <td>{{ totals.target.toLocaleString() }}</td>
                <td>{{ totals.nonTarget.toLocaleString() }}</td>
                <td>{{ totals.needsReview.toLocaleString() }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <p>
          출고 연결 미확인:
          {{
            report.assessment.shipments.reduce((sum, s) => sum + s.unlinked, 0)
          }}
          EA (출고 확인 필요 수량에 포함)
        </p>
        <p class="small muted">
          출고·입고 연결 기록이 없는 수량은 추정하여 확정하지 않습니다.
        </p>
      </template>
      <p v-else class="note">
        기준 판정이 선택되지 않았습니다. 수량과 승인 조건을 포함하려면 위에서
        판정을 선택하세요.
      </p>
      <h2>3. 담당자별 업무 현황 (건)</h2>
      <p>
        사건의 모든 작업을 포함합니다. 다른 판정의 작업과 취소 작업도 함께
        확인하세요.
      </p>
      <div v-if="report.tasks.length" class="table-scroll">
        <table aria-label="담당자별 작업 건수">
          <thead>
            <tr>
              <th>담당자</th>
              <th>진행 중</th>
              <th>작업 중</th>
              <th>완료</th>
              <th>취소</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="[name, counts] in assignees" :key="name">
              <td>{{ name }}</td>
              <td>{{ counts.OPEN }}</td>
              <td>{{ counts.IN_PROGRESS }}</td>
              <td>{{ counts.COMPLETED }}</td>
              <td>{{ counts.CANCELLED }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <p v-else>등록된 대응 작업이 없습니다.</p>
      <div v-for="task in report.tasks" :key="task.id" class="report-entry">
        <h3>{{ task.title }} · {{ label(task.status) }}</h3>
        <p>
          {{ task.assignee || "미배정" }} · {{ label(task.taskType) }} ·
          {{ label(task.targetType) }} {{ task.targetId || "" }} ·
          {{ scope(task.assessmentId) }}
        </p>
        <p class="small">
          작업 ID: {{ task.id }} · v{{ task.version }}<br />연결 판정:
          {{ task.assessmentId || "없음 (사건 단위)" }}
        </p>
        <p
          v-if="!report.proofs.some((p) => p.taskId === task.id)"
          class="muted"
        >
          제출된 처리 증빙이 없습니다.
        </p>
        <div
          v-for="proof in report.proofs.filter((p) => p.taskId === task.id)"
          :key="proof.id"
          class="proof-review"
        >
          <p>
            증빙 {{ proof.id }} · {{ proof.reviewRound }}회차
            {{ proof.reviewRound === proof.currentRound ? "(현재)" : "(이전)" }}
            · {{ label(proof.status) }}
          </p>
          <p class="prewrap">
            검토: {{ proof.reviewedBy || "미검토" }} ·
            {{ dateText(proof.reviewedAt) }}<br />{{
              proof.reviewNote || "검토 의견 없음"
            }}
          </p>
        </div>
      </div>
      <h2>4. 입고 증거 검토</h2>
      <p v-if="!report.evidence.length">등록된 입고 증거가 없습니다.</p>
      <div v-for="item in report.evidence" :key="item.id" class="report-entry">
        <p>
          {{ item.receiptId }} · {{ label(item.status) }} ·
          {{ scope(item.assessmentId) }}
        </p>
        <p class="small">
          증거 ID: {{ item.id }}<br />기준 판정: {{ item.assessmentId }}
        </p>
        <p class="prewrap">
          {{ item.reviewedBy || "미검토" }} · {{ dateText(item.reviewedAt)
          }}<br />{{ item.reviewNote || "검토 의견 없음" }}
        </p>
      </div>
      <h2>5. 종료 점검·미해결 항목</h2>
      <p>
        선택 판정과 현재 업무 기록에 대한 시스템 점검입니다. 실제 대응 범위는
        별도 확인이 필요합니다.
      </p>
      <p v-if="report.case.status === 'CLOSED'">
        현재 종료된 사건입니다. 아래 점검은 종료 당시의 승인 기록이 아니며, 종료
        기준은 다음 이력에서 확인하세요.
      </p>
      <p v-if="!report.blockers.length">시스템 종료 차단 항목이 없습니다.</p>
      <p v-for="issue in report.blockers" :key="issue.code">
        {{ issue.message }} · {{ issue.count }}
        {{ ["UNRESOLVED_SHIPMENTS"].includes(issue.code) ? "EA" : "건" }}
      </p>
      <p v-for="issue in report.warnings" :key="issue.code">
        검토 필요: {{ issue.message }} · {{ issue.count }}건
      </p>
      <h2>6. 사건 종료·재개 기록</h2>
      <p v-if="!report.lifecycle.length">종료·재개 기록이 없습니다.</p>
      <div
        v-for="event in report.lifecycle"
        :key="event.id"
        class="report-entry"
      >
        <h3>
          v{{ event.version }} ·
          {{ event.type === "CLOSED" ? "사건 종료" : "사건 재개" }}
        </h3>
        <p>{{ event.reviewer }} · {{ dateText(event.at) }}</p>
        <p class="prewrap">{{ event.note }}</p>
        <p class="small">기준 판정: {{ event.assessmentId || "없음" }}</p>
      </div>
      <p class="footnote">
        이 보고서는 조회 시점의 업무 요약입니다. 증빙 원문·첨부는 사건 상세에서
        확인하세요. 인쇄 창에서 PDF로 저장할 수 있습니다.
      </p>
    </article>
  </section>
</template>
<style scoped>
.case-report {
  overflow-wrap: anywhere;
}
.report-entry {
  border-top: 1px solid #dce3df;
  padding: 16px 0;
}
.proof-review {
  border-left: 3px solid #dce3df;
  padding-left: 12px;
}
article h2 {
  margin-top: 32px;
}
article h3 {
  font-size: 16px;
}
</style>
<style>
@media print {
  @page {
    size: A4;
    margin: 16mm;
  }
  body:has(.case-report) {
    background: white;
    color: black;
  }
  body:has(.case-report) .sidebar,
  body:has(.case-report) .topbar,
  body:has(.case-report) .content > :not(.case-report),
  .case-report .report-controls {
    display: none !important;
  }
  body:has(.case-report) .workspace,
  body:has(.case-report) .main-shell,
  body:has(.case-report) .content {
    display: block !important;
    margin: 0 !important;
    padding: 0 !important;
    min-height: 0 !important;
    width: auto !important;
  }
  .case-report {
    border: 0 !important;
    box-shadow: none !important;
    padding: 0 !important;
    margin: 0 !important;
  }
  .case-report .table-scroll {
    overflow: visible;
  }
  .case-report table {
    min-width: 0;
    width: 100%;
    table-layout: fixed;
    font-size: 10pt;
  }
  .case-report th,
  .case-report td {
    white-space: normal;
    overflow-wrap: anywhere;
  }
  .case-report h2,
  .case-report h3 {
    break-after: avoid;
  }
  .case-report tr {
    break-inside: avoid;
  }
  .case-report p {
    orphans: 3;
    widows: 3;
  }
}
</style>
