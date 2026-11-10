import { useAuthState } from '../composables/useAuthState'
import { AUTH_ACCESS } from '../constants/auth'

const endpoint = '/api/v1/admin/agent/settings'

export type AgentRuntime = Record<string, string | number | boolean>

interface AgentSettings {
  revision: number
  persona: string
  modelProvider: string
  chatKeyConfigured: boolean
  embeddingKeyConfigured: boolean
  runtime: AgentRuntime
}

export function useAgentSettingsApi() {
  const { request } = useAuthState()
  return {
    read: () => request<AgentSettings>(endpoint, {}, AUTH_ACCESS.OWNER, '读取数字人配置失败'),
    save: (
      expectedRevision: number,
      persona: string,
      runtime: AgentRuntime,
      keys: { chatApiKey?: string; embeddingApiKey?: string },
    ) =>
      request<AgentSettings>(
        endpoint,
        { method: 'PUT', body: { expectedRevision, persona, runtime, ...keys } },
        AUTH_ACCESS.OWNER,
        '保存数字人配置失败',
      ),
  }
}
