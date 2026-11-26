module.exports = {
  preset: '@react-native/jest-preset',
  transformIgnorePatterns: [
    'node_modules/(?!(react-native|@react-native|react-native-webview|react-native-safe-area-context|react-native-svg|lucide-react-native)/)',
  ],
};
