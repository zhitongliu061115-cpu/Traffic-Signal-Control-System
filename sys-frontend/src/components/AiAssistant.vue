<script setup lang="ts">
// ================================================================
// AiAssistant — 悬浮式智能体辅助决策问答
// 通过后端代理连接阿里百炼，失败时使用本地交通规则兜底
// ================================================================
import { computed, nextTick, ref } from 'vue'
import { storeToRefs } from 'pinia'
import { ChatDotRound, Close, Promotion } from '@element-plus/icons-vue'
import { useTrafficStore } from '@/stores/traffic'

const store = useTrafficStore()
const { intersections, roads, statistics, emergencyVehicle } = storeToRefs(store)

interface ChatMessage {
  id: number
  role: 'user' | 'ai'
  text: string
  time: string
}

interface ApiResponse<T> {
  success: boolean
  message: string
  data: T | null
}

interface AgentChatResponse {
  reply: string
  sessionId?: string | null
  source: 'bailian' | 'config' | string
  fallback: boolean
}

let msgId = 0
function nowTime(): string {
  const d = new Date()
  const p = (n: number) => String(n).padStart(2, '0')
  return `${p(d.getHours())}:${p(d.getMinutes())}`
}

const apiBaseUrl = computed(() => {
  const configured = import.meta.env.VITE_API_BASE_URL?.trim()
  return (configured || 'http://localhost:8080').replace(/\/$/, '')
})

const isOpen = ref(false)
const input = ref('')
const isThinking = ref(false)
const chatBody = ref<HTMLDivElement | null>(null)
const inputRef = ref<HTMLInputElement | null>(null)
const sessionId = ref<string | null>(null)
const assistantStatusText = ref('百炼待连接')

const messages = ref<ChatMessage[]>([
  {
    id: msgId++,
    role: 'ai',
    text: '您好，我是城市交通信号调度辅助决策智能体。请询问路网状态、拥堵成因、信号调度建议或应急绿波方案；涉及控制动作时，我只给出建议与待确认方案。',
    time: nowTime(),
  },
])

const quickAsks = ['当前路网状态', '哪个路口最拥堵？', '生成调度建议', '应急车辆怎么走？', '解释 Traffic-R1']

function openAssistant(): void {
  isOpen.value = true
  void nextTick(() => inputRef.value?.focus())
}

function closeAssistant(): void {
  isOpen.value = false
}

function toggleAssistant(): void {
  if (isOpen.value) {
    closeAssistant()
  } else {
    openAssistant()
  }
}

function buildTrafficContext(): Record<string, unknown> {
  const topIntersections = [...intersections.value]
    .sort((a, b) => b.congestionIndex - a.congestionIndex)
    .slice(0, 5)
    .map((it) => ({
      id: it.id,
      name: it.name,
      congestionIndex: Math.round(it.congestionIndex),
      queueLength: it.queueLength,
      averageDelay: Math.round(it.averageDelay),
      currentPhase: it.currentPhase,
      deviceStatus: it.deviceStatus,
    }))

  return {
    statistics: statistics.value,
    topIntersections,
    roadCount: roads.value.length,
    emergencyVehicle: emergencyVehicle.value,
    emergencyRoute: store.emergencyRoute,
    compareMetrics: store.compareMetrics,
  }
}

async function requestBailianAssistant(userInput: string): Promise<string> {
  const response = await fetch(`${apiBaseUrl.value}/api/v1/agent/chat`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      message: userInput,
      sessionId: sessionId.value,
      context: buildTrafficContext(),
    }),
  })

  if (!response.ok) {
    throw new Error(`HTTP ${response.status}`)
  }

  const payload = (await response.json()) as ApiResponse<AgentChatResponse>
  if (!payload.success || !payload.data?.reply) {
    throw new Error(payload.message || '百炼响应为空')
  }

  sessionId.value = payload.data.sessionId ?? sessionId.value
  assistantStatusText.value = payload.data.fallback ? '配置待完成' : '百炼在线'
  return payload.data.reply
}

