import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from "react";
import { ApiClient, ApiClientError, type Session } from "../api/client";
import { Api } from "../api/endpoints";
import type { SessionResponse, ShiftSummaryView } from "../api/types";
import {
  clearDeviceConfig,
  loadDeviceConfig,
  saveDeviceConfig,
  type DeviceConfig,
} from "../config";

const SESSION_KEY = "heysaz.pos.session";
const PRINCIPAL_KEY = "heysaz.pos.principal";

interface AppContextValue {
  config: DeviceConfig | null;
  principal: SessionResponse | null;
  shift: ShiftSummaryView | null;
  api: Api;
  provision: (config: DeviceConfig) => void;
  deprovision: () => void;
  login: (email: string, password: string, mfaCode?: string) => Promise<void>;
  logout: () => void;
  setShift: (shift: ShiftSummaryView | null) => void;
  reloadShift: () => Promise<void>;
}

const AppContext = createContext<AppContextValue | null>(null);

function restore<T>(key: string): T | null {
  try {
    const raw = localStorage.getItem(key);
    return raw ? (JSON.parse(raw) as T) : null;
  } catch {
    return null;
  }
}

export function AppProvider({ children }: { children: ReactNode }) {
  const [config, setConfig] = useState<DeviceConfig | null>(() => loadDeviceConfig());
  const [principal, setPrincipal] = useState<SessionResponse | null>(() =>
    restore<SessionResponse>(PRINCIPAL_KEY),
  );
  const [shift, setShift] = useState<ShiftSummaryView | null>(null);

  // One client/Api for the app's lifetime; base URL follows the device config.
  const clientRef = useRef<ApiClient>(
    new ApiClient(config?.apiBaseUrl || "http://localhost:8080"),
  );
  const apiRef = useRef<Api>(new Api(clientRef.current));

  // Restore a persisted session onto the client, and keep localStorage in step as the
  // client rotates tokens on refresh.
  useEffect(() => {
    const client = clientRef.current;
    const saved = restore<Session>(SESSION_KEY);
    if (saved) client.setSession(saved);
    return client.onSessionChange((session) => {
      if (session) localStorage.setItem(SESSION_KEY, JSON.stringify(session));
      else {
        localStorage.removeItem(SESSION_KEY);
        localStorage.removeItem(PRINCIPAL_KEY);
        setPrincipal(null);
        setShift(null);
      }
    });
  }, []);

  useEffect(() => {
    if (config) clientRef.current.setBaseUrl(config.apiBaseUrl);
  }, [config?.apiBaseUrl]);

  const provision = useCallback((next: DeviceConfig) => {
    saveDeviceConfig(next);
    clientRef.current.setBaseUrl(next.apiBaseUrl);
    setConfig(next);
  }, []);

  const deprovision = useCallback(() => {
    clientRef.current.setSession(null);
    clearDeviceConfig();
    setConfig(null);
  }, []);

  const reloadShift = useCallback(async () => {
    if (!config) return;
    const current = await apiRef.current.currentShift(config.terminalId);
    setShift(current);
  }, [config?.terminalId]);

  const login = useCallback(
    async (email: string, password: string, mfaCode?: string) => {
      if (!config) throw new ApiClientError(0, null, "Terminal is not provisioned");
      const res = await apiRef.current.login({
        email,
        password,
        terminalCode: config.terminalCode,
        mfaCode,
      });
      clientRef.current.setSession({
        accessToken: res.accessToken,
        refreshToken: res.refreshToken,
      });
      if (res.principal) {
        localStorage.setItem(PRINCIPAL_KEY, JSON.stringify(res.principal));
        setPrincipal(res.principal);
      }
      const current = await apiRef.current.currentShift(config.terminalId);
      setShift(current);
    },
    [config?.terminalCode, config?.terminalId],
  );

  const logout = useCallback(() => {
    clientRef.current.setSession(null);
  }, []);

  const value = useMemo<AppContextValue>(
    () => ({
      config,
      principal,
      shift,
      api: apiRef.current,
      provision,
      deprovision,
      login,
      logout,
      setShift,
      reloadShift,
    }),
    [config, principal, shift, provision, deprovision, login, logout, reloadShift],
  );

  return <AppContext.Provider value={value}>{children}</AppContext.Provider>;
}

export function useApp(): AppContextValue {
  const ctx = useContext(AppContext);
  if (!ctx) throw new Error("useApp must be used within AppProvider");
  return ctx;
}
