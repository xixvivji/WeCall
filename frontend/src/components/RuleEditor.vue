<script setup lang="ts">
import { type Rule } from "../api";
const model = defineModel<Rule>({ required: true });
const props = withDefaults(defineProps<{ depth?: number }>(), { depth: 0 });
function changeOp(e: Event) {
  const op = (e.target as HTMLSelectElement).value;
  model.value =
    op === "AND" || op === "OR"
      ? { op, children: [{ op: "EQ", field: "LOT_NUMBER", values: [""] }] }
      : { op, field: "LOT_NUMBER", values: op === "BETWEEN" ? ["", ""] : [""] };
}
function changeField(e: Event) {
  model.value = {
    ...model.value,
    field: (e.target as HTMLSelectElement).value,
    values: model.value.op === "BETWEEN" ? ["", ""] : [""],
  };
}
function values(e: Event) {
  model.value = {
    ...model.value,
    values: (e.target as HTMLInputElement).value
      .split(",")
      .map((v) => v.trim()),
  };
}
</script>
<template>
  <div class="rule-box">
    <div class="rule-row">
      <select :value="model.op" aria-label="조건 연산" @change="changeOp">
        <option value="EQ">같음</option>
        <option value="IN">목록 중 하나</option>
        <option value="BETWEEN">범위 (양끝 포함)</option>
        <option v-if="props.depth < 4" value="AND">모든 조건 충족</option>
        <option v-if="props.depth < 4" value="OR">
          하나 이상 충족
        </option></select
      ><template v-if="model.op !== 'AND' && model.op !== 'OR'"
        ><select
          :value="model.field"
          aria-label="조건 항목"
          @change="changeField"
        >
          <option value="LOT_NUMBER">제조번호</option>
          <option value="EXPIRY_DATE">소비기한</option></select
        ><template v-if="model.op === 'BETWEEN'"
          ><input
            v-model="model.values![0]"
            aria-label="범위 시작"
            :type="model.field === 'EXPIRY_DATE' ? 'date' : 'text'"
            required /><input
            v-model="model.values![1]"
            aria-label="범위 종료"
            :type="model.field === 'EXPIRY_DATE' ? 'date' : 'text'"
            required /></template
        ><input
          v-else
          :value="model.values?.join(',')"
          :type="
            model.field === 'EXPIRY_DATE' && model.op === 'EQ' ? 'date' : 'text'
          "
          aria-label="조건 값"
          :placeholder="
            model.op === 'IN' ? '쉼표로 구분 (예: A01,A02)' : '값 입력'
          "
          required
          @input="values"
      /></template>
    </div>
    <div v-if="model.children" class="rule-children">
      <div v-for="(_, index) in model.children" :key="index">
        <RuleEditor
          v-model="model.children[index]!"
          :depth="props.depth + 1"
        /><button
          v-if="model.children.length > 1"
          type="button"
          class="small danger"
          @click="model.children.splice(index, 1)"
        >
          이 조건 삭제
        </button>
      </div>
      <button
        type="button"
        @click="
          model.children.push({ op: 'EQ', field: 'LOT_NUMBER', values: [''] })
        "
      >
        하위 조건 추가
      </button>
    </div>
  </div>
</template>
<style scoped>
.rule-box {
  padding: 12px;
  border: 1px solid #e2e9e4;
  border-radius: 8px;
  margin: 8px 0;
}
.rule-children {
  padding-left: 12px;
  border-left: 2px solid #dbe9df;
  display: grid;
  gap: 10px;
}
@media (max-width: 700px) {
  .rule-children {
    padding-left: 4px;
  }
  .rule-box {
    padding: 8px;
  }
}
</style>
