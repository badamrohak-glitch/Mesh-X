import React from 'react';
import {
  StatusBar,
  StyleSheet,
  BackHandler,
  View,
  ActivityIndicator,
  NativeModules,
  DeviceEventEmitter,
  PermissionsAndroid,
  Platform,
} from 'react-native';
import { SafeAreaProvider, SafeAreaView } from 'react-native-safe-area-context';
import WebView, { WebViewMessageEvent } from 'react-native-webview';
import {
  buildWebViewDispatchScript,
  MeshXNativeModule,
  relayWebViewMessageToNative,
} from './src/bridgeRelay';

const NATIVE_EVENT_NAME = 'MeshXNativeEvent';

function getMeshXNativeModule(): MeshXNativeModule | undefined {
  return NativeModules.MeshXBridge as MeshXNativeModule | undefined;
}

async function requestBluetoothPermissions(requestId: string): Promise<string> {
  const requiredPermissions =
    Number(Platform.Version) >= 31
      ? [
          PermissionsAndroid.PERMISSIONS.BLUETOOTH_SCAN,
          PermissionsAndroid.PERMISSIONS.BLUETOOTH_CONNECT,
          PermissionsAndroid.PERMISSIONS.BLUETOOTH_ADVERTISE,
        ]
      : [PermissionsAndroid.PERMISSIONS.ACCESS_FINE_LOCATION];

  try {
    const results = await PermissionsAndroid.requestMultiple(
      requiredPermissions,
    );

    const granted = requiredPermissions.every(
      permission => results[permission] === PermissionsAndroid.RESULTS.GRANTED,
    );

    return JSON.stringify({
      version: 1,
      kind: 'response',
      requestId,
      type: 'REQUEST_BLUETOOTH_PERMISSIONS',
      ok: true,
      data: {
        granted,
        permissions: results,
      },
      timestamp: Date.now(),
    });
  } catch (error) {
    return JSON.stringify({
      version: 1,
      kind: 'response',
      requestId,
      type: 'REQUEST_BLUETOOTH_PERMISSIONS',
      ok: false,
      error: {
        code: 'PERMISSION_REQUEST_FAILED',
        message:
          error instanceof Error
            ? error.message
            : 'Unable to request Bluetooth permissions.',
      },
      timestamp: Date.now(),
    });
  }
}

function MeshXApp() {
  const webViewRef = React.useRef<WebView>(null);
  const [loading, setLoading] = React.useState(true);

  React.useEffect(() => {
    console.log('[MESHX] App component mounted');
  }, []);

  const forwardJsonToWebView = React.useCallback((jsonPayload: string) => {
    if (!webViewRef.current) {
      return;
    }

    webViewRef.current.injectJavaScript(
      buildWebViewDispatchScript(jsonPayload),
    );
  }, []);

  React.useEffect(() => {
    const subscription = BackHandler.addEventListener(
      'hardwareBackPress',
      () => {
        webViewRef.current?.goBack();
        return true;
      },
    );

    return () => subscription.remove();
  }, []);

  React.useEffect(() => {
    const eventSubscription = DeviceEventEmitter.addListener(
      NATIVE_EVENT_NAME,
      (eventJson: unknown) => {
        if (typeof eventJson === 'string' && eventJson.length > 0) {
          forwardJsonToWebView(eventJson);
        }
      },
    );

    return () => eventSubscription.remove();
  }, [forwardJsonToWebView]);

  const handleWebViewMessage = React.useCallback(
    async (event: WebViewMessageEvent) => {
      const rawData = event.nativeEvent.data;

      try {
        const parsed = JSON.parse(rawData);

        if (parsed?.type === 'REQUEST_BLUETOOTH_PERMISSIONS') {
          const responseJson = await requestBluetoothPermissions(
            parsed.requestId,
          );
          forwardJsonToWebView(responseJson);
          return;
        }
      } catch {
        // Invalid or unrelated messages continue through the normal bridge.
      }

      const responseJson = await relayWebViewMessageToNative(
        rawData,
        getMeshXNativeModule(),
      );

      forwardJsonToWebView(responseJson);
    },
    [forwardJsonToWebView],
  );

  const handleWebViewLoadEnd = React.useCallback(() => {
    console.log('[MESHX-WEBVIEW] Load ended');
    setLoading(false);

    try {
      getMeshXNativeModule()?.notifyWebViewLoaded?.();
    } catch {
      // Non-fatal: WebView can still query BRIDGE_PING / GET_RUNTIME_INFO directly.
    }
  }, []);

  return (
    <SafeAreaProvider>
      <SafeAreaView style={styles.container} edges={['top', 'bottom']}>
        <StatusBar
          barStyle="light-content"
          backgroundColor="#0a0c10"
        />

        <View style={styles.webContainer} onLayout={() => console.log("[MESHX] Container layout ready")}>
          <WebView
            ref={webViewRef}
            onLayout={() => console.log("[MESHX-WEBVIEW] Layout ready")}
            source={{ uri: 'file:///android_asset/meshx/index.html' }}
            originWhitelist={['*']}
            javaScriptEnabled={true}
            domStorageEnabled={true}
            allowFileAccess={true}
            allowFileAccessFromFileURLs={true}
            allowUniversalAccessFromFileURLs={false}
            mixedContentMode="never"
            cacheEnabled={true}
            onMessage={handleWebViewMessage}
            onLoadStart={() =>
              console.log('[MESHX-WEBVIEW] Load started')
            }
            onLoadProgress={({ nativeEvent }) =>
              console.log(
                '[MESHX-WEBVIEW] Load progress:',
                nativeEvent.progress,
              )
            }
            onLoadEnd={handleWebViewLoadEnd}
            onError={({ nativeEvent }) => {
              console.log('[MESHX-WEBVIEW] Load error:', nativeEvent);
              setLoading(false);
            }}
            onHttpError={({ nativeEvent }) =>
              console.log('[MESHX-WEBVIEW] HTTP error:', nativeEvent)
            }
            onRenderProcessGone={({ nativeEvent }) =>
              console.log('[MESHX-WEBVIEW] Renderer gone:', nativeEvent)
            }
            style={styles.webView}
            backgroundColor="#0a0c10"
            overScrollMode="never"
            setSupportMultipleWindows={false}
            onShouldStartLoadWithRequest={request => {
              return (
                request.url.startsWith('file:///android_asset/') ||
                request.url.startsWith('about:blank')
              );
            }}
          />

          {loading && (
            <View style={styles.loader}>
              <ActivityIndicator size="large" color="#b9a5ff" />
            </View>
          )}
        </View>
      </SafeAreaView>
    </SafeAreaProvider>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#0a0c10',
  },
  webContainer: {
    flex: 1,
    backgroundColor: '#0a0c10',
  },
  webView: {
    flex: 1,
    backgroundColor: '#0a0c10',
  },
  loader: {
    ...StyleSheet.absoluteFillObject,
    backgroundColor: '#0a0c10',
    justifyContent: 'center',
    alignItems: 'center',
  },
});

export default MeshXApp;
