<script setup lang="ts">
import { computed, nextTick, ref } from "vue";
import { ArrowRight, Check, RotateCcw, ShieldCheck } from "@lucide/vue";
import BrandFlow from "../components/BrandFlow.vue";
import sample from "../demo/sample.json";
// Each mount owns its state. No API, account, browser storage or shared mutable fixture.
const step = ref(0),
  furthest = ref(0),
  approved = ref(false),
  supplemented = ref(false);
const checked = ref(false),
  evidenceChecked = ref(false),
  proofChecked = ref(false);
const taskStatus = ref<"OPEN" | "IN_PROGRESS" | "PROOF" | "COMPLETED">("OPEN");
const resetRequested = ref(false),
  finished = ref(false);
const heading = ref<HTMLElement>();
function startExperience() {
  heading.value?.scrollIntoView({ block: "center", behavior: "auto" });
  heading.value?.focus({ preventScroll: true });
}
const steps = [
  "원문 확인",
  "AI 초안",
  "사람 검토",
  "영향 확인",
  "입고 보완",
  "대응·마무리",
];
const tips = [
  "어떤 상품의 어떤 제조분을 회수하는지 원문에서 확인하세요.",
  "AI가 추출한 조건과 인용 근거를 원문과 비교하세요.",
  "AI는 상품을 확정하거나 조건을 승인하지 않습니다. 규격·제조사를 확인하세요.",
  "대상·비대상·확인 필요를 구분합니다. 누락된 정보는 비대상으로 단정하지 않습니다.",
  "추가 증거를 검토하면 새 판정이 만들어집니다. 이전 판정은 그대로 남습니다.",
  "작업 증빙을 확인하되, 작업 완료와 사건 종료는 별도로 판단합니다.",
];
const totals = computed(() =>
  supplemented.value ? sample.totals.after : sample.totals.before,
);
const labels: Record<string, string> = {
  TARGET: "회수 대상",
  NON_TARGET: "비대상",
  NEEDS_REVIEW: "확인 필요",
};
const taskLabels = {
  OPEN: "대기",
  IN_PROGRESS: "진행 중",
  PROOF: "증빙 검토 대기",
  COMPLETED: "완료",
};
async function go(value: number) {
  if (value < 0 || value > furthest.value || value >= steps.length) return;
  step.value = value;
  await nextTick();
  heading.value?.focus();
}
function advance() {
  if (step.value === 2 && !approved.value) return;
  if (step.value === 4 && !supplemented.value) return;
  if (step.value >= steps.length - 1) return;
  furthest.value = Math.max(furthest.value, step.value + 1);
  go(step.value + 1);
}
function approve() {
  if (!checked.value) return;
  approved.value = true;
  advance();
}
function supplement() {
  if (!evidenceChecked.value || !approved.value) return;
  supplemented.value = true;
}
function transition(action: "start" | "proof" | "complete") {
  if (action === "start" && taskStatus.value === "OPEN")
    taskStatus.value = "IN_PROGRESS";
  if (action === "proof" && taskStatus.value === "IN_PROGRESS")
    taskStatus.value = "PROOF";
  if (
    action === "complete" &&
    taskStatus.value === "PROOF" &&
    proofChecked.value
  )
    taskStatus.value = "COMPLETED";
}
function reset() {
  step.value = 0;
  furthest.value = 0;
  approved.value = false;
  supplemented.value = false;
  checked.value = false;
  evidenceChecked.value = false;
  proofChecked.value = false;
  taskStatus.value = "OPEN";
  resetRequested.value = false;
  finished.value = false;
  go(0);
}
</script>
<template>
  <div class="demo-page">
    <header class="demo-header">
      <RouterLink to="/" class="wordmark" aria-label="WeCall 업무 화면"
        >wecall<span class="brand-dot">.</span></RouterLink
      >
      <span class="demo-label"><ShieldCheck :size="16" /> 합성 샘플 체험</span>
      <nav class="demo-topnav" aria-label="서비스 메뉴">
        <button @click="startExperience">
          서비스 체험 <ArrowRight :size="16" />
        </button>
        <RouterLink to="/" class="demo-login">업무 화면으로 →</RouterLink>
      </nav>
    </header>
    <section class="brand-hero demo-hero" aria-label="WeCall 소개">
      <BrandFlow />
      <div class="hero-copy">
        <p class="eyebrow">CONNECTED RECALL OPERATIONS</p>
        <h1>확실한 근거로,<br />끝까지 연결하다.</h1>
        <p>
          회수 요청부터 영향 확인과 대응까지.<br class="mobile-break" />
          WeCall로 하나의 흐름으로.
        </p>
        <button class="demo-hero-cta" @click="startExperience">
          샘플 체험 시작 <ArrowRight :size="20" />
        </button>
      </div>
      <div class="demo-hero-index" aria-label="서비스 핵심 흐름">
        <span><small>01</small> 원문에서 근거로</span>
        <span><small>02</small> 근거에서 판단으로</span>
        <span><small>03</small> 판단에서 대응으로</span>
      </div>
    </section>
    <main class="demo-main">
      <div class="demo-section-title">
        <p class="eyebrow">EXPERIENCE WECALL</p>
        <h2>회수 업무의 흐름을<br />직접 확인해 보세요.</h2>
        <p>가상 크래커 회수 사건으로 알아보는 6단계 업무 체험</p>
      </div>
      <div class="demo-boundary" role="note">
        <ShieldCheck :size="20" />
        <p>
          <strong>이 체험은 나만의 탭에서 진행됩니다.</strong> 변경은 저장되지
          않고 새로고침하면 초기화됩니다. 실제 사건이나 다른 방문자의 체험에는
          영향을 주지 않습니다. AI 초안과 수량은 검증된 합성 예시이며, 여기서
          실제 모델을 실행하지 않습니다.
        </p>
      </div>
      <nav class="demo-steps" aria-label="샘플 체험 단계">
        <button
          v-for="(title, index) in steps"
          :key="title"
          :disabled="index > furthest"
          :aria-current="step === index ? 'step' : undefined"
          @click="go(index)"
        >
          <span class="step-number"
            ><Check v-if="index < furthest" :size="16" /><template v-else>{{
              index + 1
            }}</template></span
          >
          {{ title }}
        </button>
      </nav>
      <section class="panel demo-stage" aria-label="현재 체험 단계">
        <div class="demo-stage-heading">
          <div>
            <p class="eyebrow">
              STEP {{ String(step + 1).padStart(2, "0") }} / 06
            </p>
            <h2 ref="heading" tabindex="-1">{{ steps[step] }}</h2>
            <p class="muted">{{ tips[step] }}</p>
          </div>
          <button @click="resetRequested = !resetRequested">
            <RotateCcw :size="16" />처음부터 체험
          </button>
        </div>
        <div
          v-if="resetRequested"
          class="note"
          role="group"
          aria-label="체험 초기화 확인"
        >
          <p>
            현재 탭의 체험 진행을 초기화할까요? 실제 업무 데이터는 변경되지
            않습니다.
          </p>
          <div class="inline">
            <button @click="reset">체험 초기화</button
            ><button @click="resetRequested = false">계속 체험</button>
          </div>
        </div>
        <template v-if="step === 0">
          <div class="demo-columns">
            <article class="demo-document">
              <p class="eyebrow">SYNTHETIC NOTICE · N1</p>
              <h3>가상 공급사 회수 요청</h3>
              <pre>{{ sample.notice }}</pre>
            </article>
            <aside class="demo-explanation">
              <h3>이 사건에서 확인할 것</h3>
              <ol>
                <li>가상식품A의 <strong>100g</strong> 제품인가?</li>
                <li>제조번호가 <strong>A01 또는 A02</strong>인가?</li>
                <li>소비기한이 <strong>2026-10-31</strong>인가?</li>
              </ol>
              <p class="note">
                제조번호만 맞아도 대상이 되는 것은 아닙니다. 상품 연결과 두
                조건을 함께 확인해야 합니다.
              </p>
              <p class="small muted">
                CSV 5종과 원문이 준비된 예시입니다. 회사 문서를 입력하거나
                업로드할 필요가 없습니다.
              </p>
            </aside>
          </div>
        </template>
        <template v-else-if="step === 1">
          <p class="note">
            <strong>저장된 AI 초안 예시</strong> · 새 분석을 실행한 결과가
            아닙니다. 실제 서비스에서는 로컬 모델로 초안을 만들고 사람이
            검토합니다.
          </p>
          <div class="demo-columns">
            <article class="demo-document">
              <h3>원문 근거</h3>
              <blockquote>{{ sample.condition.sourceQuote }}</blockquote>
            </article>
            <article class="demo-explanation">
              <h3>구조화된 조건</h3>
              <div class="demo-rule">
                <span>제조번호</span><strong>A01 또는 A02</strong>
              </div>
              <p class="demo-and">그리고 (AND)</p>
              <div class="demo-rule">
                <span>소비기한</span><strong>2026-10-31</strong>
              </div>
              <p class="small muted">
                초안만으로는 판정할 수 없습니다. 다음 단계에서 상품과 근거를
                확인하세요.
              </p>
            </article>
          </div>
        </template>
        <template v-else-if="step === 2">
          <p class="note">
            이름이 비슷해도 규격이나 제조사가 다르면 같은 상품으로 연결하지
            않습니다.
          </p>
          <div
            class="table-scroll"
            tabindex="0"
            role="region"
            aria-label="샘플 상품 검토 표"
          >
            <table>
              <thead>
                <tr>
                  <th>상품</th>
                  <th>제조사</th>
                  <th>규격</th>
                  <th>검토 예시</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="p in sample.products" :key="p.product_id">
                  <td>{{ p.name }}</td>
                  <td>{{ p.manufacturer }}</td>
                  <td>{{ p.pack_size }}</td>
                  <td>
                    {{
                      p.product_id === "P1"
                        ? "연결 — 제조사·규격 일치"
                        : p.product_id === "P2"
                          ? "제외 — 규격 다름"
                          : "제외 — 제조사 다름"
                    }}
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
          <label class="check-label"
            ><input
              v-model="checked"
              type="checkbox"
              :disabled="approved"
            />원문 조건과 상품의 제조사·규격을 확인했습니다.</label
          >
          <p v-if="approved" class="success" role="status">
            이 체험에서 조건 검토를 완료했습니다.
          </p>
          <button v-else class="primary" :disabled="!checked" @click="approve">
            샘플 조건 승인하고 영향 보기 <ArrowRight :size="16" />
          </button>
        </template>
        <template v-else-if="step === 3">
          <p class="note">
            {{
              supplemented
                ? "입고 증거 보완 후 판정 v2 예시"
                : "승인 조건의 판정 v1 예시"
            }}
            · 표시 수량은 합성 정답표입니다. 재고와 출고는 별도 집계합니다.
          </p>
          <h3>재고 영향 · EA</h3>
          <div class="metrics">
            <div
              v-for="(amount, decision) in totals.inventory"
              :key="decision"
              class="metric"
              :class="{ warn: decision === 'NEEDS_REVIEW' }"
            >
              <span>{{ labels[decision] }}</span
              ><strong>{{ amount }}</strong>
            </div>
          </div>
          <h3>출고 영향 · EA</h3>
          <div class="metrics">
            <div
              v-for="(amount, decision) in totals.shipments"
              :key="decision"
              class="metric"
              :class="{ warn: decision === 'NEEDS_REVIEW' }"
            >
              <span>{{ labels[decision] }}</span
              ><strong>{{ amount }}</strong>
            </div>
          </div>
          <div class="demo-columns">
            <div class="demo-explanation">
              <h3>제조번호가 없는 입고 R4</h3>
              <p>
                {{
                  supplemented
                    ? "증거 승인으로 A02가 확인됐습니다."
                    : "재고 30개와 연결 출고 10개의 대상 여부가 불명확합니다. 입고 증거가 필요합니다."
                }}
              </p>
            </div>
            <div class="demo-explanation">
              <h3>입고와 연결되지 않은 출고 S2</h3>
              <p>
                10개는 원 입고가 확인되지 않습니다. 상품명·날짜만으로 연결을
                추정하지 않습니다.
              </p>
            </div>
          </div>
        </template>
        <template v-else-if="step === 4">
          <div class="demo-columns">
            <article class="demo-document">
              <h3>합성 입고 증거</h3>
              <pre>{{ sample.evidence }}</pre>
            </article>
            <aside class="demo-explanation">
              <h3>증거가 바꾸는 범위</h3>
              <p>
                입고 R4의 제조번호를 A02로 보완합니다. R4에 연결된 재고 30개와
                출고 10개가 대상에 포함됩니다.
              </p>
              <p class="note">
                S2의 미연결 출고 10개는 이 증거로 해결되지 않습니다.
              </p>
              <label class="check-label"
                ><input
                  v-model="evidenceChecked"
                  type="checkbox"
                  :disabled="supplemented"
                />입고·상품·전체 수량·날짜·단일 제조분의 근거를
                확인했습니다.</label
              ><button
                class="primary"
                :disabled="!evidenceChecked || supplemented"
                @click="supplement"
              >
                샘플 증거 승인
              </button>
            </aside>
          </div>
          <div v-if="supplemented" class="demo-comparison" role="status">
            <h3>새 판정 v2 예시 — v1은 보존</h3>
            <p>
              재고 대상 <strong>110 → 140 EA</strong> · 확인 필요
              <strong>30 → 0 EA</strong>
            </p>
            <p>
              출고 대상 <strong>60 → 70 EA</strong> · 확인 필요
              <strong>20 → 10 EA</strong>
            </p>
            <p>출고 확인 필요 10 EA는 그대로 남습니다.</p>
          </div>
        </template>
        <template v-else>
          <p class="note">
            검토자·실행 담당자의 작업 순서를 한 화면에서 체험합니다. 실제
            서비스에서는 계정별 권한이 적용됩니다.
          </p>
          <article class="task-card">
            <div class="section-heading">
              <h3>재고 I1 60개 격리 · 샘플 작업 1건</h3>
              <span class="badge" role="status">{{
                taskLabels[taskStatus]
              }}</span>
            </div>
            <p>조건에 맞는 재고 I1을 격리하고 실행 근거를 남기는 예시입니다.</p>
            <button
              v-if="taskStatus === 'OPEN'"
              class="primary"
              @click="transition('start')"
            >
              샘플 작업 시작
            </button>
            <template v-else-if="taskStatus === 'IN_PROGRESS'"
              ><p>
                샘플 증빙: I1 60개를 별도 구역에 격리했다고 기록했습니다. 실제
                실적은 아닙니다.
              </p>
              <button class="primary" @click="transition('proof')">
                샘플 증빙 제출
              </button></template
            >
            <template v-else-if="taskStatus === 'PROOF'"
              ><label class="check-label"
                ><input v-model="proofChecked" type="checkbox" />작업 대상과
                증빙이 일치하는지 확인했습니다.</label
              ><button
                class="primary"
                :disabled="!proofChecked"
                @click="transition('complete')"
              >
                샘플 증빙 검토 후 작업 완료
              </button></template
            >
            <p v-else class="success">
              샘플 작업 1건을 완료했습니다. 전체 회수 완료를 의미하지 않습니다.
            </p>
          </article>
          <div class="demo-explanation">
            <h3>사건 종료는 아직 할 수 없어요</h3>
            <p>
              미연결 출고 10 EA가 남아 있고, 다른 대상 재고·출고의 대응 범위도
              검토해야 합니다. 작업 1건 완료만으로 사건을 종료하지 않습니다.
            </p>
          </div>
          <button
            v-if="!finished"
            class="primary"
            :disabled="taskStatus !== 'COMPLETED'"
            @click="finished = true"
          >
            체험 요약 보기
          </button>
          <div v-else class="demo-comparison" role="status">
            <h3>체험 완료 · 실제 사건 종료 아님</h3>
            <p>
              AI는 조건 초안을 제안하고, 사람이 상품과 근거를 승인합니다. 판정은
              기록으로 계산하며 불확실한 수량은 남겨 두고 대응 이력을
              관리합니다.
            </p>
            <p>
              실제 모델 분석과 직접 입력·업로드는 권한이 있는 업무 계정에서
              사용할 수 있습니다.
            </p>
            <RouterLink to="/" class="sample-entry-link"
              >업무 화면으로 이동 →</RouterLink
            >
          </div>
        </template>
        <footer class="demo-stage-footer">
          <button :disabled="step === 0" @click="go(step - 1)">이전 단계</button
          ><span>{{ step + 1 }} / {{ steps.length }}</span
          ><button
            v-if="step < 5"
            class="primary"
            :disabled="
              (step === 2 && !approved) || (step === 4 && !supplemented)
            "
            @click="advance"
          >
            다음: {{ steps[step + 1] }} <ArrowRight :size="16" /></button
          ><span v-else class="muted small">{{
            finished ? "체험 완료" : "샘플 작업을 완료해 보세요"
          }}</span>
        </footer>
      </section>
      <p class="footnote">
        모든 업체·상품·문서·수량은 가상입니다. 샘플 체험은 실제 AI 실행이나 회사
        자료 검증을 대신하지 않습니다.
      </p>
    </main>
  </div>