function localMatch(input_: string): string | null {
  const q = input_.trim()

  if (/哪个.*拥堵|最.*堵|拥堵.*路口|路口.*拥堵/.test(q)) {
    const sorted = [...intersections.value].sort((a, b) => b.congestionIndex - a.congestionIndex)
    const top = sorted[0]
    if (!top) return null
    const count = roads.value.filter((r) => r.from === top.id || r.to === top.id).length
    return `当前 **${top.name}**（${top.id}）拥堵指数最高，达到 **${Math.round(top.congestionIndex)}**，排队 ${top.queueLength} 辆，平均延误 ${Math.round(top.averageDelay)}s。\n\n建议：延长当前${top.currentPhase === 'eastwest_straight' ? '东西' : top.currentPhase === 'northsouth_straight' ? '南北' : ''}方向绿灯 15 秒，提前放行下游 ${count} 条连接路段，并持续监控排队长度变化。\n\n状态：建议-待人工确认。`
  }

  if (/为什么.*延长|延长.*绿灯|绿灯.*延长|为什么.*绿灯/.test(q)) {
    return `延长绿灯的核心原因是**当前车流密度持续上升**。\n\n当路口排队车辆超过阈值且拥堵指数较高时，系统会评估延长绿灯的边际收益，以降低平均等待时间。当前平均等待时间约 ${Math.round(statistics.value.averageWaitTime)}s；该说明来自本地实时状态兜底分析，最终建议仍需后端校验与人工确认。`
  }

  if (/应急.*车|救护|消防|怎么走|路线|通行/.test(q)) {
    const ev = emergencyVehicle.value
    const route = store.emergencyRoute
      .map((id) => intersections.value.find((it) => it.id === id)?.name ?? id)
      .join(' → ')
    return ev.greenWaveActive
      ? `应急车辆 **${ev.type === 'ambulance' ? '救护车' : '消防车'}**（${ev.id}）正在执行绿波通行。\n\n路线：**${route}**\n目标：${ev.destination}\n预计到达：**${ev.eta} 分钟**\n状态：建议-待人工确认，沿线 ${store.emergencyRoute.length} 个路口应依次优先放行。`
      : `建议应急车辆沿 **${route}** 通行。该路线覆盖 ${store.emergencyRoute.length} 个路口，沿途可提前形成绿波窗口，预计通行时间从 ${store.compareMetrics.emergencyPassTime.traditional} 分钟缩短至 **${store.compareMetrics.emergencyPassTime.ai} 分钟**。\n\n状态：建议-待人工确认，请由人工确认后再发起控制流程。`
  }

  if (/设备.*离线|离线|故障|通信.*超时/.test(q)) {
    const faults = intersections.value.filter((it) => it.deviceStatus !== 'online')
    if (faults.length === 0) {
      return '当前路网所有信号机 **均在线运行**，设备在线率 **100%**。未检测到离线或故障设备。'
    }
    const names = faults.map((it) => `**${it.name}**（${it.id}，${it.deviceStatus === 'fault' ? '故障' : '离线'}）`).join('\n- ')
    return `当前检测到 **${faults.length} 台**信号设备异常：\n- ${names}\n\n建议：立即派单巡检通信模块与硬件连接；受影响路口切换降级控制；若超过 30 分钟未恢复，建议启用临时移动信号灯。\n\n状态：建议-待人工确认。`
  }

  return null
}

async function queryAssistant(userInput: string): Promise<string> {
  try {
    return await requestBailianAssistant(userInput)
  } catch {
    assistantStatusText.value = '本地兜底'
  }

  const local = localMatch(userInput)
  if (local) {
    return `${local}\n\n注：百炼服务暂不可用，已使用本地兜底分析。`
  }

  const storeReply = store.askAssistant(userInput)
  if (storeReply) {
    return `${storeReply}\n\n注：百炼服务暂不可用，已使用本地兜底分析。`
  }

  return '暂时无法连接百炼智能体，且本地兜底规则未覆盖该问题。请检查后端服务、BAILIAN_API_KEY 配置和百炼应用 ID 后重试。'
}

async function handleSend(): Promise<void> {
  const text = input.value.trim()
  if (!text || isThinking.value) return

  messages.value.push({ id: msgId++, role: 'user', text, time: nowTime() })
  input.value = ''
  isThinking.value = true

  const reply = await queryAssistant(text)
  messages.value.push({ id: msgId++, role: 'ai', text: reply, time: nowTime() })
  isThinking.value = false

  await nextTick()
  scrollToBottom()
}

function handleQuickAsk(q: string): void {
  if (isThinking.value) return
  input.value = q
  void handleSend()
}

function handleKeydown(e: KeyboardEvent): void {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    void handleSend()
  }
}

