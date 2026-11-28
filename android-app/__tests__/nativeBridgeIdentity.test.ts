import {
  BridgeLogEntry,
  getDeviceIdentity,
  installBridgeReceiver,
  onBridgeLog,
  sendBridgeRequest,
} from '../../src/bridge/nativeBridge';

describe('nativeBridge Phase 3 identity integration', () => {
  const originalWindow = (globalThis as { window?: unknown }).window;

  afterEach(() => {
    if (originalWindow === undefined) {
      delete (globalThis as { window?: unknown }).window;
    } else {
      (globalThis as { window?: unknown }).window = originalWindow;
    }
    jest.useRealTimers();
  });

  test('returns BRIDGE_UNAVAILABLE safely when Android WebView is absent', async () => {
    (globalThis as { window?: unknown }).window = {};

    const res = await getDeviceIdentity();
    expect(res.ok).toBe(false);
    expect(res.type).toBe('GET_IDENTITY');
    expect(res.error?.code).toBe('BRIDGE_UNAVAILABLE');
  });

  test('dispatches GET_IDENTITY and resolves typed identity without logging sensitive values', async () => {
    const fakeWindow: {
      ReactNativeWebView?: { postMessage: (msg: string) => void };
      __MESHX_BRIDGE_RECEIVE__?: (rawJson: string) => void;
    } = {};

    const logs: BridgeLogEntry[] = [];
    const unsubscribe = onBridgeLog(entry => logs.push(entry));

    const testDeviceId = '11111111-2222-4333-8444-555555555555';
    const testPublicKey =
      'MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAETestPublicKeyPayload12345==';

    fakeWindow.ReactNativeWebView = {
      postMessage: jest.fn((rawRequest: string) => {
        const parsed = JSON.parse(rawRequest);
        expect(parsed.type).toBe('GET_IDENTITY');
        expect(parsed.kind).toBe('request');
        expect(parsed.version).toBe(1);

        fakeWindow.__MESHX_BRIDGE_RECEIVE__?.(
          JSON.stringify({
            version: 1,
            kind: 'response',
            requestId: parsed.requestId,
            type: 'GET_IDENTITY',
            ok: true,
            data: {
              deviceId: testDeviceId,
              publicKey: testPublicKey,
              algorithm: 'EC',
            },
            timestamp: Date.now(),
          }),
        );
      }),
    };

    (globalThis as { window?: unknown }).window = fakeWindow;
    installBridgeReceiver();

    try {
      const res = await getDeviceIdentity();

      expect(res.ok).toBe(true);
      expect(res.data?.deviceId).toBe(testDeviceId);
      expect(res.data?.publicKey).toBe(testPublicKey);
      expect(res.data?.algorithm).toBe('EC');

      const serializedLogs = JSON.stringify(logs);
      expect(serializedLogs).not.toContain(testDeviceId);
      expect(serializedLogs).not.toContain(testPublicKey);
      expect(serializedLogs).not.toContain('privateKey');
    } finally {
      unsubscribe();
    }
  });

  test('returns TIMEOUT safely if native bridge does not respond', async () => {
    jest.useFakeTimers();

    const fakeWindow: {
      ReactNativeWebView?: { postMessage: (msg: string) => void };
      __MESHX_BRIDGE_RECEIVE__?: (rawJson: string) => void;
    } = {
      ReactNativeWebView: {
        postMessage: jest.fn(),
      },
    };

    (globalThis as { window?: unknown }).window = fakeWindow;
    installBridgeReceiver();

    const promise = sendBridgeRequest('GET_IDENTITY', {}, 500);
    jest.advanceTimersByTime(500);
    const res = await promise;

    expect(res.ok).toBe(false);
    expect(res.error?.code).toBe('TIMEOUT');
  });
});
