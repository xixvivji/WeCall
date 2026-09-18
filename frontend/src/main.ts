import { createApp } from "vue";
import { createRouter, createWebHashHistory } from "vue-router";
import { confirmDrafts } from "./drafts";
import App from "./App.vue";
import Cases from "./views/Cases.vue";
import CaseDetail from "./views/CaseDetail.vue";
import ReviewInbox from "./views/ReviewInbox.vue";
import Readiness from "./views/Readiness.vue";
import Datasets from "./views/Datasets.vue";
import Users from "./views/Users.vue";
import Workspace from "./views/Workspace.vue";
import Account from "./views/Account.vue";
import "./style.css";
const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: "/demo", component: () => import("./views/Demo.vue") },
    { path: "/", component: Cases },
    { path: "/workspace", component: Workspace },
    { path: "/reviews", component: ReviewInbox },
    { path: "/recalls/:id", component: CaseDetail },
    { path: "/datasets", component: Datasets },
    { path: "/datasets/:id/readiness", component: Readiness },
    { path: "/users", component: Users },
    { path: "/account", component: Account },
    { path: "/:pathMatch(.*)*", redirect: "/" },
  ],
});
router.beforeEach(
  (to, from) => to.fullPath === from.fullPath || confirmDrafts(),
);
const app = createApp(App).use(router);
router.isReady().then(() => app.mount("#app"));
