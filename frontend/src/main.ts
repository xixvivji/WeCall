import { createApp } from "vue";
import { createRouter, createWebHashHistory } from "vue-router";
import App from "./App.vue";
import Cases from "./views/Cases.vue";
import CaseDetail from "./views/CaseDetail.vue";
import Readiness from "./views/Readiness.vue";
import Datasets from "./views/Datasets.vue";
import Users from "./views/Users.vue";
import Workspace from "./views/Workspace.vue";
import Account from "./views/Account.vue";
import "./style.css";
const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: "/", component: Cases },
    { path: "/workspace", component: Workspace },
    { path: "/recalls/:id", component: CaseDetail },
    { path: "/datasets", component: Datasets },
    { path: "/datasets/:id/readiness", component: Readiness },
    { path: "/users", component: Users },
    { path: "/account", component: Account },
    { path: "/:pathMatch(.*)*", redirect: "/" },
  ],
});
createApp(App).use(router).mount("#app");
