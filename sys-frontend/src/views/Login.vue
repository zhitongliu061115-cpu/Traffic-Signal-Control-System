<script setup lang="ts">
import { computed, onUnmounted, reactive, ref } from 'vue'
import { RouterLink, useRouter } from 'vue-router'

import { login, loginWithCaptcha, sendCaptcha } from '@/api/auth'
import bgVideo from '@/assets/images/bg/bg-video.mp4'
import { saveAuthSession } from '@/utils/authSession'

type LoginMode = 'captcha' | 'password'

const router = useRouter()
const mode = ref<LoginMode>('password')
const loading = ref(false)
const errorMessage = ref('')
const captchaSending = ref(false)
const captchaCooldown = ref(0)
let captchaTimer: ReturnType<typeof setInterval> | null = null

onUnmounted(() => {
  if (captchaTimer) clearInterval(captchaTimer)
})

const form = reactive({
  captcha: '',
  email: '',
  password: '',
  username: '',
})

const modeText = computed(() => (mode.value === 'password' ? '账号密码登录' : '邮箱验证码登录'))

const canSubmit = computed(() => {
  if (mode.value === 'password') {
    return Boolean(form.username.trim() && form.password)
  }

  return Boolean(form.email.trim() && form.captcha.trim())
})

function toggleMode(nextMode: LoginMode): void {
  mode.value = nextMode
  errorMessage.value = ''
}

async function handleSendCaptcha(): Promise<void> {
  if (!form.email.trim() || captchaSending.value || captchaCooldown.value > 0) return

  captchaSending.value = true
  errorMessage.value = ''

  try {
    await sendCaptcha(form.email.trim())
    captchaCooldown.value = 60
    if (captchaTimer) clearInterval(captchaTimer)
    captchaTimer = setInterval(() => {
      captchaCooldown.value -= 1
      if (captchaCooldown.value <= 0 && captchaTimer) {
        clearInterval(captchaTimer)
        captchaTimer = null
      }
    }, 1000)
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '验证码发送失败，请稍后重试'
  } finally {
    captchaSending.value = false
  }
}

async function handleSubmit(): Promise<void> {
  if (!canSubmit.value || loading.value) return

  loading.value = true
  errorMessage.value = ''

  try {
    if (mode.value === 'password') {
      const result = await login({
        email: '',
        password: form.password,
        username: form.username.trim(),
      })
      saveAuthSession(result)
    } else {
      const result = await loginWithCaptcha({
        captcha: form.captcha.trim(),
        email: form.email.trim(),
      })
      saveAuthSession(result)
    }

    await router.push('/')
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '登录失败，请稍后重试'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <main class="auth-shell">
    <video class="auth-video" autoplay muted loop playsinline preload="auto">
      <source :src="bgVideo" type="video/mp4" />
    </video>
    <div class="auth-overlay" />
    <div class="auth-grid" aria-hidden="true" />

    <section class="auth-stage">
      <div class="auth-brand">
        <span class="auth-brand__mark" />
        <div>
          <p class="auth-brand__kicker">TRAFFIC SIGNAL CONTROL</p>
          <h1>信号灯配时控制与应急通行信控系统</h1>
        </div>
      </div>

      <div class="auth-intel-panel">
        <p class="auth-intel-panel__label">实时接入</p>
        <div class="auth-intel-panel__metric">12</div>
        <p>路口信号策略在线巡检，登录后进入路网大屏。</p>
      </div>
    </section>

    <section class="auth-card" aria-labelledby="login-title">
      <div class="auth-card__scan" aria-hidden="true" />
      <div class="auth-card__header">
        <p class="auth-eyebrow">SECURE ACCESS</p>
        <h2 id="login-title">系统登录</h2>
        <p>{{ modeText }}</p>
      </div>

      <div class="auth-tabs" role="tablist" aria-label="登录方式">
        <button
          :aria-selected="mode === 'password'"
          class="auth-tab"
          type="button"
          @click="toggleMode('password')"
        >
          账号密码
        </button>
        <button
          :aria-selected="mode === 'captcha'"
          class="auth-tab"
          type="button"
          @click="toggleMode('captcha')"
        >
          验证码
        </button>
      </div>

      <form class="auth-form" @submit.prevent="handleSubmit">
        <label v-if="mode === 'password'" class="auth-field">
          <span>用户名</span>
          <input v-model="form.username" autocomplete="username" placeholder="请输入用户名" type="text" />
        </label>

        <label v-if="mode === 'captcha'" class="auth-field">
          <span>邮箱</span>
          <input v-model="form.email" autocomplete="email" placeholder="name@example.com" type="email" />
        </label>

        <label v-if="mode === 'password'" class="auth-field">
          <span>密码</span>
          <input v-model="form.password" autocomplete="current-password" placeholder="请输入密码" type="password" />
        </label>

        <label v-else class="auth-field">
          <span>验证码</span>
          <div class="auth-captcha-row">
            <input v-model="form.captcha" autocomplete="one-time-code" placeholder="请输入邮箱验证码" type="text" />
            <button
              class="auth-send-code"
              :disabled="!form.email.trim() || captchaSending || captchaCooldown > 0"
              type="button"
              @click="handleSendCaptcha"
            >
              <span v-if="captchaCooldown > 0">{{ captchaCooldown }}s</span>
              <span v-else>{{ captchaSending ? '发送中' : '发送验证码' }}</span>
            </button>
          </div>
        </label>

        <p v-if="errorMessage" class="auth-error" role="alert">{{ errorMessage }}</p>

        <button class="auth-submit" :disabled="!canSubmit || loading" type="submit">
          {{ loading ? '校验中...' : '进入路网大屏' }}
        </button>
      </form>

      <footer class="auth-card__footer">
        <span>没有账号？</span>
        <RouterLink to="/register">使用邀请码注册</RouterLink>
      </footer>
    </section>
  </main>
</template>

<style src="@/assets/styles/auth.scss" lang="scss"></style>