function scrollToBottom(): void {
  if (chatBody.value) {
    chatBody.value.scrollTop = chatBody.value.scrollHeight
  }
}

function escapeHtml(text: string): string {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}

function renderText(text: string): string {
  return escapeHtml(text)
    .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
    .replace(/\n/g, '<br>')
}
</script>

<template>
  <div class="ai-assistant-float" :class="{ 'is-open': isOpen }">
    <button
      class="ai-float-trigger"
      type="button"
      :aria-expanded="isOpen"
      aria-controls="ai-assistant-panel"
      title="智能体辅助决策"
      @click="toggleAssistant"
    >
      <span class="ai-float-trigger__halo" aria-hidden="true" />
      <span class="ai-float-trigger__icon" aria-hidden="true">
        <ChatDotRound />
      </span>
      <span class="ai-float-trigger__text">AI 决策</span>
      <span class="ai-float-trigger__dot" aria-hidden="true" />
    </button>

    <aside
      v-if="isOpen"
      id="ai-assistant-panel"
      class="hud-card data-panel-card ai-float-panel"
      aria-label="智能体辅助决策问答窗口"
    >
      <header class="ai-panel-head">
        <div class="ai-panel-title">
          <span class="titlebar-mark" />
          <div>
            <div class="ai-panel-title__main">智能体辅助决策</div>
            <div class="ai-panel-title__sub">{{ assistantStatusText }}</div>
          </div>
        </div>
        <button class="ai-icon-btn" type="button" title="关闭" @click="closeAssistant">
          <Close />
        </button>
      </header>

      <div class="ai-panel-body">
        <div ref="chatBody" class="ai-chat-body">
          <div
            v-for="m in messages"
            :key="m.id"
            class="ai-chat-msg"
            :class="`ai-chat-msg--${m.role}`"
          >
            <div v-if="m.role === 'ai'" class="ai-chat-avatar">AI</div>
            <div class="ai-chat-bubble" :class="`ai-chat-bubble--${m.role}`">
              <div class="ai-chat-bubble__text" v-html="renderText(m.text)" />
              <div class="ai-chat-bubble__time">{{ m.time }}</div>
            </div>
            <div v-if="m.role === 'user'" class="ai-chat-avatar ai-chat-avatar--user">我</div>
          </div>

          <div v-if="isThinking" class="ai-chat-thinking">
            <span class="ai-chat-thinking__dot" />
            <span class="ai-chat-thinking__dot" />
            <span class="ai-chat-thinking__dot" />
            <span class="ai-chat-thinking__text">AI 分析中</span>
          </div>
        </div>

        <div class="ai-quick" aria-label="快捷提问">
          <button
            v-for="q in quickAsks"
            :key="q"
            class="hud-pill hud-pill--neutral ai-quick__pill"
            type="button"
            :disabled="isThinking"
            @click="handleQuickAsk(q)"
          >
            {{ q }}
          </button>
        </div>

        <div class="ai-input-row">
          <input
            ref="inputRef"
            v-model="input"
            class="cyber-input ai-input"
            placeholder="向智能体提问或申请建议..."
            :disabled="isThinking"
            @keydown="handleKeydown"
          />
          <button
            class="cyber-btn ai-send"
            :disabled="isThinking || !input.trim()"
            type="button"
            title="发送"
            @click="handleSend"
          >
            <Promotion aria-hidden="true" />
            <span>{{ isThinking ? '分析中' : '发送' }}</span>
          </button>
        </div>
      </div>
    </aside>
  </div>
</template>

<style scoped>
.ai-assistant-float {
  position: fixed;
  right: 18px;
  bottom: 18px;
  z-index: 80;
  pointer-events: none;
}

.ai-float-trigger,
.ai-float-panel {
  pointer-events: auto;
}

.ai-float-trigger {
  position: relative;
  display: inline-flex;
  width: 72px;
  min-height: 96px;
  align-items: center;
  justify-content: center;
  flex-direction: column;
  gap: 7px;
  border: 1px solid rgba(122, 247, 255, 0.72);
  color: #e8f4ff;
  background:
    linear-gradient(180deg, rgba(7, 30, 54, 0.92), rgba(2, 8, 23, 0.72)),
    rgba(0, 212, 255, 0.1);
  box-shadow: 0 0 22px rgba(0, 212, 255, 0.22), inset 0 0 14px rgba(122, 247, 255, 0.08);
  clip-path: polygon(14px 0, 100% 0, 100% calc(100% - 14px), calc(100% - 14px) 100%, 0 100%, 0 14px);
  cursor: pointer;
  transition:
    transform 180ms ease,
    border-color 180ms ease,
    color 180ms ease,
    box-shadow 180ms ease;
}

