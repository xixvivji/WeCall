<script setup lang="ts">
import { computed } from "vue";
import { aiInput } from "../aiInput";
const props = defineProps<{ text: string }>();
const size = computed(() => aiInput(props.text));
</script>
<template>
  <div
    :class="size.exceeded ? 'error' : 'note'"
    role="status"
    aria-label="AI 입력 한도"
  >
    <strong
      >AI 분석 입력: {{ size.bytes.toLocaleString("ko-KR") }} / 6,000바이트 ·
      {{ size.lines }} / 80줄</strong
    >
    <p v-if="size.exceeded">
      AI 입력 한도를 초과했습니다. 사건 등록·원문 보관은 가능합니다. 예외·정정
      내용을 삭제해 한도에 맞추지 말고 원문 전체를 확인해 수동으로 조건을
      작성하세요.
    </p>
    <p v-else-if="size.empty">분석할 원문을 입력하세요.</p>
    <p v-else>
      입력 크기 기준으로 분석 가능합니다. 조건 추출 가능 여부와 정확성은 별도
      검토가 필요합니다.
    </p>
  </div>
</template>
