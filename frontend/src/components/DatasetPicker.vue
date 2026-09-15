<script setup lang="ts">
import { ref, onMounted } from "vue";
import { api, errorText, dateText, type Page, type Dataset } from "../api";
const model = defineModel<string>({ required: true }),
  page = ref(0),
  data = ref<Page<Dataset>>(),
  error = ref(""),
  busy = ref(false);
async function load() {
  busy.value = true;
  error.value = "";
  data.value = undefined;
  try {
    data.value = await api("/api/v1/datasets?page=" + page.value + "&size=20");
  } catch (e) {
    error.value = errorText(e);
  } finally {
    busy.value = false;
  }
}
onMounted(load);
</script>
<template>
  <label
    >판정에 사용할 데이터 버전<select
      v-model="model"
      required
      :disabled="busy || !!error"
    >
      <option value="">데이터 버전 선택</option>
      <option v-for="item in data?.items" :key="item.id" :value="item.id">
        {{ dateText(item.asOf) }} · {{ item.id.slice(0, 8) }}
      </option>
    </select></label
  >
  <div class="inline small">
    <button
      type="button"
      :disabled="page === 0 || busy"
      @click="
        model = '';
        page--;
        load();
      "
    >
      이전 데이터</button
    ><span>{{ page + 1 }} 페이지</span
    ><button
      type="button"
      :disabled="!data || page + 1 >= data.totalPages || busy"
      @click="
        model = '';
        page++;
        load();
      "
    >
      다음 데이터</button
    ><RouterLink to="/datasets">데이터 등록하기</RouterLink>
  </div>
  <p v-if="busy" class="note" role="status">데이터 버전을 불러오는 중입니다…</p>
  <p v-else-if="error" class="error" role="alert">
    {{ error }}
    <button type="button" @click="load">데이터 버전 다시 조회</button>
  </p>
  <p v-else-if="!data?.items.length" class="empty">
    선택할 데이터 버전이 없습니다. 데이터를 먼저 등록하세요.
  </p>
</template>
