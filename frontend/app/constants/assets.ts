/** 编辑器上传后的临时引用，保存时由文档资源解析器完成业务绑定。 */
export const ASSET_REFERENCE_PREFIX = 'asset://'
/** 统一给签名地址预留 30 秒传输余量；缓存判断与刷新调度必须使用同一个值。 */
export const ASSET_URL_REFRESH_LEAD_MS = 30_000
