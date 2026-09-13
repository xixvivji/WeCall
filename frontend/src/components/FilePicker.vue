<script setup lang="ts">
import { ref } from "vue";
const emit = defineEmits<{ "update:modelValue": [File[]] }>();
defineProps<{ modelValue: File[]; label?: string }>();
const error = ref("");
function select(event: Event) {
  const input = event.target as HTMLInputElement;
  const files = Array.from(input.files ?? []);
  error.value = "";
  if (
    files.length > 5 ||
    files.some((f) => f.size === 0 || f.size > 10485760)
  ) {
    error.value =
      "빈 파일은 제외하고 파일당 10 MiB 이하, 최대 5개를 선택하세요.";
    input.value = "";
    emit("update:modelValue", []);
    return;
  }
  emit("update:modelValue", files);
}
</script>
<template>
  <label
    >{{ label ?? "근거 파일 첨부"
    }}<input
      type="file"
      multiple
      accept=".pdf,.png,.jpg,.jpeg"
      @change="select"
  /></label>
  <p class="footnote">
    선택 사항 · PDF/PNG/JPEG · 파일당 10 MiB, 최대 5개. 저장 후 교체할 수
    없습니다. 파일을 첨부해도 텍스트 근거와 검토는 필요합니다.
  </p>
  <p v-if="error" class="error" role="alert">{{ error }}</p>
</template>
