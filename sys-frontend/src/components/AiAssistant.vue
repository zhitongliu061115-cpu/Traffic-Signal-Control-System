<script setup lang="ts">
// ================================================================
// AiAssistant — 智能体辅助决策问答
// 自然语言交通调度问答 + 预留 RAG / 大模型接入接口
// ================================================================
import { ref, nextTick } from 'vue'
import { storeToRefs } from 'pinia'
import { useTrafficStore } from '@/stores/traffic'

const store = useTrafficStore()
const { intersections, roads, statistics, emergencyVehicle } = storeToRefs(store)

// ---- 消息类型 ----
interface ChatMessage {
  id: number
  role: 'user' | 'ai'
  text: string
  time: string
}

let msgId = 0
function nowTime(): string {
  const d = new Date()
  const p = (n: number) => String(n).padStart(2, '0')
  return `${p(d.getHours())}:${p(d.getMinutes())}`
}

// ---- 聊天状态 ----
const messages = ref<ChatMessage[]>([
  {
    id: msgId++,
    role: 'ai',
    text: '👋 您好！我是交通调度智能体。您可以向我提问路网拥堵、信号控制、应急绿波、设备状态等问题，我将结合实时数据和知识库为您提供辅助决策建议。',
    time: nowTime(),
  },
])

const input = ref('')
const isThinking = ref(false)
const chatBody = ref<HTMLDivElement | null>(null)

// ---- 快捷提问 ----
const quickAsks = ['哪个路口最拥堵？', '为什么延长绿灯？', '应急车辆怎么走？', '设备离线怎么办？', '生成绿波方案']

// ---- 核心问答引擎（预留真实接口） ----
// ────────────────────────────────────────────────────────────
// TODO: 后续接入真实 LLM / RAG 时，替换此函数实现。
// 当前为基于关键词的模拟回复，补充 store.askAssistant() 的通用匹配。
// 接口签名已设计为 async (userInput: string, context: ChatContext) => Promise<string>
//   context 包含 { intersections, roads, statistics, alerts, emergencyVehicle, congestionTrend }
// ────────────────────────────────────────────────────────────

/** 扩展关键词匹配（补充 store.askAssistant 未覆盖的场景） */
function localMatch(input_: string): string | null {
  const q = input_.trim()

  // 1. 最拥堵路口查询
  if (/哪个.*拥堵|最.*堵|拥堵.*路口|路口.*拥堵/.test(q)) {
    const sorted = [...intersections.value].sort((a, b) => b.congestionIndex - a.congestionIndex)
    const top = sorted[0]
    if (!top) return null
    const count = roads.value.filter((r) => r.from === top.id || r.to === top.id).length
    return `当前 **${top.name}**（${top.id}）拥堵指数最高，达到 **${Math.round(top.congestionIndex)}**，排队 ${top.queueLength} 辆，平均延误 ${Math.round(top.averageDelay)}s。\n\n建议：① 延长当前${top.currentPhase === 'eastwest_straight' ? '东西' : top.currentPhase === 'northsouth_straight' ? '南北' : ''}方向绿灯 15 秒；② 提前放行下游 ${count} 条连接路段；③ 持续监控排队长度变化。`
  }

  // 2. 为什么延长绿灯
  if (/为什么.*延长|延长.*绿灯|绿灯.*延长|为什么.*绿灯/.test(q)) {
    return `延长绿灯的核心原因是**当前车流密度持续上升**。\n\n当路口排队车辆超过阈值（如 15 辆）且拥堵指数 ≥ 60 时，AI 算法会计算延长绿灯的边际收益：每延长 1 秒可多放行约 2-3 辆车，从而降低平均等待时间 ${Math.round(statistics.value.averageWaitTime)}s → 目标减少 30% 以上。系统在保证其他相位不严重拥堵的前提下自动调整配时。`
  }

  // 3. 应急车辆路线
  if (/应急.*车|救护|消防|怎么走|路线|通行/.test(q)) {
    const ev = emergencyVehicle.value
    const route = store.emergencyRoute
      .map((id) => intersections.value.find((it) => it.id === id)?.name ?? id)
      .join(' → ')
    return ev.greenWaveActive
      ? `应急车辆 **${ev.type === 'ambulance' ? '救护车' : '消防车'}**（${ev.id}）当前正在执行绿波通行。\n\n路线：**${route}**\n目标：${ev.destination}\n预计到达：**${ev.eta} 分钟**\n状态：绿波已激活，沿线 ${store.emergencyRoute.length} 个路口将依次优先放行。`
      : `系统建议应急车辆沿 **${route}** 通行。该路线覆盖 ${store.emergencyRoute.length} 个路口，沿途将提前激活绿波信号，预计通行时间从 ${store.compareMetrics.emergencyPassTime.traditional} 分钟缩短至 **${store.compareMetrics.emergencyPassTime.ai} 分钟**。请点击"应急绿波控制"面板启动。`
  }

  // 4. 设备离线
  if (/设备.*离线|离线|故障|通信.*超时/.test(q)) {
    const faults = intersections.value.filter((it) => it.deviceStatus !== 'online')
    if (faults.length === 0) {
      return '当前路网所有信号机 **均在线运行**，设备在线率 **100%**。未检测到离线或故障设备。'
    }
    const names = faults.map((it) => `**${it.name}**（${it.id}，${it.deviceStatus === 'fault' ? '故障' : '离线'}）`).join('\n- ')
    return `当前检测到 **${faults.length} 台**信号设备异常：\n- ${names}\n\n建议：① 立即派单巡检检查硬件连接和通信模块；② 受影响路口 AI 已切换至降级模式，周边路口自动扩大放行窗口；③ 若超过 30 分钟未恢复，建议启用临时移动信号灯。`
  }

  return null
}

