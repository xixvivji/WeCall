<script setup lang="ts">
import { ref } from "vue";
import { changeOwnPassword, errorText, user } from "../api";
const currentPassword = ref(""),
  newPassword = ref(""),
  confirmation = ref(""),
  busy = ref(false),
  error = ref("");
async function save() {
  busy.value = true;
  error.value = "";
  try {
    if (newPassword.value !== confirmation.value)
      throw new Error("새 비밀번호 확인이 일치하지 않습니다.");
    if (new TextEncoder().encode(newPassword.value).length > 72)
      throw new Error("새 비밀번호는 UTF-8 기준 72바이트 이하여야 합니다.");
    await changeOwnPassword(currentPassword.value, newPassword.value);
  } catch (e) {
    error.value = errorText(e);
  } finally {
    currentPassword.value = "";
    newPassword.value = "";
    confirmation.value = "";
    busy.value = false;
  }
}
</script>
<template>
  <div class="page-heading">
    <div>
      <p class="eyebrow">MY ACCOUNT</p>
      <h1>내 계정</h1>
      <p class="muted">{{ user?.username }}</p>
    </div>
  </div>
  <section class="panel">
    <h2>비밀번호 변경</h2>
    <p class="note">
      변경 후 현재 기기와 다른 기기의 기존 로그인이 모두 무효화됩니다. 새
      비밀번호로 다시 로그인하세요.
    </p>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <form @submit.prevent="save">
      <fieldset :disabled="busy">
        <label
          >현재 비밀번호<input
            v-model="currentPassword"
            type="password"
            autocomplete="current-password"
            required
            maxlength="200" /></label
        ><label
          >새 비밀번호<input
            v-model="newPassword"
            type="password"
            autocomplete="new-password"
            required
            minlength="12"
            maxlength="72" /></label
        ><label
          >새 비밀번호 확인<input
            v-model="confirmation"
            type="password"
            autocomplete="new-password"
            required
            minlength="12"
            maxlength="72"
        /></label>
        <p class="footnote">
          12자 이상, UTF-8 72바이트 이하. 현재와 다른 비밀번호를 입력하세요.
        </p>
        <button class="primary">
          {{ busy ? "변경 중…" : "비밀번호 변경 후 로그아웃" }}
        </button>
      </fieldset>
    </form>
  </section>
</template>
<style scoped>
fieldset {
  padding: 0;
  border: 0;
  margin: 0;
  min-inline-size: 0;
}
.panel {
  max-width: 680px;
}
</style>