.ai-float-trigger:hover {
  color: #7af7ff;
  border-color: #7af7ff;
  transform: translateY(-2px);
  box-shadow: 0 0 28px rgba(0, 212, 255, 0.34), inset 0 0 18px rgba(122, 247, 255, 0.12);
}

.ai-float-trigger:focus-visible {
  outline: 2px solid #7af7ff;
  outline-offset: 4px;
}

.ai-float-trigger__halo {
  position: absolute;
  inset: 8px;
  border: 1px solid rgba(0, 212, 255, 0.16);
  clip-path: inherit;
}

.ai-float-trigger__icon {
  display: inline-flex;
  width: 26px;
  height: 26px;
  color: #7af7ff;
}

.ai-float-trigger__icon svg,
.ai-icon-btn svg,
.ai-send svg {
  width: 100%;
  height: 100%;
}

.ai-float-trigger__text {
  position: relative;
  z-index: 1;
  writing-mode: vertical-rl;
  text-orientation: mixed;
  font-family: 'AlimamaShuHeiTi', 'Microsoft YaHei', sans-serif;
  font-size: 14px;
  font-weight: 800;
  letter-spacing: 0;
}

.ai-float-trigger__dot {
  width: 7px;
  height: 7px;
  border-radius: 999px;
  background: #22d3a0;
  box-shadow: 0 0 14px rgba(34, 211, 160, 0.85);
}

.ai-float-panel {
  position: absolute;
  right: 0;
  bottom: 88px;
  width: min(390px, calc(100vw - 32px));
  height: min(690px, calc(100vh - 128px));
  display: flex;
  flex-direction: column;
  overflow: hidden;
  box-sizing: border-box;
  animation: ai-panel-enter 180ms ease-out;
}

@keyframes ai-panel-enter {
  from {
    opacity: 0;
    transform: translateY(10px) scale(0.98);
  }
  to {
    opacity: 1;
    transform: translateY(0) scale(1);
  }
}

.ai-panel-head {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 13px 14px 10px;
  border-bottom: 1px solid rgba(0, 212, 255, 0.14);
}

.ai-panel-title {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 9px;
}

.ai-panel-title__main {
  color: #e8f4ff;
  font-family: 'AlimamaShuHeiTi', 'Microsoft YaHei', sans-serif;
  font-size: 16px;
  font-weight: 800;
  line-height: 1.2;
}

.ai-panel-title__sub {
  margin-top: 4px;
  color: #22d3a0;
  font-size: 12px;
  line-height: 1;
}

.ai-icon-btn {
  display: inline-flex;
  width: 34px;
  height: 34px;
  align-items: center;
  justify-content: center;
  border: 1px solid rgba(0, 212, 255, 0.22);
  color: rgba(207, 250, 254, 0.86);
  background: rgba(8, 47, 73, 0.26);
  clip-path: polygon(8px 0, 100% 0, 100% calc(100% - 8px), calc(100% - 8px) 100%, 0 100%, 0 8px);
  cursor: pointer;
  transition:
    color 180ms ease,
    border-color 180ms ease,
    background-color 180ms ease;
}

.ai-icon-btn:hover {
  color: #7af7ff;
  border-color: rgba(122, 247, 255, 0.72);
  background: rgba(0, 212, 255, 0.1);
}

.ai-panel-body {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  gap: 9px;
  padding: 10px;
  overflow: hidden;
}

.ai-chat-body {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
  overflow-y: auto;
  overflow-x: hidden;
  padding: 2px 4px 2px 2px;
  box-sizing: border-box;
}

.ai-chat-msg {
  display: flex;
  gap: 9px;
  flex: 0 0 auto;
}

.ai-chat-msg--user {
  flex-direction: row-reverse;
}

.ai-chat-avatar {
  flex: 0 0 30px;
  height: 30px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  color: #051323;
  background: #00d4ff;
  box-shadow: 0 0 10px rgba(0, 212, 255, 0.6);
  font-size: 11px;
  font-weight: 800;
  user-select: none;
}

