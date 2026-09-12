<script setup lang="ts">
import { ref, onMounted, computed } from "vue";
import { api, user, errorText } from "../api";
interface Account {
  username: string;
  displayName: string;
  role: string;
  enabled: boolean;
}
const accounts = ref<Account[]>([]),
  error = ref(""),
  success = ref(""),
  busy = ref(false),
  loading = ref(false),
  username = ref(""),
  displayName = ref(""),
  password = ref(""),
  role = ref("OPERATOR");
const reviewer = computed(() => user.value?.roles.includes("REVIEWER"));
async function load() {
  if (!reviewer.value) return;
  loading.value = true;
  try {
    accounts.value = await api("/api/users");
  } catch (e) {
    error.value = errorText(e);
  } finally {
    loading.value = false;
  }
}
async function create() {
  busy.value = true;
  error.value = "";
  success.value = "";
  try {
    if (new TextEncoder().encode(password.value).length > 72)
      throw new Error("비밀번호는 UTF-8 기준 72바이트 이하여야 합니다.");
    await api("/api/users", {
      username: username.value,
      displayName: displayName.value,
      password: password.value,
      role: role.value,
    });
    success.value = `${username.value} 계정을 생성했습니다.`;
    username.value = "";
    displayName.value = "";
    role.value = "OPERATOR";
    await load();
  } catch (e) {
    error.value = errorText(e);
  } finally {
    password.value = "";
    busy.value = false;
  }
}
onMounted(load);
</script>
<template>
  <div class="page-heading">
    <div>
      <p class="eyebrow">TEAM ACCOUNTS</p>
      <h1>계정 관리</h1>
      <p class="muted">업무 담당자 계정을 발급하고 권한을 확인합니다.</p>
    </div>
  </div>
  <p v-if="!reviewer" class="note">계정 관리는 검토자만 사용할 수 있습니다.</p>
  <template v-else
    ><p v-if="error" class="error" role="alert">{{ error }}</p>
    <p v-if="success" class="success" role="status">{{ success }}</p>
    <section class="panel">
      <h2>계정 생성</h2>
      <form @submit.prevent="create">
        <fieldset :disabled="busy" class="form-fieldset">
          <div class="form-grid">
            <label
              >새 계정명<input
                v-model="username"
                required
                pattern="[a-z][a-z0-9._-]{2,63}"
                minlength="3"
                maxlength="64"
                autocomplete="off"
                placeholder="영문 소문자로 시작, 3~64자" /></label
            ><label
              >표시 이름<input
                v-model="displayName"
                required
                maxlength="200"
                autocomplete="off"
            /></label>
          </div>
          <label
            >초기 비밀번호<input
              v-model="password"
              required
              type="password"
              minlength="12"
              maxlength="72"
              autocomplete="new-password"
          /></label>
          <p class="footnote">
            12자 이상, UTF-8 72바이트 이하. 비밀번호는 생성 후 화면에 남기지
            않습니다.
          </p>
          <label
            >계정 권한<select v-model="role" aria-label="계정 권한">
              <option value="OPERATOR">실행 담당자</option>
              <option value="REVIEWER">검토자</option>
            </select></label
          >
          <p class="note">
            {{
              role === "REVIEWER"
                ? "검토자는 조건·증거 승인, 작업 배정·완료, 사건 종료와 계정 생성을 할 수 있습니다."
                : "실행 담당자는 업무를 조회하고 입고 증거를 제안하며, 자신에게 배정된 작업을 시작하고 증빙을 제출할 수 있습니다."
            }}
          </p>
          <button class="primary">{{ busy ? "생성 중…" : "계정 생성" }}</button>
        </fieldset>
      </form>
    </section>
    <section class="panel list-panel">
      <div class="list-caption">
        <strong>팀 계정</strong><span>{{ accounts.length }}명</span>
      </div>
      <p v-if="loading" class="empty" role="status">
        계정을 불러오는 중입니다…
      </p>
      <div v-else class="table-scroll">
        <table>
          <thead>
            <tr>
              <th>계정명</th>
              <th>이름</th>
              <th>권한</th>
              <th>상태</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="account in accounts" :key="account.username">
              <td>{{ account.username }}</td>
              <td>{{ account.displayName }}</td>
              <td>
                {{ account.role === "REVIEWER" ? "검토자" : "실행 담당자" }}
              </td>
              <td>{{ account.enabled ? "활성" : "비활성" }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </section></template
  >
</template>
<style scoped>
.form-fieldset {
  border: 0;
  padding: 0;
  margin: 0;
  min-inline-size: 0;
}
</style>
