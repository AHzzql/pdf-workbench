import { createRouter, createWebHashHistory } from "vue-router";
import WorkbenchView from "../views/WorkbenchView.vue";
export default createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: "/", redirect: "/text" },
    {
      path: "/:tool(text|tables|images|encrypt|signatures|watermark|info)",
      component: WorkbenchView,
    },
    { path: "/:pathMatch(.*)*", redirect: "/text" },
  ],
});
