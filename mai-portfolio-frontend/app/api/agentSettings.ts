import { useAuthState } from '../composables/useAuthState'
import { AUTH_ACCESS } from '../constants/auth'

const endpoint = '/api/v1/admin/agent/settings/catalog'
export type AgentModelType = 1 | 2

export interface AgentProvider {
  id: number
  title: string
  providerCode: string
  apiProtocol: 1
  baseUrl: string
  walletKey: string | null
  keyConfigured: boolean
  revision: number
}
export interface AgentProviderWrite {
  title: string
  providerCode: string
  apiProtocol: 1
  baseUrl: string
  walletKey: string | null
  apiKey?: string
  expectedRevision?: number
}
export interface AgentModel {
  id: number
  modelProviderId: number
  modelKey: string
  modelNickname: string
  modelType: AgentModelType
  config: { maxOutputTokens?: number; dimensions?: number | null }
  isActive: boolean
  revision: number
}
export type AgentModelWrite = Omit<AgentModel, 'id' | 'revision'> & { expectedRevision?: number }
export interface AgentModelRoute {
  type: AgentModelType
  modelIds: number[]
}
export interface AgentModelCatalog {
  providers: AgentProvider[]
  models: AgentModel[]
  routes: AgentModelRoute[]
}

export interface AgentModelProbe {
  ok: boolean
  code: string | null
  dimensions: number | null
}
export interface AgentPrompt {
  id: number
  promptKey: string
  description: string
  activeVersionId: number | null
  updatedAt: string
}
export interface AgentPromptVersion {
  id: number
  promptConfigId: number
  versionNo: number
  content: string
  createdAt: string
}

export interface AgentWallet {
  providerCode: string
  walletKey: string | null
  providerIds: number[]
  status: 'available' | 'stale' | 'unavailable' | 'unsupported'
  balances: { currency: string; totalBalance: string }[]
  queriedAt: string | null
}

export function useAgentSettingsApi() {
  const { request } = useAuthState()
  return {
    wallets: () =>
      request<{ wallets: AgentWallet[] }>(
        `${endpoint}/wallets`,
        {},
        AUTH_ACCESS.OWNER,
        '读取供应商钱包失败',
      ),
    testModel: (id: number) =>
      request<AgentModelProbe>(
        `${endpoint}/models/${id}/test`,
        { method: 'POST', body: {} },
        AUTH_ACCESS.OWNER,
        '模型测试失败',
      ),
    prompts: () =>
      request<{ prompts: AgentPrompt[] }>(
        `${endpoint}/prompts`,
        {},
        AUTH_ACCESS.OWNER,
        '读取 Prompt 失败',
      ),
    versions: (id: number) =>
      request<{ versions: AgentPromptVersion[] }>(
        `${endpoint}/prompts/${id}/versions`,
        {},
        AUTH_ACCESS.OWNER,
        '读取历史版本失败',
      ),
    publish: (id: number, content: string, expectedActiveVersionId: number | null) =>
      request<AgentPromptVersion>(
        `${endpoint}/prompts/${id}/versions`,
        { method: 'POST', body: { content, expectedActiveVersionId } },
        AUTH_ACCESS.OWNER,
        '发布 Prompt 失败',
      ),
    activate: (id: number, versionId: number, expectedActiveVersionId: number | null) =>
      request<AgentPrompt>(
        `${endpoint}/prompts/${id}/active-version`,
        { method: 'PUT', body: { versionId, expectedActiveVersionId } },
        AUTH_ACCESS.OWNER,
        '切换 Prompt 版本失败',
      ),
    read: () => request<AgentModelCatalog>(endpoint, {}, AUTH_ACCESS.OWNER, '读取模型目录失败'),
    saveProvider: (body: AgentProviderWrite, id?: number) =>
      request<AgentProvider>(
        id === undefined ? `${endpoint}/providers` : `${endpoint}/providers/${id}`,
        { method: id === undefined ? 'POST' : 'PUT', body },
        AUTH_ACCESS.OWNER,
        '保存供应商失败',
      ),
    saveModel: (body: AgentModelWrite, id?: number) =>
      request<AgentModel>(
        id === undefined ? `${endpoint}/models` : `${endpoint}/models/${id}`,
        { method: id === undefined ? 'POST' : 'PUT', body },
        AUTH_ACCESS.OWNER,
        '保存模型失败',
      ),
    saveRoute: (type: AgentModelType, modelIds: number[], expectedModelIds: number[]) =>
      request<AgentModelRoute>(
        `${endpoint}/routes/${type}`,
        { method: 'PUT', body: { modelIds, expectedModelIds } },
        AUTH_ACCESS.OWNER,
        '保存执行链失败',
      ),
  }
}
