<script setup lang="ts">
import BrandFlow from "../components/BrandFlow.vue";
import { useDraftGuard } from "../drafts";
import { onMounted, onUnmounted, ref } from "vue";
import { Search, Plus, ArrowUpRight } from "@lucide/vue";
import {
  api,
  user,
  label,
  dateText,
  errorText,
  type Page,
  type CaseRow,
} from "../api";
import { useRouter } from "vue-router";
import { registerVisibleCases } from "../webmcp";
const router = useRouter(),
  q = ref(""),
  status = ref(""),
  sourceType = ref(""),
  page = ref(0),
  data = ref<Page<CaseRow>>(),
  loading = ref(false),
  error = ref(""),
  createError = ref(""),
  creating = ref(false),
  saving = ref(false);
const title = ref(""),
  source = ref("SUPPLIER"),
  text = ref("");
const appliedPdf = ref<{ file: File; sha256: string }>();
let previewFile: File | undefined;
const extracting = ref(false),
  pdfPreview = ref<{
    text: string;
    pages: number;
    scanStatus: string;
    sha256: string;
  }>(),
  pdfError = ref("");
async function extractPdf(event: Event) {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  input.value = "";
  if (!file || extracting.value) return;
  pdfPreview.value = undefined;
  pdfError.value = "";
  if (file.size > 10485760) {
    pdfError.value = "PDF는 10 MiB 이하여야 합니다";
    return;
  }
  extracting.value = true;
  try {
    const form = new FormData();
    form.append("file", file);
    pdfPreview.value = await api("/api/v1/source-pdf", form);
    previewFile = file;
  } catch (e) {
    pdfError.value = errorText(e);
  } finally {
    extracting.value = false;
  }
}
function applyPdf() {
  if (!pdfPreview.value) return;
  if (
    text.value &&
    !window.confirm("입력한 원문을 PDF 추출 내용으로 바꿀까요?")
  )
    return;
  text.value = pdfPreview.value.text;
  appliedPdf.value = { file: previewFile!, sha256: pdfPreview.value.sha256 };
  pdfPreview.value = undefined;
}
const draft = useDraftGuard(() => ({
  title: title.value,
  source: source.value,
  text: text.value,
  pdfPreview: pdfPreview.value?.text,
  pdf: appliedPdf.value,
}));
function toggleCreate() {
  if (extracting.value || saving.value) return;
  if (creating.value && !draft.discard()) return;
  if (creating.value) {
    title.value = "";
    source.value = "SUPPLIER";
    text.value = "";
    appliedPdf.value = undefined;
    previewFile = undefined;
    pdfPreview.value = undefined;
    pdfError.value = "";
    draft.saved();
  }
  creating.value = !creating.value;
}
let generation = 0;
async function load(reset = false) {
  if (reset) page.value = 0;
  const ticket = ++generation;
  data.value = undefined;
  loading.value = true;
  error.value = "";
  try {
    const result = await api<Page<CaseRow>>(
      "/api/v1/recalls?" +
        new URLSearchParams({
          q: q.value,
          status: status.value,
          sourceType: sourceType.value,
          page: String(page.value),
          size: "12",
        }),
    );
    if (ticket === generation) data.value = result;
  } catch (e) {
    if (ticket === generation) error.value = errorText(e);
  } finally {
    if (ticket === generation) loading.value = false;
  }
}
async function create() {
  if (saving.value || extracting.value || pdfPreview.value) return;
  saving.value = true;
  createError.value = "";
  try {
    const metadata = {
      title: title.value,
      sourceType: source.value,
      sourceText: text.value,
    };
    let result: CaseRow;
    if (appliedPdf.value) {
      const form = new FormData();
      form.append("file", appliedPdf.value.file);
      form.append(
        "metadata",
        new Blob(
          [
            JSON.stringify({
              recall: metadata,
              expectedSha256: appliedPdf.value.sha256,
            }),
          ],
          { type: "application/json" },
        ),
      );
      result = await api<CaseRow>("/api/v1/recalls/from-pdf", form);
    } else result = await api<CaseRow>("/api/v1/recalls", metadata);
    draft.saved();
    await router.push(
      "/recalls/" + result.id + (appliedPdf.value ? "?tab=ai" : ""),
    );
  } catch (e) {
    createError.value = errorText(e);
  } finally {
    saving.value = false;
  }
}
let unregister = () => {};
onMounted(() => {
  load();
  unregister = registerVisibleCases(() => ({
    loading: loading.value,
    error: error.value,
    page: data.value?.page ?? 0,
    totalElements: data.value?.totalElements ?? 0,
    items: loading.value || error.value ? [] : (data.value?.items ?? []),
  }));
});
onUnmounted(() => {
  generation++;
  unregister();
});
</script>
<template>
  <section class="brand-hero" aria-label="회수 업무 안내">
    <BrandFlow />
    <div class="hero-copy">
      <p class="eyebrow">CONNECTED RESPONSE, WECALL</p>
      <h2>확인에서 대응까지.<br />모든 과정이 이어지도록.</h2>
      <p>원문과 근거를 확인하고, 회수 대응을 한곳에서 관리하세요.</p>
      <RouterLink to="/workspace" class="hero-link"
        >업무 대시보드 열기 <ArrowUpRight :size="18"
      /></RouterLink>
    </div>
    <span class="hero-index" aria-hidden="true">01 — RECALL OPERATIONS</span>
  </section>
  <div class="page-heading">
    <div>
      <p class="eyebrow">RECALL CASES</p>
      <h1>회수 사건</h1>
      <p class="muted">요청을 확인하고, 필요한 대응을 이어가세요.</p>
    </div>
    <button
      v-if="user?.roles.includes('REVIEWER')"
      class="primary"
      @click="toggleCreate"
    >
      <Plus :size="18" />{{ creating ? "등록 닫기" : "새 사건 등록" }}
    </button>
  </div>
  <section v-if="creating" class="panel">
    <h2>새 회수 사건</h2>
    <p v-if="createError" class="error" role="alert">{{ createError }}</p>
    <form @submit.prevent="create">
      <div class="form-grid">
        <label
          >사건명<input
            v-model="title"
            required
            maxlength="200"
            placeholder="상품명과 회수 사유를 입력하세요" /></label
        ><label
          >요청 출처<select v-model="source">
            <option value="SUPPLIER">공급사 요청</option>
            <option value="OFFICIAL">공식 공고</option>
            <option value="INTERNAL">내부 확인</option>
          </select></label
        >
      </div>
      <label
        >텍스트 PDF에서 가져오기
        <input
          type="file"
          accept="application/pdf,.pdf"
          :disabled="extracting || saving"
          @change="extractPdf"
        />
      </label>
      <p class="muted">
        최대 10 MiB·50쪽. 로컬 서버에서 텍스트를 추출합니다. 사건 등록 시 PDF
        원본·추출본·확인한 텍스트를 함께 보관합니다. 스캔 PDF의 OCR은 아직
        지원하지 않습니다.
      </p>
      <p v-if="extracting" role="status">PDF 원문을 추출하고 있습니다…</p>
      <p v-if="pdfError" class="error" role="alert">{{ pdfError }}</p>
      <div v-if="pdfPreview" class="panel">
        <h3>PDF 추출 미리보기 · {{ pdfPreview.pages }}쪽</h3>
        <p>
          표·줄바꿈·읽기 순서가 달라질 수 있습니다. PDF와 대조하고 적용 후
          원문을 수정하세요.
        </p>
        <p>
          {{
            pdfPreview.scanStatus === "CLEAN"
              ? "악성 파일 검사 통과"
              : "악성 파일 검사 미실행"
          }}
        </p>
        <textarea
          aria-label="PDF 추출 미리보기"
          :value="pdfPreview.text"
          readonly
          rows="8"
        />
        <button type="button" @click="applyPdf">
          추출 내용으로 원문 채우기
        </button>
        <button type="button" @click="pdfPreview = undefined">
          미리보기 버리기
        </button>
      </div>
      <p v-if="appliedPdf" class="muted">
        보관할 원본: {{ appliedPdf.file.name }}
        <button type="button" @click="appliedPdf = undefined">
          PDF 연결 해제
        </button>
      </p>
      <label
        >회수 원문<textarea
          v-model="text"
          required
          maxlength="100000"
          rows="7"
          placeholder="회수 요청 원문을 그대로 붙여 넣으세요"
        />
      </label>
      <p class="muted">
        원문을 확인·수정한 뒤 등록하세요. 현재 로컬 AI는 6,000바이트·80줄까지
        분석하며, 초과 시 자동으로 자르지 않습니다.
      </p>
      <div class="form-actions">
        <button
          class="primary"
          :disabled="saving || extracting || !!pdfPreview"
        >
          {{ saving ? "등록 중…" : "사건 등록" }}
        </button>
      </div>
    </form>
  </section>
  <section class="panel list-panel">
    <form class="filters" @submit.prevent="load(true)">
      <div class="search">
        <Search :size="18" /><input
          v-model="q"
          maxlength="200"
          aria-label="사건명 검색"
          placeholder="사건명 검색"
        />
      </div>
      <select v-model="status" aria-label="사건 상태" @change="load(true)">
        <option value="">모든 상태</option>
        <option value="OPEN">진행 중</option>
        <option value="CLOSED">종료</option></select
      ><select v-model="sourceType" aria-label="요청 출처" @change="load(true)">
        <option value="">모든 출처</option>
        <option value="SUPPLIER">공급사 요청</option>
        <option value="OFFICIAL">공식 공고</option>
        <option value="INTERNAL">내부 확인</option></select
      ><button class="secondary" :disabled="loading">검색</button>
    </form>
    <p v-if="error" class="error" role="alert">
      {{ error }} <button @click="load()">다시 시도</button>
    </p>
    <div class="list-caption">
      <strong>사건 목록</strong
      ><span v-if="data" class="muted">{{ data.totalElements }}건</span>
    </div>
    <div v-if="loading" class="empty" role="status">
      사건을 불러오는 중입니다…
    </div>
    <div v-else-if="!error && data && !data.items.length" class="empty">
      <h3>조건에 맞는 사건이 없습니다</h3>
      <p>검색 조건을 바꾸거나 새 회수 사건을 등록하세요.</p>
    </div>
    <div v-else-if="!error && data" class="table-scroll">
      <table>
        <thead>
          <tr>
            <th>사건명</th>
            <th>상태</th>
            <th>출처</th>
            <th>미승인 조건</th>
            <th>남은 작업</th>
            <th>등록일</th>
            <th><span class="sr-only">열기</span></th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="item in data.items" :key="item.id">
            <td>
              <RouterLink class="case-link" :to="'/recalls/' + item.id">{{
                item.title
              }}</RouterLink
              ><small class="muted">{{
                item.id.slice(0, 8).toUpperCase()
              }}</small>
            </td>
            <td>
              <span class="badge" :class="item.status">{{
                label(item.status)
              }}</span>
            </td>
            <td>{{ label(item.sourceType) }}</td>
            <td>{{ item.draftCount }}건</td>
            <td>{{ item.openTaskCount }}건</td>
            <td class="muted">{{ dateText(item.createdAt) }}</td>
            <td>
              <RouterLink
                :to="'/recalls/' + item.id"
                :aria-label="item.title + ' 열기'"
                ><ArrowUpRight :size="18"
              /></RouterLink>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <footer v-if="data && !error" class="pagination">
      <span
        >{{ data?.totalPages ? page + 1 : 0 }} /
        {{ data?.totalPages ?? 0 }} 페이지</span
      >
      <div>
        <button
          :disabled="page === 0 || loading"
          @click="
            page--;
            load();
          "
        >
          이전</button
        ><button
          :disabled="!data || page + 1 >= data.totalPages || loading"
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
  <p class="footnote">대상 판정과 대응 작업 상태는 별도로 관리됩니다.</p>
</template>
