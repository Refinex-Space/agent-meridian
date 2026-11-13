/**
 * 运行时配置：控制台是静态资源，身份提供方的地址与客户端随部署环境变化，启动时从同源的 `/config.json` 读取（ADR-0055），
 * 同一份构建产物用于所有环境。开发环境的默认值在 `public/config.json`（本地 Keycloak 的 `meridian-dev` realm）。
 */

export interface ConsoleConfig {
  oidc: {
    /** 签发方（OIDC 发现文档所在的地址），例如 `http://localhost:18082/realms/meridian-dev`。 */
    authority: string;
    /** 控制台的公开客户端（授权码加 PKCE）。 */
    clientId: string;
    /** 申请的范围，默认 `openid profile email`。 */
    scope?: string;
  };
}

export class ConfigError extends Error {}

function isConfig(value: unknown): value is ConsoleConfig {
  if (typeof value !== 'object' || value === null || !('oidc' in value)) return false;
  const { oidc } = value;
  return (
    typeof oidc === 'object' &&
    oidc !== null &&
    typeof (oidc as Record<string, unknown>)['authority'] === 'string' &&
    typeof (oidc as Record<string, unknown>)['clientId'] === 'string'
  );
}

/** 读取并校验运行时配置；缺失或格式不对时抛出 ConfigError，由启动页显示原因。 */
export async function loadConfig(): Promise<ConsoleConfig> {
  const response = await fetch('/config.json', { cache: 'no-store' });
  if (!response.ok) throw new ConfigError(`读取 /config.json 失败（HTTP ${response.status}）`);
  const value: unknown = await response.json();
  if (!isConfig(value)) throw new ConfigError('/config.json 缺少 oidc.authority 或 oidc.clientId');
  return value;
}