</template>
<style scoped>
.demo-page {
  min-height: 100vh;
  background: white;
  position: relative;
}
.demo-header {
  position: absolute;
  inset: 0 0 auto;
  z-index: 2;
  padding: 26px 5%;
  background: transparent;
  color: white;
  display: flex;
  align-items: center;
  gap: 28px;
}
.demo-label {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #b7c8f3;
  font-size: 0.8rem;
}
.demo-login {
  border-radius: 30px;
  padding: 9px 20px;
  background: #ffffff24;
  font-size: 0.85rem;
}
.demo-main {
  max-width: 1180px;
  margin: auto;
  padding: 32px 24px 60px;
}
.demo-topnav {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 36px;
}
.demo-topnav button {
  border: 0;
  background: transparent;
  color: white;
  padding: 8px 0;
}
.demo-hero {
  min-height: min(850px, 100svh);
  padding: 200px 5% 140px;
  margin: 0;
  border-radius: 0;
  display: flex;
  align-items: center;
  background: #070914;
}
.demo-hero :deep(.brand-flow) {
  left: 0;
  opacity: 1;
}
.demo-hero .hero-copy {
  max-width: 850px;
}
.demo-hero .hero-copy > p:not(.eyebrow) {
  font-size: 1.15rem;
  color: #e0e4f2;
  margin-top: 28px;
}
.demo-hero-cta {
  color: white;
  background: transparent;
  border: 0;
  border-bottom: 1px solid #ffffff66;
  border-radius: 0;
  padding: 16px 0;
  margin-top: 52px;
  gap: 56px;
}
.demo-hero-cta:hover {
  color: #b7dcff;
  background: transparent;
}
.demo-hero-index {
  position: absolute;
  bottom: 42px;
  left: 5%;
  right: 5%;
  display: flex;
  justify-content: center;
  gap: 56px;
  border-top: 1px solid #ffffff25;
  padding-top: 24px;
}
.demo-hero-index span {
  display: flex;
  gap: 14px;
  align-items: baseline;
  font-size: 0.9rem;
}
.demo-hero-index small {
  color: #8f9cb9;
  font-weight: 400;
}
.demo-section-title {
  padding: 68px 0 26px;
}
.demo-section-title h2 {
  font-size: clamp(2rem, 3.7vw, 3rem);
  line-height: 1.3;
  letter-spacing: -0.05em;
}
.demo-section-title > p:last-child {
  color: #697186;
}
.mobile-break {
  display: none;
}
.demo-hero h1 {
  font-size: clamp(2.8rem, 5vw, 5rem);
  line-height: 1.3;
  margin: 16px 0;
}
.demo-boundary {
  display: flex;
  gap: 12px;
  color: #586681;
  font-size: 0.85rem;
  margin: 22px 0;
}
.demo-boundary svg {
  flex-shrink: 0;
  margin-top: 3px;
}
.demo-boundary p {
  margin: 0;
}
.demo-steps {
  display: grid;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  gap: 8px;
  margin: 26px 0;
}
.demo-steps button {
  flex-direction: column;
  padding: 14px 6px;
  font-size: 0.8rem;
}
.demo-steps button[aria-current] {
  background: #253d99;
  color: white;
  border-color: #253d99;
}
.step-number {
  display: grid;
  place-items: center;
  height: 24px;
  font-weight: 800;
}
.demo-stage {
  padding: 32px;
}
.demo-stage-heading {
  display: flex;
  align-items: start;
  gap: 20px;
  justify-content: space-between;
  margin-bottom: 28px;
}
.demo-stage-heading h2 {
  font-size: 1.6rem;
  margin-bottom: 12px;
}
.demo-stage-heading > button {
  flex-shrink: 0;
}
.demo-columns {
  display: grid;
  grid-template-columns: minmax(0, 1.2fr) minmax(0, 1fr);
  gap: 24px;
  margin-bottom: 24px;
}
.demo-document,
.demo-explanation {
  min-width: 0;
  padding: 24px;
  background: #f6f8fc;
  border: 1px solid #e1e6f0;
  border-radius: 6px;
}
.demo-document {
  background: white;
  border-top: 3px solid #526bba;
}
pre {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  font: inherit;
  font-size: 0.9rem;
  line-height: 1.85;
  margin-bottom: 0;
}
blockquote {
  margin: 12px 0;
  border-left: 3px solid #526bba;
  padding-left: 16px;
  font-size: 1.2rem;
  line-height: 1.8;
}
ol {
  padding-left: 22px;
}
li {
  margin-bottom: 18px;
}
.demo-rule {
  display: flex;
  justify-content: space-between;
  gap: 14px;
  background: white;
  border: 1px solid #dce2ef;
  padding: 16px;
  border-radius: 5px;
}
.demo-and {
  text-align: center;
  color: #415aaa;
  font-size: 0.8rem;
  margin: 10px;
}
.demo-rule + .small {
  margin-top: 18px;
}
.demo-comparison {
  padding: 24px;
  margin-top: 24px;
  background: #edf2ff;
  border-left: 3px solid #526bba;
}
.demo-stage-footer {
  border-top: 1px solid #e1e6f0;
  padding-top: 22px;
  margin-top: 30px;
  display: flex;
  gap: 16px;
  align-items: center;
  justify-content: space-between;
}
.demo-stage-footer > span {
  font-size: 0.8rem;
}
.demo-stage .table-scroll {
  margin-bottom: 24px;
}
.demo-stage > .primary {
  margin-top: 20px;
}
@media (max-width: 800px) {
  .demo-columns {
    grid-template-columns: 1fr;
  }
  .demo-steps {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}
@media (max-width: 600px) {
  .demo-topnav {
    gap: 12px;
  }
  .demo-topnav button,
  .demo-label {
    display: none;
  }
  .demo-hero {
    min-height: 640px;
    padding: 150px 24px 160px;
  }
  .demo-hero h1 {
    font-size: 2.65rem;
  }
  .demo-hero .hero-copy > p:not(.eyebrow) {
    font-size: 0.95rem;
  }
  .demo-hero-index {
    left: 24px;
    right: 24px;
    gap: 14px;
    justify-content: space-between;
  }
  .demo-hero-index span {
    flex-direction: column;
    gap: 5px;
    font-size: 0.68rem;
  }
  .demo-section-title {
    padding-top: 32px;
  }
  .mobile-break {
    display: initial;
  }
  .demo-header {
    padding: 20px 24px;
    flex-wrap: wrap;
    gap: 12px;
  }
  .demo-header .wordmark {
    font-size: 1.7rem;
  }
  .demo-label {
    font-size: 0.7rem;
  }
  .demo-login {
    font-size: 0.75rem;
  }
  .demo-main {
    padding: 20px 14px 40px;
  }
  .demo-stage {
    padding: 20px 16px;
  }
  .demo-stage-heading {
    flex-direction: column;
  }
  .demo-stage-footer {
    flex-wrap: wrap;
  }
  .demo-document,
  .demo-explanation {
    padding: 18px;
  }
  .demo-rule {
    flex-wrap: wrap;
  }
}
</style>
