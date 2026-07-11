<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { RouterLink, useRouter } from 'vue-router'

import { register } from '@/api/auth'
import bgVideo from '@/assets/images/bg/bg-video.mp4'
import { saveAuthSession } from '@/utils/authSession'

const router = useRouter()
const loading = ref(false)
const errorMessage = ref('')

const form = reactive({
  confirmPassword: '',
  email: '',
  inviteCode: '',
  password: '',
  username: '',
})

const passwordMatched = computed(() => form.password.length > 0 && form.password === form.confirmPassword)
const canSubmit = computed(() =>
  Boolean(
    form.username.trim() &&
    form.email.trim() &&
    form.password &&
    passwordMatched.value &&
    form.inviteCode.trim(),
  ),
)

async function handleSubmit(): Promise<void> {
  if (!canSubmit.value || loading.value) return

  loading.value = true
  errorMessage.value = ''

  try {
    const result = await register({
      email: form.email.trim(),
      inviteCode: form.inviteCode.trim(),
      password: form.password,
      username: form.username.trim(),
    })
    saveAuthSession(result)

    await router.push('/')
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '注册失败，请稍后重试'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <main class="auth-shell auth-shell--register">
    <video class="auth-video" autoplay muted loop playsinline preload="auto">
      <source :src="bgVideo" type="video/mp4" />
    </video>
    <div class="auth-overlay" />
    <div class="auth-grid" aria-hidden="true" />

    <section class="auth-stage">
      <div class="auth-brand">
        <span class="auth-brand__mark" />
        <div>
          <p class="auth-brand__kicker">INVITED OPERATOR ACCESS</p>
          <h1>信号灯配时控制与应急通行信控系统</h1>
        </div>
      </div>

      <div class="auth-policy-panel">
        <span class="auth-policy-panel__dot" />
        <div>
          <p>注册策略</p>
          <strong>仅允许邀请码注册</strong>
          <small>请向管理员获取授权邀请码</small>
        </div>
      </div>
    </section>

    <section class="auth-card" aria-labelledby="register-title">
      <div class="auth-card__scan" aria-hidden="true" />
      <div class="auth-card__header">
        <p class="auth-eyebrow">SECURE ENROLLMENT</p>
        <h2 id="register-title">账号注册</h2>
        <p>使用授权邀请码创建系统账号</p>
      </div>

      <form class="auth-form" @submit.prevent="handleSubmit">
        <label class="auth-field">
          <span>用户名</span>
          <input v-model="form.username" autocomplete="username" placeholder="请输入用户名" type="text" />
        </label>

        <label class="auth-field">
          <span>邮箱</span>
          <input v-model="form.email" autocomplete="email" placeholder="name@example.com" type="email" />
        </label>

        <label class="auth-field">
          <span>密码</span>
          <input v-model="form.password" autocomplete="new-password" placeholder="请输入密码" type="password" />
        </label>

        <label class="auth-field">
          <span>确认密码</span>
          <input v-model="form.confirmPassword" autocomplete="new-password" placeholder="请再次输入密码" type="password" />
        </label>

        <label class="auth-field">
          <span>邀请码</span>
          <input v-model="form.inviteCode" placeholder="请输入授权邀请码" type="password" />
        </label>

        <p v-if="form.confirmPassword && !passwordMatched" class="auth-error" role="alert">两次输入的密码不一致</p>
        <p v-if="errorMessage" class="auth-error" role="alert">{{ errorMessage }}</p>

        <button class="auth-submit" :disabled="!canSubmit || loading" type="submit">
          {{ loading ? '注册中...' : '创建账号并进入大屏' }}
        </button>
      </form>

      <footer class="auth-card__footer">
        <span>已有账号？</span>
        <RouterLink to="/login">返回登录</RouterLink>
      </footer>
    </section>
  </main>
</template>

<style src="@/assets/styles/auth.scss" lang="scss"></style>