/**
 * 统一问答入口。
 * 优先级：本地关键词 > store.askAssistant（mock 关键词）> 默认回复
 */
async function queryAssistant(userInput: string): Promise<string> {
  // ---- 预留：真实 API 接入点 ----
  // 替换此处，直接 return fetch(...) 即可
  // const response = await fetch('/api/assistant/chat', {
  //   method: 'POST', headers: { 'Content-Type': 'application/json' },
  //   body: JSON.stringify({ query: userInput, context: { ... } }),
  // })
  // return (await response.json()).reply

  // 本地关键词
  const local = localMatch(userInput)
  if (local) return local

  // store 关键词（拥堵/绿波/应急/信号/设备/预案）
  const storeReply = store.askAssistant(userInput)
  if (storeReply) return storeReply

  // 默认
  return '已收到您的问题，系统将结合实时交通状态、信号控制策略和知识库进行辅助分析。您可以尝试输入以下关键词获得更精准的建议：**拥堵**、**绿波**、**应急**、**信号**、**设备**、**预案**。'
}

// ---- 发送消息 ----
async function handleSend(): Promise<void> {
  const text = input.value.trim()
  if (!text || isThinking.value) return

  // 用户消息
  messages.value.push({ id: msgId++, role: 'user', text, time: nowTime() })
  input.value = ''

  // 模拟延迟
  isThinking.value = true
  await new Promise((resolve) => setTimeout(resolve, 300))

  // AI 回复
  const reply = await queryAssistant(text)
  messages.value.push({ id: msgId++, role: 'ai', text: reply, time: nowTime() })
  isThinking.value = false

  await nextTick()
  scrollToBottom()
}

// ---- 快捷提问点击 ----
function handleQuickAsk(q: string): void {
  input.value = q
  handleSend()
}

// ---- 回车发送 ----
function handleKeydown(e: KeyboardEvent): void {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    handleSend()
  }
}

// ---- 自动滚动 ----
function scrollToBottom(): void {
  if (chatBody.value) {
    chatBody.value.scrollTop = chatBody.value.scrollHeight
  }
}

// ---- 文本换行渲染（将 \n 转为 <br>，**加粗** 转为 <strong>） ----
function renderText(text: string): string {
  return text
    .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
    .replace(/\n/g, '<br>')
}
</script>

<template>
  <section class="hud-card data-panel-card comp-card">
    <div class="hud-panel-titlebar">
      <div class="titlebar-inner">
        <span class="titlebar-mark" />
        <span class="titlebar-text">智能体辅助决策</span>
        <span class="ai-status">
          <span class="status-dot status-dot--live" />
          <span class="ai-status__text">在线</span>
        </span>
      </div>
    </div>

    <div class="hud-card__content comp-card__body">
      <!-- 聊天消息区域 -->
      <div ref="chatBody" class="ai-chat-body">
        <div
          v-for="m in messages"
          :key="m.id"
          class="ai-chat-msg"
          :class="`ai-chat-msg--${m.role}`"
        >
          <!-- AI 头像 -->
          <div v-if="m.role === 'ai'" class="ai-chat-avatar">AI</div>

          <!-- 气泡 -->
          <div class="ai-chat-bubble" :class="`ai-chat-bubble--${m.role}`">
            <div
              class="ai-chat-bubble__text"
              v-html="renderText(m.text)"
            />
            <div class="ai-chat-bubble__time">{{ m.time }}</div>
          </div>

          <!-- 用户头像 -->
          <div v-if="m.role === 'user'" class="ai-chat-avatar ai-chat-avatar--user">我</div>
        </div>

        <!-- 思考中... -->
        <div v-if="isThinking" class="ai-chat-thinking">
          <span class="ai-chat-thinking__dot" />
          <span class="ai-chat-thinking__dot" />
          <span class="ai-chat-thinking__dot" />
          <span class="ai-chat-thinking__text">AI 分析中…</span>
        </div>
      </div>

      <!-- 快捷提问 -->
      <div class="ai-quick">
        <span
          v-for="q in quickAsks"
          :key="q"
          class="hud-pill hud-pill--neutral ai-quick__pill"
          @click="handleQuickAsk(q)"
        >
          {{ q }}
        </span>
      </div>

      <!-- 输入区 -->
      <div class="ai-input-row">
        <input
          v-model="input"
          class="cyber-input ai-input"
          placeholder="向智能体提问或下达指令…"
          :disabled="isThinking"
          @keydown="handleKeydown"
        />
        <button
          class="cyber-btn ai-send"
          :disabled="isThinking || !input.trim()"
          @click="handleSend"
        >
          {{ isThinking ? '思考中…' : '发送' }}
        </button>
      </div>
    </div>
  </section>
