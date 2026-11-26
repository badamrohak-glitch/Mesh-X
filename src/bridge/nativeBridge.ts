export const BRIDGE_PROTOCOL_VERSION = 1;

export type BridgeErrorCode =
  | 'INVALID_JSON'
  | 'INVALID_ENVELOPE'
  | 'UNSUPPORTED_VERSION'
  | 'UNKNOWN_COMMAND'
  | 'INVALID_PAYLOAD'
  | 'INTERNAL_ERROR'
  | 'BRIDGE_UNAVAILABLE'
  | 'TIMEOUT';

export type BridgeRequestType = 'BRIDGE_PING' | 'GET_RUNTIME_INFO' | string;

export interface BridgeRequest<TPayload = Record<string, unknown>> {
  version: number;
  kind: 'request';
  requestId: string;
  type: BridgeRequestType;
  payload: TPayload;
  timestamp: number;
}

export interface BridgeError {
  code: BridgeErrorCode;
  message: string;
  details?: Record<string, unknown>;
}

export interface BridgeResponse<TData = Record<string, unknown>> {
  version: number;
  kind: 'response';
  requestId: string;
  type: string;
  ok: boolean;
  data?: TData;
  error?: BridgeError;
  timestamp: number;
}

export interface BridgeEvent<TPayload = Record<string, unknown>> {
  version: number;
  kind: 'event';
  eventId: string;
  type: string;
  payload: TPayload;
  timestamp: number;
}

export interface PingData {
  pong: boolean;
  echo?: string;
  receivedAtMs: number;
  nativeTimestampMs: number;
  uptimeMs: number;
}

export interface RuntimeInfoData {
  protocolVersion: number;
  platform: string;
  manufacturer: string;
  model: string;
  androidRelease: string;
  sdkInt: number;
  appId: string;
  appVersionName: string;
  appVersionCode: number;
  isDebugBuild: boolean;
  availableCommands: string[];
}

export interface BridgeLogEntry {
  id: string;
  timestamp: number;
  direction: 'request' | 'event';
  type: string;
  ok: boolean;
  detail: string;
  latencyMs?: number;
}

declare global {
  interface Window {
    ReactNativeWebView?: {
      postMessage: (message: string) => void;
    };
    __MESHX_BRIDGE_RECEIVE__?: (rawJson: string) => void;
  }
}

interface PendingRequest {
  type: string;
  startedAt: number;
  resolve: (response: BridgeResponse<any>) => void;
  timerId: number;
}

const pendingRequests = new Map<string, PendingRequest>();
const eventListeners = new Set<(event: BridgeEvent) => void>();
const logListeners = new Set<(entry: BridgeLogEntry) => void>();
let requestSequence = 0;

function emitLog(entry: BridgeLogEntry) {
  logListeners.forEach((listener) => listener(entry));
}

function handleIncomingBridgeMessage(rawJson: string) {
  let parsed: BridgeResponse | BridgeEvent | null = null;
  try {
    parsed = JSON.parse(rawJson) as BridgeResponse | BridgeEvent;
  } catch {
    emitLog({
      id: `parse_err_${Date.now()}`,
      timestamp: Date.now(),
      direction: 'event',
      type: 'BRIDGE_PARSE_ERROR',
      ok: false,
      detail: 'Received malformed JSON from native shell',
    });
    return;
  }

  if (!parsed || typeof parsed !== 'object') {
    return;
  }

  if (parsed.kind === 'response' && typeof parsed.requestId === 'string') {
    const pending = pendingRequests.get(parsed.requestId);
    if (!pending) {
      return;
    }
    window.clearTimeout(pending.timerId);
    pendingRequests.delete(parsed.requestId);

    const latencyMs = Math.max(0, Date.now() - pending.startedAt);
    emitLog({
      id: parsed.requestId,
      timestamp: parsed.timestamp || Date.now(),
      direction: 'request',
      type: parsed.type,
      ok: parsed.ok,
      latencyMs,
      detail: parsed.ok
        ? 'OK'
        : `${parsed.error?.code ?? 'ERROR'}: ${parsed.error?.message ?? 'Failed'}`,
    });
    pending.resolve(parsed);
    return;
  }

  if (parsed.kind === 'event' && typeof parsed.type === 'string') {
    emitLog({
      id: parsed.eventId || `evt_${Date.now()}`,
      timestamp: parsed.timestamp || Date.now(),
      direction: 'event',
      type: parsed.type,
      ok: true,
      detail: 'Native event received',
    });
    eventListeners.forEach((listener) => listener(parsed as BridgeEvent));
  }
}

export function installBridgeReceiver(): void {
  if (typeof window === 'undefined') {
    return;
  }
  window.__MESHX_BRIDGE_RECEIVE__ = handleIncomingBridgeMessage;
}

installBridgeReceiver();

export function isAndroidWebViewAvailable(): boolean {
  return (
    typeof window !== 'undefined' &&
    typeof window.ReactNativeWebView?.postMessage === 'function'
  );
}

