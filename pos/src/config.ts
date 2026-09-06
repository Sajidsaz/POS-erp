/**
 * Per-device provisioning. A terminal is set up once by an administrator: the API it
 * talks to, and which shop/terminal it *is*. Login authenticates with `terminalCode`,
 * but the checkout and shift APIs address the terminal by its UUID, so both are held
 * here. These values come from the terminal record an admin created in the back office.
 */
export interface DeviceConfig {
  apiBaseUrl: string;
  shopId: string;
  terminalId: string;
  terminalCode: string;
}

const KEY = "heysaz.pos.device";

export function loadDeviceConfig(): DeviceConfig | null {
  try {
    const raw = localStorage.getItem(KEY);
    if (!raw) return null;
    const c = JSON.parse(raw) as DeviceConfig;
    if (!c.apiBaseUrl || !c.shopId || !c.terminalId || !c.terminalCode) return null;
    return c;
  } catch {
    return null;
  }
}

export function saveDeviceConfig(config: DeviceConfig): void {
  localStorage.setItem(KEY, JSON.stringify(config));
}

export function clearDeviceConfig(): void {
  localStorage.removeItem(KEY);
}
