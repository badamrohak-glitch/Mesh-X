/**
 * @format
 */

import React from 'react';
import ReactTestRenderer from 'react-test-renderer';

jest.mock('react-native-webview', () => {
  const mockReact = require('react');
  const { View } = require('react-native');
  return mockReact.forwardRef((props: Record<string, unknown>, _ref: unknown) =>
    mockReact.createElement(View, props),
  );
});

jest.mock('react-native-safe-area-context', () => {
  const mockReact = require('react');
  const { View } = require('react-native');
  return {
    SafeAreaProvider: ({ children }: { children: unknown }) =>
      mockReact.createElement(View, null, children),
    SafeAreaView: ({ children, ...rest }: { children: unknown }) =>
      mockReact.createElement(View, rest, children),
  };
});

import App from '../App';

test('renders correctly', async () => {
  await ReactTestRenderer.act(() => {
    ReactTestRenderer.create(<App />);
  });
});
