import { DarkTheme, ThemeProvider } from '@react-navigation/native';
import { Stack } from 'expo-router';
import { StatusBar } from 'expo-status-bar';
import { useEffect } from 'react';
import 'react-native-reanimated';

import { ensureGlassLogging } from '@/components/GlassKit';

export const unstable_settings = {
  anchor: '(tabs)',
};

export default function RootLayout() {
  // Subscribe once, at boot, so every native trace is captured even before a screen mounts.
  useEffect(() => {
    ensureGlassLogging();
  }, []);

  return (
    <ThemeProvider value={DarkTheme}>
      <Stack>
        <Stack.Screen name="(tabs)" options={{ headerShown: false }} />
      </Stack>
      <StatusBar style="light" />
    </ThemeProvider>
  );
}
