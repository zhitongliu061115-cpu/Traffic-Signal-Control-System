import { createRouter, createWebHistory } from 'vue-router'
import DataAnalysis from '@/views/DataAnalysis.vue'
import Dashboard from '@/views/Dashboard.vue'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      name: 'Dashboard',
      component: Dashboard,
    },
    {
      path: '/data-analysis',
      name: 'DataAnalysis',
      component: DataAnalysis,
    },
  ],
})

export default router
