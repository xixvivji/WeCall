import { createApp } from "vue";
import { createRouter, createWebHashHistory } from "vue-router";
import App from "./App.vue";
import Cases from "./views/Cases.vue";
import CaseDetail from "./views/CaseDetail.vue";
import Datasets from "./views/Datasets.vue";
import "./style.css";
const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: "/", component: Cases },
    { path: "/recalls/:id", component: CaseDetail },
    { path: "/datasets", component: Datasets },
    { path: "/:pathMatch(.*)*", redirect: "/" },
  ],
});
createApp(App).use(router).mount("#app");