</template>

<style scoped>
/* ===== 面板根容器 ===== */
.comp-card {
  height: 100%;
  display: flex;
  flex-direction: column;
  box-sizing: border-box;
}

.comp-card__body {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
  overflow: hidden;
  box-sizing: border-box;
}

.ai-status {
  margin-left: auto;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  flex: 0 0 auto;
}

.ai-status__text {
  font-size: 12px;
  color: #22d3a0;
}

/* ===== 聊天消息区域（flex:1, 独占剩余高度） ===== */
.ai-chat-body {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
  overflow-y: auto;
  overflow-x: hidden;
  padding: 8px 6px 8px 8px;
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

/* 头像 */
.ai-chat-avatar {
  flex: 0 0 30px;
  height: 30px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 11px;
  font-weight: 700;
  border-radius: 50%;
  color: #051323;
  background: #00d4ff;
  box-shadow: 0 0 10px rgba(0, 212, 255, 0.6);
  user-select: none;
}

.ai-chat-avatar--user {
  background: #7c5cff;
  box-shadow: 0 0 10px rgba(124, 92, 255, 0.6);
  color: #e8f4ff;
}

/* 气泡 — 最大宽度 80%，强制换行 */
.ai-chat-bubble {
  max-width: 80%;
  min-width: 0;
  padding: 9px 12px;
  background: rgba(4, 21, 39, 0.6);
  border: 1px solid rgba(0, 212, 255, 0.16);
  clip-path: polygon(
    0 0, 100% 0, 100% calc(100% - 6px),
    calc(100% - 6px) 100%, 0 100%
  );
  box-sizing: border-box;
  overflow-wrap: break-word;
  word-break: break-word;
}

.ai-chat-bubble--user {
  background: rgba(124, 92, 255, 0.12);
  border-color: rgba(124, 92, 255, 0.3);
}

.ai-chat-bubble__text {
  font-size: 14px;
  line-height: 1.55;
  color: #e8f4ff;
  overflow-wrap: break-word;
  word-break: break-word;
  overflow: hidden;
}

.ai-chat-bubble__text :deep(strong) {
  color: #7af7ff;
  font-weight: 700;
}

.ai-chat-bubble__time {
  margin-top: 4px;
  font-size: 10px;
  color: #5a7595;
  font-family: 'Rajdhani', sans-serif;
}

/* 思考中动画 */
.ai-chat-thinking {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 6px 0;
  flex: 0 0 auto;
}

.ai-chat-thinking__dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #00d4ff;
  animation: ai-dot-bounce 0.8s ease-in-out infinite;
}

.ai-chat-thinking__dot:nth-child(2) { animation-delay: 0.15s; }
.ai-chat-thinking__dot:nth-child(3) { animation-delay: 0.3s; }

@keyframes ai-dot-bounce {
  0%, 80%, 100% { opacity: 0.25; transform: scale(0.7); }
  40% { opacity: 1; transform: scale(1); }
}

.ai-chat-thinking__text {
  margin-left: 4px;
  font-size: 12px;
  color: #5a7595;
}

/* 快捷提问 — 紧凑排列 */
.ai-quick {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
  flex: 0 0 auto;
  padding: 0 2px;
}

.ai-quick__pill {
  cursor: pointer;
  font-size: 10px;
  padding: 3px 8px;
  transition: all 200ms ease;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 100%;
}

.ai-quick__pill:hover {
  border-color: rgba(0, 212, 255, 0.5);
  color: #7af7ff;
  background: rgba(0, 212, 255, 0.1);
}

/* 输入行 — 高度 40-45px，80%/20% 分配 */
.ai-input-row {
  display: flex;
  gap: 6px;
  flex: 0 0 auto;
  height: 42px;
  box-sizing: border-box;
}

.ai-input {
  flex: 0 0 80%;
  font-size: 13px;
  padding: 0 12px;
  height: 100%;
  box-sizing: border-box;
  overflow: hidden;
}

.ai-send {
  flex: 0 0 calc(20% - 6px);
  height: 100%;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  text-transform: none;
  font-size: 13px;
  white-space: nowrap;
  box-sizing: border-box;
  padding: 0 8px;
}

.ai-send:disabled {
  opacity: 0.35;
  cursor: not-allowed;
}
</style>
