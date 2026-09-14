<script setup lang="ts">
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
  if (saving.value) return;
  saving.value = true;
  createError.value = "";
  try {
    const result = await api<CaseRow>("/api/v1/recalls", {
      title: title.value,
      sourceType: source.value,
      sourceText: text.value,
    });
    await router.push("/recalls/" + result.id);
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
  <div class="page-heading">
    <div>
      <p class="eyebrow">RECALL CASES</p>
      <h1>회수 사건</h1>
      <p class="muted">요청을 확인하고, 필요한 대응을 이어가세요.</p>
    </div>
    <button
      v-if="user?.roles.includes('REVIEWER')"
      class="primary"
      @click="creating = !creating"
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
        >회수 원문<textarea
          v-model="text"
          required
          maxlength="100000"
          rows="7"
          placeholder="회수 요청 원문을 그대로 붙여 넣으세요"
        />
      </label>
      <div class="form-actions">
        <button class="primary" :disabled="saving">
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
