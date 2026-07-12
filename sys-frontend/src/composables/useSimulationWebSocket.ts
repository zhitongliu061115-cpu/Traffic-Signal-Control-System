// ================================================================
// useSimulationWebSocket — 仿真帧数据 WebSocket 客户端
//
// 用法：
//   const { connect, disconnect, status, lastFrame } = useSimulationWebSocket()
//   await connect('my-sid')
//   watch(lastFrame, (frame) => { ... })
// ================================================================
import { ref, readonly, onUnmounted } from 'vue'
import type { ControlDecision, SimFrameData, WsMessage } from '@/types/traffic'

type ConnectionStatus = 'disconnected' | 'connecting' | 'connected' | 'error'

/** 订阅请求体 */
interface SubscribeBody {
  topics: string[]
  intervalMs: number
}

/** WebSocket 协议订阅消息 */
interface SubscribeMessage {
  v: string
  type: string
  sid: string
  seq: number
  simTime: number
  sentAt: string
  data: SubscribeBody
}

const DEFAULT_FRAME_INTERVAL_MS = 200
const RECONNECT_DELAY_MS = 3000
const MAX_RECONNECT_ATTEMPTS = 5

export function useSimulationWebSocket() {
  // ---- 响应式状态 ----
  const status = ref<ConnectionStatus>('disconnected')
  const lastFrame = ref<WsMessage<SimFrameData> | null>(null)
  const lastFrameData = ref<SimFrameData | null>(null)
  const lastControlDecision = ref<ControlDecision | null>(null)
  const frameSeq = ref(0)
  const errorMessage = ref<string | null>(null)

  // ---- 内部变量 ----
  let ws: WebSocket | null = null
  let currentSid: string | null = null
  let reconnectAttempts = 0
  let reconnectTimer: ReturnType<typeof setTimeout> | null = null
  let heartbeatTimer: ReturnType<typeof setInterval> | null = null

  // ---- 工具函数 ----

  function deriveWsBase(): string {
    const apiBase = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080'
    return apiBase.replace(/^http/, 'ws')
  }

  function clearTimers(): void {
    if (reconnectTimer !== null) {
      clearTimeout(reconnectTimer)
      reconnectTimer = null
    }
    if (heartbeatTimer !== null) {
      clearInterval(heartbeatTimer)
      heartbeatTimer = null
    }
  }

  function sendSubscribe(): void {
    if (!ws || ws.readyState !== WebSocket.OPEN || !currentSid) return

    const message: SubscribeMessage = {
      v: '1.0',
      type: 'client.subscribe',
      sid: currentSid,
      seq: 1,
      simTime: 0,
      sentAt: new Date().toISOString(),
      data: {
        topics: ['vehicles', 'roads', 'intersections', 'signals', 'metrics'],
        intervalMs: DEFAULT_FRAME_INTERVAL_MS,
      },
    }

    ws.send(JSON.stringify(message))
    console.log('[SimWS] subscription sent', { sid: currentSid, intervalMs: DEFAULT_FRAME_INTERVAL_MS })
  }

  function handleMessage(event: MessageEvent): void {
    try {
      const message = JSON.parse(event.data as string) as WsMessage<SimFrameData>

      if (message.type === 'sim.frame') {
        frameSeq.value = message.seq
        lastFrame.value = message
        lastFrameData.value = message.data

        if (message.seq % 50 === 1 || message.seq <= 2) {
          console.log(
            `[SimWS] frame ${message.seq} | t=${message.simTime?.toFixed(1)}s | ` +
            `vehicles=${message.data?.vehicles?.length ?? 0} ` +
            `signals=${message.data?.signals?.length ?? 0}`,
          )
        }
      } else if (message.type === 'control.decision') {
        const decisions = message.data as unknown as ControlDecision[]
        if (Array.isArray(decisions) && decisions.length > 0) {
          const decision = decisions[0]!
          lastControlDecision.value = decision
          console.log(
            `[SimWS] AI decision | intersection=${decision.intersectionId} ` +
            `phase=${decision.phaseCode} confidence=${(decision.confidence * 100).toFixed(0)}% ` +
            `reason=${decision.reason}`,
          )
        }
      } else {
        // 其他消息类型（如 server.ack, server.error）
        console.log('[SimWS] received', message.type, message)
      }
    } catch (err) {
      console.warn('[SimWS] failed to parse message', err)
    }
  }

  function scheduleReconnect(): void {
    if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
      status.value = 'error'
      errorMessage.value = `重连失败：已达最大重试次数 (${MAX_RECONNECT_ATTEMPTS})`
      console.error('[SimWS] max reconnect attempts reached')
      return
    }

    const delay = Math.min(RECONNECT_DELAY_MS * Math.pow(1.5, reconnectAttempts), 30_000)
    reconnectAttempts++
    status.value = 'connecting'

    console.log(`[SimWS] reconnecting in ${delay}ms (attempt ${reconnectAttempts}/${MAX_RECONNECT_ATTEMPTS})`)

    reconnectTimer = setTimeout(() => {
      if (currentSid) {
        connect(currentSid)
      }
    }, delay)
  }

  // ---- 公开方法 ----

  /** 建立 WebSocket 连接 */
  function connect(sid: string): void {
    // 先断开旧连接
    if (ws) {
      ws.removeEventListener('open', onOpen)
      ws.removeEventListener('message', onMessage)
      ws.removeEventListener('close', onClose)
      ws.removeEventListener('error', onError)
      if (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING) {
        ws.close(1000, 'reconnect')
      }
    }

    currentSid = sid
    status.value = 'connecting'
    errorMessage.value = null

    const url = `${deriveWsBase()}/ws/v1/simulations/${sid}`
    console.log('[SimWS] connecting to', url)

    try {
      ws = new WebSocket(url)
    } catch (err) {
      status.value = 'error'
      errorMessage.value = `无法创建 WebSocket 连接: ${String(err)}`
      scheduleReconnect()
      return
    }

    ws.addEventListener('open', onOpen)
    ws.addEventListener('message', onMessage)
    ws.addEventListener('close', onClose)
    ws.addEventListener('error', onError)
  }

  function onOpen(): void {
    status.value = 'connected'
    reconnectAttempts = 0
    errorMessage.value = null
    console.log('[SimWS] connected')

    // 发送订阅
    sendSubscribe()

    // 心跳保活（每 30s 检查一次连接状态）
    heartbeatTimer = setInterval(() => {
      if (ws?.readyState !== WebSocket.OPEN) {
        console.warn('[SimWS] heartbeat detected dead connection')
        if (ws) {
          ws.close(1006, 'heartbeat timeout')
        }
      }
    }, 30_000)
  }

  function onMessage(event: MessageEvent): void {
    handleMessage(event)
  }

  function onClose(event: CloseEvent): void {
    clearTimers()
    console.log('[SimWS] closed', { code: event.code, reason: event.reason })

    if (status.value !== 'disconnected') {
      scheduleReconnect()
    }
  }

  function onError(event: Event): void {
    console.error('[SimWS] error', event)
    status.value = 'error'
    errorMessage.value = 'WebSocket 连接错误'
  }

  /** 主动断开 */
  function disconnect(): void {
    clearTimers()

    if (reconnectTimer !== null) {
      clearTimeout(reconnectTimer)
      reconnectTimer = null
    }

    reconnectAttempts = MAX_RECONNECT_ATTEMPTS // 阻止自动重连
    status.value = 'disconnected'

    if (ws) {
      ws.removeEventListener('open', onOpen)
      ws.removeEventListener('message', onMessage)
      ws.removeEventListener('close', onClose)
      ws.removeEventListener('error', onError)

      if (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING) {
        ws.close(1000, 'client disconnect')
      }
      ws = null
    }

    currentSid = null
    lastFrame.value = null
    lastFrameData.value = null
    lastControlDecision.value = null
    frameSeq.value = 0
    console.log('[SimWS] disconnected by client')
  }

  /** 检查是否已连接 */
  function isConnected(): boolean {
    return ws?.readyState === WebSocket.OPEN
  }

  // ---- 组件卸载时自动清理 ----
  onUnmounted(() => {
    disconnect()
  })

  // ---- 导出 ----
  return {
    /** 连接状态（只读） */
    status: readonly(status),
    /** 最新帧 WsMessage（含 envelope） */
    lastFrame: readonly(lastFrame),
    /** 最新帧 SimFrameData（纯数据） */
    lastFrameData: readonly(lastFrameData),
    /** 最新 AI 控制决策 */
    lastControlDecision: readonly(lastControlDecision),
    /** 当前帧序号 */
    frameSeq: readonly(frameSeq),
    /** 错误信息 */
    errorMessage: readonly(errorMessage),

    // 方法
    connect,
    disconnect,
    isConnected,
  }
}
