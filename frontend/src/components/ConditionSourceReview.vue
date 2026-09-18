<script setup lang="ts">
import { ref } from "vue";
import { api, errorText, dateText, user, type Condition } from "../api";
import { useDraftGuard } from "../drafts";
const props = defineProps<{
  caseId: string;
  condition: Condition;
  closed: boolean;
}>();
const emit = defineEmits<{ updated: [] }>();
const note = ref(""),
  confirmed = ref(false),
  busy = ref(false),
  error = ref(""),
  mode = ref<"review" | "withdrawal" | "">("");
const draft = useDraftGuard(() => ({
  note: note.value,
  confirmed: confirmed.value,
}));
function toggle(next: "review" | "withdrawal" | "") {
  if (!draft.discard()) return;
  note.value = "";
  confirmed.value = false;
  mode.value = next;
  draft.saved();
}
async function submit() {
  if (busy.value || !mode.value || !props.condition.sourceReview) return;
  busy.value = true;
  error.value = "";
  try {
    await api(
      `/api/v1/recalls/${props.caseId}/conditions/${props.condition.id}/${mode.value === "review" ? "source-review" : "withdrawal"}`,
      {
        expectedSourceVersion: props.condition.sourceReview.currentVersion,
        note: note.value,
        unchangedConfirmed: confirmed.value,
      },
    );
    note.value = "";
    confirmed.value = false;
    mode.value = "";
    draft.saved();
    emit("updated");
  } catch (e) {
    error.value = errorText(e);
  } finally {
    busy.value = false;
  }
}
</script>
<template>
  <div v-if="condition.sourceReview" class="source-review">
    <p class="small">
      조건 생성 기준 원문:
      {{
        condition.sourceReview.basisVersion === null
          ? "기록 없음"
          : "v" + condition.sourceReview.basisVersion
      }}
      · 현재 원문 v{{ condition.sourceReview.currentVersion }}
    </p>
    <p v-if="condition.sourceReview.required" class="note">
      원문 재검토 필요. 조건·상품 연결·근거가 현재 원문에도 유효한지 확인하세요.
      내용이 달라졌으면 새 조건을 작성하고, 사용하지 않을 초안은 철회하세요.
    </p>
    <p v-if="condition.withdrawnBy" class="muted">
      철회 {{ condition.withdrawnBy }} · {{ condition.withdrawalNote }}
    </p>
    <details v-if="condition.sourceReview.reviews.length">
      <summary>원문 영향 검토 이력</summary>
      <p
        v-for="item in condition.sourceReview.reviews"
        :key="item.sourceVersion"
      >
        원문 v{{ item.sourceVersion }} · {{ item.reviewer }} ·
        {{ dateText(item.createdAt) }} · {{ item.note }}
      </p>
    </details>
    <template v-if="user?.roles.includes('REVIEWER') && !closed">
      <div class="form-actions">
        <button
          v-if="condition.sourceReview.required"
          :disabled="busy"
          @click="toggle('review')"
        >
          원문 영향 없음 검토
        </button>
        <button
          v-if="condition.status === 'DRAFT'"
          :disabled="busy"
          @click="toggle('withdrawal')"
        >
          조건 초안 철회
        </button>
      </div>
      <form v-if="mode" @submit.prevent="submit">
        <p v-if="error" role="alert" class="error">{{ error }}</p>
        <label
          >{{ mode === "review" ? "원문 영향 검토 사유" : "초안 철회 사유"
          }}<textarea
            v-model="note"
            required
            maxlength="2000"
            :disabled="busy"
          />
        </label>
        <label v-if="mode === 'review'" class="check-label"
          ><input
            v-model="confirmed"
            type="checkbox"
            :disabled="busy"
            required
          />조건·상품 연결·근거가 현재 원문에도 그대로 유효함을
          확인했습니다.</label
        >
        <p v-else class="muted">
          초안과 철회 사유는 보존하며 승인·판정 대상으로 사용하지 않습니다.
        </p>
        <button
          :disabled="busy || !note.trim() || (mode === 'review' && !confirmed)"
        >
          {{ mode === "review" ? "영향 없음 확인 저장" : "철회 사유 저장" }}
        </button>
        <button type="button" :disabled="busy" @click="toggle('')">취소</button>
      </form>
    </template>
  </div>
</template>