.ai-chat-avatar--user {
  color: #e8f4ff;
  background: #7c5cff;
  box-shadow: 0 0 10px rgba(124, 92, 255, 0.6);
}

.ai-chat-bubble {
  max-width: 82%;
  min-width: 0;
  padding: 9px 11px;
  border: 1px solid rgba(0, 212, 255, 0.16);
  color: #e8f4ff;
  background: rgba(4, 21, 39, 0.64);
  clip-path: polygon(0 0, 100% 0, 100% calc(100% - 6px), calc(100% - 6px) 100%, 0 100%);
  box-sizing: border-box;
  overflow-wrap: break-word;
  word-break: break-word;
}

.ai-chat-bubble--user {
  border-color: rgba(124, 92, 255, 0.32);
  background: rgba(124, 92, 255, 0.14);
}

.ai-chat-bubble__text {
  color: #e8f4ff;
  font-size: 13px;
  line-height: 1.55;
  overflow-wrap: break-word;
  word-break: break-word;
  text-wrap: pretty;
}

.ai-chat-bubble__text :deep(strong) {
  color: #7af7ff;
  font-weight: 800;
}

.ai-chat-bubble__time {
  margin-top: 5px;
  color: #5a7595;
  font-family: 'Rajdhani', 'DINPro', monospace;
  font-size: 10px;
}

.ai-chat-thinking {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  gap: 4px;
  padding: 6px 0;
}

.ai-chat-thinking__dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #00d4ff;
  animation: ai-dot-bounce 0.8s ease-in-out infinite;
}

.ai-chat-thinking__dot:nth-child(2) {
  animation-delay: 0.15s;
}

.ai-chat-thinking__dot:nth-child(3) {
  animation-delay: 0.3s;
}

@keyframes ai-dot-bounce {
  0%,
  80%,
  100% {
    opacity: 0.25;
    transform: scale(0.7);
  }
  40% {
    opacity: 1;
    transform: scale(1);
  }
}

.ai-chat-thinking__text {
  margin-left: 4px;
  color: #8da8c5;
  font-size: 12px;
}

.ai-quick {
  display: flex;
  flex: 0 0 auto;
  flex-wrap: wrap;
  gap: 6px;
}

.ai-quick__pill {
  max-width: 100%;
  border: 1px solid rgba(0, 212, 255, 0.2);
  cursor: pointer;
  font-size: 11px;
  overflow: hidden;
  padding: 4px 8px;
  text-overflow: ellipsis;
  transition:
    color 180ms ease,
    border-color 180ms ease,
    background-color 180ms ease;
  white-space: nowrap;
}

.ai-quick__pill:hover:not(:disabled) {
  color: #7af7ff;
  border-color: rgba(0, 212, 255, 0.5);
  background: rgba(0, 212, 255, 0.1);
}

.ai-quick__pill:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.ai-input-row {
  flex: 0 0 auto;
  display: grid;
  grid-template-columns: minmax(0, 1fr) 86px;
  gap: 7px;
  height: 42px;
}

.ai-input {
  width: 100%;
  height: 100%;
  box-sizing: border-box;
  overflow: hidden;
  padding: 0 12px;
  font-size: 13px;
}

.ai-send {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 5px;
  min-width: 0;
  height: 100%;
  box-sizing: border-box;
  padding: 0 8px;
  font-size: 13px;
  text-transform: none;
  white-space: nowrap;
}

.ai-send svg {
  flex: 0 0 15px;
  width: 15px;
  height: 15px;
}

.ai-send:disabled {
  opacity: 0.35;
  cursor: not-allowed;
}

@media (max-width: 720px) {
  .ai-assistant-float {
    right: 12px;
    bottom: 12px;
  }

  .ai-float-trigger {
    width: 64px;
    min-height: 88px;
  }

  .ai-float-panel {
    right: 0;
    bottom: 82px;
    width: calc(100vw - 24px);
    height: min(640px, calc(100vh - 112px));
  }

  .ai-input-row {
    grid-template-columns: minmax(0, 1fr) 78px;
  }
}

@media (prefers-reduced-motion: reduce) {
  *,
  *::before,
  *::after {
    animation-duration: 0.01ms !important;
    animation-iteration-count: 1 !important;
    transition-duration: 0.01ms !important;
  }
}
</style>
