<script setup lang="ts">
import BrandFlow from "./components/BrandFlow.vue";
import { confirmDrafts } from "./drafts";
import { onMounted, ref } from "vue";
import {
  ClipboardList,
  Database,
  LogOut,
  ShieldCheck,
  Users,
  KeyRound,
} from "@lucide/vue";
import {
  user,
  restoreSession,
  login,
  logout,
  errorText,
  sessionNotice,
} from "./api";
const ready = ref(false),
  busy = ref(false),
  error = ref(""),
  username = ref(""),
  password = ref("");
onMounted(async () => {
  try {
    await restoreSession();
  } catch (e) {
    error.value = errorText(e);
  } finally {
    ready.value = true;
  }
});
async function signIn() {
  busy.value = true;
  error.value = "";
  try {
    await login(username.value, password.value);
    password.value = "";
  } catch (e) {
    error.value = errorText(e);
  } finally {
    busy.value = false;
  }
}
async function signOut() {
  if (!confirmDrafts()) return;
  try {
    await logout();
  } catch (e) {
    error.value = errorText(e);
  }
}
</script>
<template>
  <div v-if="!ready" class="loading" role="status">
    업무 공간을 불러오는 중입니다…
  </div>
  <div v-else-if="!user" class="login-layout">
    <section class="login-intro">
      <BrandFlow />
      <div class="wordmark">
        w<span>e</span>call<span class="brand-dot">.</span>
      </div>
      <p class="eyebrow">RECALL OPERATIONS</p>
      <h1>확실한 근거로,<br />끝까지 연결되는<br />회수 대응.</h1>
      <p>
        회수 요청부터 조건 검토, 영향 확인과 대응 기록까지.<br />한곳에서
        이어지는 회수 업무.
      </p>
      <div class="intro-footer">
        <ShieldCheck :size="20" /> 검토와 승인 기록으로 남기는 의사결정
      </div>
    </section>
    <main class="login-form">
      <div class="eyebrow">WORKSPACE LOGIN</div>
      <h2>WeCall에 로그인</h2>
      <p class="muted">발급받은 계정으로 업무 공간에 접속하세요.</p>
      <p v-if="sessionNotice" class="note" role="status">{{ sessionNotice }}</p>
      <form @submit.prevent="signIn">
        <label
          >계정명<input
            v-model="username"
            required
            autocomplete="username"
            placeholder="계정명 입력" /></label
        ><label
          >비밀번호<input
            v-model="password"
            required
            type="password"
            autocomplete="current-password"
            placeholder="비밀번호 입력"
        /></label>
        <p v-if="error" class="error" role="alert">{{ error }}</p>
        <button class="primary wide" :disabled="busy">
          {{ busy ? "로그인 중…" : "로그인" }}
        </button>
      </form>
      <p class="muted small">
        계정이 없다면 담당 검토자에게 발급을 요청하세요.
      </p>
    </main>
  </div>
  <div v-else class="workspace">
    <aside class="sidebar">
      <RouterLink class="wordmark" to="/"
        >wecall<span class="brand-dot">.</span></RouterLink
      >
      <div class="workspace-name">회수 관리 워크스페이스</div>
      <p class="nav-label">업무</p>
      <nav aria-label="주요 메뉴">
        <RouterLink to="/"><ClipboardList :size="20" />회수 사건</RouterLink
        ><RouterLink to="/datasets"
          ><Database :size="20" />데이터 관리</RouterLink
        >
        <RouterLink v-if="user.roles.includes('REVIEWER')" to="/users"
          ><Users :size="20" />계정 관리</RouterLink
        >
        <RouterLink to="/workspace"
          ><ClipboardList :size="20" />업무 대시보드</RouterLink
        >
        <RouterLink v-if="user.roles.includes('REVIEWER')" to="/reviews"
          ><ShieldCheck :size="20" />검토 대기함</RouterLink
        >
        <RouterLink to="/account"><KeyRound :size="20" />내 계정</RouterLink>
      </nav>
      <div class="sidebar-bottom">
        <div class="avatar">{{ user.username.slice(0, 1).toUpperCase() }}</div>
        <div>
          <strong>{{ user.username }}</strong
          ><small>{{
            user.roles.includes("REVIEWER") ? "검토자" : "실행 담당자"
          }}</small>
        </div>
        <button class="icon-button" aria-label="로그아웃" @click="signOut">
          <LogOut :size="18" />
        </button>
      </div>
    </aside>
    <div class="main-shell">
      <header class="topbar">
        <span>WeCall B2B <span class="divider">/</span> 회수 업무</span
        ><span class="muted">{{
          user.roles.includes("REVIEWER")
            ? "검토 · 승인 권한"
            : "담당 작업 실행 권한"
        }}</span
        ><button
          class="mobile-logout"
          aria-label="모바일 로그아웃"
          @click="signOut"
        >
          <LogOut :size="18" />
        </button>
      </header>
      <main class="content">
        <p v-if="error" class="error" role="alert">{{ error }}</p>
        <RouterView :key="$route.fullPath" />
      </main>
    </div>
  </div>
</template>
