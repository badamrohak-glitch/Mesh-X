export interface BridgeErrorPayload {
  code: string;
  message: string;
  details?: Record<string, unknown>;
}

export interface BridgeResponseEnvelope {
  version: number;
  kind: 'response';
  requestId: string;
  type: string;
  ok: boolean;
  data?: Record<string, unknown>;
  error?: BridgeErrorPayload;
  timestamp: number;
}

export interface MeshXNativeModule {
  dispatchRequest: (rawJson: string) => Promise<string>;
  notifyWebViewLoaded?: () => void;
}

const MAX_MESSAGE_BYTES = 65_536;

export function buildWebViewDispatchScript(jsonPayload: string): string {
  const serializedLiteral = JSON.stringify(jsonPayload);
  return `(function(){if(window.__MESHX_BRIDGE_RECEIVE__){window.__MESHX_BRIDGE_RECEIVE__(${serializedLiteral});}})();true;`;
}

export function buildRelayErrorResponse(
  requestId: string,
  type: string,
  code: string,
  message: string,
): string {
  const envelope: BridgeResponseEnvelope = {
    version: 1,
    kind: 'response',
    requestId,
    type,
    ok: false,
    error: { code, message },
    timestamp: Date.now(),
  };
  return JSON.stringify(envelope);
}

function extractRequestMeta(rawData: string): { requestId: string; type: string } {
  try {
    const parsed = JSON.parse(rawData) as { requestId?: unknown; type?: unknown };
    return {
      requestId: typeof parsed.requestId === 'string' && parsed.requestId.trim() ? parsed.requestId : 'unknown',
      type: typeof parsed.type === 'string' && parsed.type.trim() ? parsed.type : 'UNKNOWN',
    };
  } catch {
    return { requestId: 'unknown', type: 'UNKNOWN' };
  }
}

export async function relayWebViewMessageToNative(
  rawData: string,
  nativeModule: MeshXNativeModule | undefined | null,
): Promise<string> {
  if (typeof rawData !== 'string' || rawData.trim().length === 0) {
    return buildRelayErrorResponse(
      'unknown',
      'UNKNOWN',
      'INVALID_JSON',
      'WebView bridge received an empty message.',
    );
  }

  if (rawData.length > MAX_MESSAGE_BYTES) {
    const meta = extractRequestMeta(rawData.slice(0, 1024));
    return buildRelayErrorResponse(
      meta.requestId,
      meta.type,
      'INVALID_JSON',
      'Bridge message exceeds maximum allowed length.',
    );
  }

  const meta = extractRequestMeta(rawData);

  if (!nativeModule || typeof nativeModule.dispatchRequest !== 'function') {
    return buildRelayErrorResponse(
      meta.requestId,
      meta.type,
      'BRIDGE_UNAVAILABLE',
      'Android native module MeshXBridge is not registered.',
    );
  }

  try {
    const responseJson = await nativeModule.dispatchRequest(rawData);
    if (typeof responseJson !== 'string' || responseJson.trim().length === 0) {
      return buildRelayErrorResponse(
        meta.requestId,
        meta.type,
        'INTERNAL_ERROR',
        'Native bridge returned an empty response.',
      );
    }
    return responseJson;
  } catch (err) {
    const message = err instanceof Error ? err.message : 'Native bridge call failed.';
    return buildRelayErrorResponse(meta.requestId, meta.type, 'INTERNAL_ERROR', message);
  }
}