export function onBridgeEvent(listener: (event: BridgeEvent) => void): () => void {
  eventListeners.add(listener);
  return () => eventListeners.delete(listener);
}

export function onBridgeLog(listener: (entry: BridgeLogEntry) => void): () => void {
  logListeners.add(listener);
  return () => logListeners.delete(listener);
}

export function sendBridgeRequest<TData = Record<string, unknown>>(
  type: BridgeRequestType,
  payload: Record<string, unknown> = {},
  timeoutMs = 8000,
): Promise<BridgeResponse<TData>> {
  installBridgeReceiver();
  const startedAt = Date.now();
  requestSequence += 1;
  const requestId = `req_${startedAt}_${requestSequence}`;

  if (!isAndroidWebViewAvailable()) {
    const response: BridgeResponse<TData> = {
      version: BRIDGE_PROTOCOL_VERSION,
      kind: 'response',
      requestId,
      type,
      ok: false,
      error: {
        code: 'BRIDGE_UNAVAILABLE',
        message: 'Android WebView bridge is unavailable in this browser environment.',
      },
      timestamp: Date.now(),
    };
    emitLog({
      id: requestId,
      timestamp: response.timestamp,
      direction: 'request',
      type,
      ok: false,
      detail: 'BRIDGE_UNAVAILABLE: Running outside Android WebView shell',
    });
    return Promise.resolve(response);
  }

  const request: BridgeRequest = {
    version: BRIDGE_PROTOCOL_VERSION,
    kind: 'request',
    requestId,
    type,
    payload,
    timestamp: startedAt,
  };

  return new Promise<BridgeResponse<TData>>((resolve) => {
    const timerId = window.setTimeout(() => {
      if (!pendingRequests.has(requestId)) {
        return;
      }
      pendingRequests.delete(requestId);
      const timeoutResponse: BridgeResponse<TData> = {
        version: BRIDGE_PROTOCOL_VERSION,
        kind: 'response',
        requestId,
        type,
        ok: false,
        error: {
          code: 'TIMEOUT',
          message: `Native bridge request '${type}' timed out after ${timeoutMs}ms.`,
        },
        timestamp: Date.now(),
      };
      emitLog({
        id: requestId,
        timestamp: timeoutResponse.timestamp,
        direction: 'request',
        type,
        ok: false,
        latencyMs: timeoutMs,
        detail: timeoutResponse.error!.message,
      });
      resolve(timeoutResponse);
    }, timeoutMs);

    pendingRequests.set(requestId, {
      type,
      startedAt,
      resolve,
      timerId,
    });

    try {
      window.ReactNativeWebView!.postMessage(JSON.stringify(request));
    } catch (err) {
      window.clearTimeout(timerId);
      pendingRequests.delete(requestId);
      const message = err instanceof Error ? err.message : 'Failed to post message to WebView.';
      resolve({
        version: BRIDGE_PROTOCOL_VERSION,
        kind: 'response',
        requestId,
        type,
        ok: false,
        error: {
          code: 'INTERNAL_ERROR',
          message,
        },
        timestamp: Date.now(),
      });
    }
  });
}

export function pingNativeBridge(echo?: string): Promise<BridgeResponse<PingData>> {
  return sendBridgeRequest<PingData>('BRIDGE_PING', echo ? { echo } : {});
}

export function getNativeRuntimeInfo(): Promise<BridgeResponse<RuntimeInfoData>> {
  return sendBridgeRequest<RuntimeInfoData>('GET_RUNTIME_INFO', {});
}

export function testUnknownBridgeCommand(): Promise<BridgeResponse<any>> {
  return sendBridgeRequest('UNKNOWN_COMMAND', {});
}

/**
 * Request device identity (Phaseâ€¯3).
 * Returns a BridgeResponse whose `data` contains `{ deviceId, publicKey, algorithm }`.
 */
export function getDeviceIdentity(): Promise<BridgeResponse<any>> {
  return sendBridgeRequest('GET_IDENTITY', {});
}

// MESH-X BLE discovery

export interface NearbyDevice {
  address: string;
  name: string | null;
  rssi: number;
  lastSeen: number;
}

export interface BluetoothPermissionData {
  granted: boolean;
  permissions: Record<string, string>;
}

export interface NearbyDevicesData {
  devices: NearbyDevice[];
}

export function requestBluetoothPermissions(): Promise<BridgeResponse<BluetoothPermissionData>> {
  return sendBridgeRequest<BluetoothPermissionData>(
    'REQUEST_BLUETOOTH_PERMISSIONS',
    {},
    30000,
  );
}

export function startDiscovery(): Promise<BridgeResponse<{ started: boolean }>> {
  return sendBridgeRequest<{ started: boolean }>('START_DISCOVERY', {});
}

export function stopDiscovery(): Promise<BridgeResponse<{ stopped: boolean }>> {
  return sendBridgeRequest<{ stopped: boolean }>('STOP_DISCOVERY', {});
}

export function getNearbyDevices(): Promise<BridgeResponse<NearbyDevicesData>> {
  return sendBridgeRequest<NearbyDevicesData>('GET_NEARBY_DEVICES', {});
}
