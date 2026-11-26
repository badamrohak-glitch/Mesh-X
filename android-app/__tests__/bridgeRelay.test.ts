import {
  buildRelayErrorResponse,
  buildWebViewDispatchScript,
  MeshXNativeModule,
  relayWebViewMessageToNative,
} from '../src/bridgeRelay';

describe('bridgeRelay', () => {
  test('forwards valid request to native module and returns native JSON', async () => {
    const mockNative: MeshXNativeModule = {
      dispatchRequest: jest.fn(async (rawJson: string) => {
        const parsed = JSON.parse(rawJson);
        return JSON.stringify({
          version: 1,
          kind: 'response',
          requestId: parsed.requestId,
          type: parsed.type,
          ok: true,
          data: { pong: true },
          timestamp: 12345,
        });
      }),
    };

    const req = JSON.stringify({
      version: 1,
      kind: 'request',
      requestId: 'req-42',
      type: 'BRIDGE_PING',
      payload: {},
      timestamp: 1000,
    });

    const res = JSON.parse(await relayWebViewMessageToNative(req, mockNative));
    expect(mockNative.dispatchRequest).toHaveBeenCalledWith(req);
    expect(res.ok).toBe(true);
    expect(res.requestId).toBe('req-42');
    expect(res.data.pong).toBe(true);
  });

  test('returns structured BRIDGE_UNAVAILABLE error when native module is missing', async () => {
    const req = JSON.stringify({
      version: 1,
      kind: 'request',
      requestId: 'req-77',
      type: 'GET_RUNTIME_INFO',
      payload: {},
      timestamp: 1000,
    });

    const res = JSON.parse(await relayWebViewMessageToNative(req, undefined));
    expect(res.ok).toBe(false);
    expect(res.requestId).toBe('req-77');
    expect(res.error.code).toBe('BRIDGE_UNAVAILABLE');
  });

  test('returns structured INTERNAL_ERROR when native promise rejects', async () => {
    const failingNative: MeshXNativeModule = {
      dispatchRequest: jest.fn(async () => {
        throw new Error('Bridge crash');
      }),
    };

    const req = JSON.stringify({
      version: 1,
      kind: 'request',
      requestId: 'req-99',
      type: 'BRIDGE_PING',
      payload: {},
      timestamp: 1000,
    });

    const res = JSON.parse(await relayWebViewMessageToNative(req, failingNative));
    expect(res.ok).toBe(false);
    expect(res.requestId).toBe('req-99');
    expect(res.error.code).toBe('INTERNAL_ERROR');
    expect(res.error.message).toContain('Bridge crash');
  });

  test('rejects empty payload without calling native module', async () => {
    const mockNative: MeshXNativeModule = {
      dispatchRequest: jest.fn(),
    };

    const res = JSON.parse(await relayWebViewMessageToNative('   ', mockNative));
    expect(mockNative.dispatchRequest).not.toHaveBeenCalled();
    expect(res.ok).toBe(false);
    expect(res.error.code).toBe('INVALID_JSON');
  });

  test('buildWebViewDispatchScript safely encodes JSON payload', () => {
    const script = buildWebViewDispatchScript('{"ok":true,"msg":"hello\'world"}');
    expect(script).toContain('window.__MESHX_BRIDGE_RECEIVE__');
    expect(script).toContain('hello\'world');
    expect(script.endsWith('true;')).toBe(true);
  });

  test('buildRelayErrorResponse creates v1 response envelope', () => {
    const parsed = JSON.parse(
      buildRelayErrorResponse('req-1', 'BRIDGE_PING', 'TIMEOUT', 'Timed out'),
    );
    expect(parsed.version).toBe(1);
    expect(parsed.kind).toBe('response');
    expect(parsed.ok).toBe(false);
    expect(parsed.error.code).toBe('TIMEOUT');
  });
});
