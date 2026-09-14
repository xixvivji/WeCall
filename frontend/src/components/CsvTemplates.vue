<script setup lang="ts">
import { ref, onMounted } from "vue";
import { api, downloadCsvTemplate, errorText } from "../api";
interface Template {
  type: string;
  field: string;
  label: string;
  columns: {
    name: string;
    label: string;
    required: boolean;
    guidance: string;
    example: string;
  }[];
}
const templates = ref<Template[]>([]),
  error = ref(""),
  loading = ref(false),
  downloading = ref(false);
async function load() {
  if (loading.value) return;
  loading.value = true;
  error.value = "";
  try {
    templates.value = await api("/api/v1/datasets/templates");
  } catch (e) {
    error.value = errorText(e);
  } finally {
    loading.value = false;
  }
}
async function download(type?: string) {
  if (downloading.value) return;
  downloading.value = true;
  error.value = "";
  try {
    await downloadCsvTemplate(type);
  } catch (e) {
    error.value =
      e instanceof TypeError
        ? "양식을 내려받지 못했습니다. 연결 상태를 확인하세요."
        : errorText(e);
  } finally {
    downloading.value = false;
  }
}
onMounted(load);
</script>
<template>
  <div class="template-guide">
    <button type="button" :disabled="downloading" @click="download()">
      CSV 빈 양식 5종 다운로드
    </button>
    <p class="muted small">
      ZIP에 헤더만 있는 CSV 5개와 작성안내.txt가 들어 있습니다. 예시 값은
      설명용이며 양식에 입력되어 있지 않습니다.
    </p>
    <details>
      <summary>CSV 항목별 작성 안내</summary>
      <p class="note">
        ID는 텍스트로 입력해 앞자리 0을 보존하세요. UTF-8 CSV로 저장하고 첫 행의
        항목명·순서를 바꾸지 마세요. 제조번호·소비기한만 빈칸을 허용하며, 모르는
        연결은 추정하지 않습니다. 모든 텍스트는 500자 이내입니다. 기록이 없는
        파일도 헤더를 유지해서 제출하세요.
      </p>
      <p v-if="loading" role="status">양식 정보를 불러오는 중입니다…</p>
      <details
        v-for="template in templates"
        :key="template.type"
        class="file-guide"
      >
        <summary>{{ template.label }} · {{ template.type }}.csv</summary>
        <button
          type="button"
          :disabled="downloading"
          @click="download(template.type)"
        >
          {{ template.label }} 빈 양식 다운로드
        </button>
        <div class="table-scroll">
          <table>
            <thead>
              <tr>
                <th>항목</th>
                <th>필수</th>
                <th>작성 방법</th>
                <th>예시</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="column in template.columns" :key="column.name">
                <td>
                  {{ column.label }}<small>{{ column.name }}</small>
                </td>
                <td>{{ column.required ? "필수" : "선택" }}</td>
                <td>{{ column.guidance }}</td>
                <td>{{ column.example }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </details>
    </details>
    <p v-if="error" class="error" role="alert">
      {{ error }}
      <button type="button" :disabled="loading" @click="load">
        양식 안내 다시 조회
      </button>
    </p>
  </div>
</template>
<style scoped>
.template-guide {
  margin: 16px 0;
}
.file-guide {
  margin: 12px 0;
}
td {
  overflow-wrap: anywhere;
  min-width: 65px;
}
small {
  display: block;
}
</style>
