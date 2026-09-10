import React, { useEffect, useState } from 'react';
import {
  ActivityIndicator,
  Alert,
  ScrollView,
  StyleSheet,
  Text,
  TouchableOpacity,
  View,
} from 'react-native';

import {
  Glass,
  GlassConnectionState,
  GlassEventSubscriptions,
} from '@/modules/glass-sdk';

/**
 * Shared UI primitives + the global device-trace buffer for the tinker screens.
 *
 * The trace buffer is the workhorse: every `trace()` / `onError()` call in the native adapter
 * rides the GLASS_ERROR event and lands here, so anything the device or the skeleton does is
 * visible on-screen without a Metro console.
 */

// ── global trace buffer ──────────────────────────────────────────────────────

const MAX_LINES = 500;
let logLines: string[] = [];
let listeners: ((lines: string[]) => void)[] = [];
let started = false;

function pushLine(line: string) {
  const stamped = `${new Date().toLocaleTimeString()}  ${line}`;
  // Mirror every device-log line to the Metro/terminal console so `npx expo run:android`
  // shows the full stream (incl. [OTA]/[CRP] traces), not just the in-app panel.
  console.log(`[glass] ${line}`);
  logLines = [stamped, ...logLines].slice(0, MAX_LINES);
  listeners.forEach((l) => l(logLines));
}

/** Call once at app start (see app/_layout.tsx). Idempotent. */
export function ensureGlassLogging() {
  if (started) return;
  started = true;
  try {
    GlassEventSubscriptions.onError((e) => pushLine(e.isFatal ? `FATAL ${e.message}` : e.message));
    GlassEventSubscriptions.onStateChanged((e) => pushLine(`[state] ${e.newState}${e.reason ? ` (${e.reason})` : ''}`));
    GlassEventSubscriptions.onBattery((e) => pushLine(`[battery] ${e.level}%${e.isCharging ? ' charging' : ''}`));
    GlassEventSubscriptions.onDeviceVersion((e) => pushLine(`[version] ${e.type}=${e.version}`));
    GlassEventSubscriptions.onDeviceId((e) => pushLine(`[deviceId] ${e.deviceId}`));
    GlassEventSubscriptions.onFeatureState((e) => pushLine(`[features] ${e.state}`));
    GlassEventSubscriptions.onWifiState((e) => pushLine(`[wifi] state=${e.state} connected=${e.connected}`));
    GlassEventSubscriptions.onMediaCount((e) => pushLine(`[media] photos=${e.photoCount} videos=${e.videoCount}`));
    GlassEventSubscriptions.onDownloadProgress((e) => pushLine(`[download] ${e.progress}%`));
    GlassEventSubscriptions.onDownloadComplete((e) => pushLine(`[download] complete → ${e.dirPath} (${e.files})`));
    GlassEventSubscriptions.onOtaProgress((e) => pushLine(`[ota] ${e.progress}% ${e.phase ?? ''}`));
    GlassEventSubscriptions.onOtaState((e) => pushLine(`[ota] ${e.state} ${e.message ?? ''}`));
    GlassEventSubscriptions.onWakeWordStatus((e) => pushLine(`[wakeword] enabled=${e.enabled}`));
    GlassEventSubscriptions.onWearStatus((e) => pushLine(`[wear] enabled=${e.enabled}`));
    GlassEventSubscriptions.onAudioState((e) => pushLine(`[audio] recording=${e.recording} ${e.duration}s`));
    GlassEventSubscriptions.onVideoConfig((e) => pushLine(`[video] ${e.fps}fps max=${e.maxDuration}s`));
    GlassEventSubscriptions.onFirmwareInfo((e) => pushLine(`[firmware] ${e.info}`));
    GlassEventSubscriptions.onAiImage((e) => pushLine(`[ai-image] ${e.path}`));
  } catch (e: any) {
    // Native module missing (e.g. running in Expo Go) — keep the UI alive.
    pushLine(`native module unavailable: ${e?.message ?? e}`);
  }
}

export function useDeviceLog() {
  const [lines, setLines] = useState<string[]>(logLines);
  useEffect(() => {
    const l = (next: string[]) => setLines(next);
    listeners.push(l);
    return () => {
      listeners = listeners.filter((x) => x !== l);
    };
  }, []);
  return {
    lines,
    clear: () => {
      logLines = [];
      listeners.forEach((l) => l(logLines));
    },
    log: pushLine,
  };
}

// ── connection state hook ────────────────────────────────────────────────────

export function useConnected() {
  const [connected, setConnected] = useState(false);
  useEffect(() => {
    Glass.getCurrentState()
      .then((s) => setConnected(s === GlassConnectionState.CONNECTED))
      .catch(() => {});
    const sub = GlassEventSubscriptions.onStateChanged((e) =>
      setConnected(e.newState === GlassConnectionState.CONNECTED)
    );
    return () => sub.remove();
  }, []);
  return connected;
}

// ── primitives ───────────────────────────────────────────────────────────────

export function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <View style={k.section}>
      <Text style={k.sectionTitle}>{title}</Text>
      <View style={k.sectionBody}>{children}</View>
    </View>
  );
}

export function Btn({
  title,
  onPress,
  color = '#2a9d8f',
  disabled = false,
  busy = false,
}: {
  title: string;
  onPress: () => void;
  color?: string;
  disabled?: boolean;
  busy?: boolean;
}) {
  return (
    <TouchableOpacity
      style={[k.btn, { backgroundColor: disabled ? '#555' : color }]}
      onPress={onPress}
      disabled={disabled || busy}>
      {busy ? <ActivityIndicator color="#fff" /> : <Text style={k.btnText}>{title}</Text>}
    </TouchableOpacity>
  );
}

export function Row({ label, value }: { label: string; value: string | number | undefined }) {
  return (
    <View style={k.row}>
      <Text style={k.rowLabel}>{label}</Text>
      <Text style={k.rowValue}>{value ?? '—'}</Text>
    </View>
  );
}

export function NotConnected() {
  return (
    <View style={k.notice}>
      <Text style={k.noticeTitle}>Not connected</Text>
      <Text style={k.noticeText}>Connect to the glasses on the Connect tab first.</Text>
    </View>
  );
}

export function SkeletonNotice() {
  return (
    <View style={[k.notice, { borderColor: '#e9c46a' }]}>
      <Text style={[k.noticeTitle, { color: '#e9c46a' }]}>Skeleton build</Text>
      <Text style={k.noticeText}>
        The vendor SDK calls are not wired yet, so these buttons reach the native module and log a
        trace but do not talk to the device. See moyoung-app/README.md for the wire-up guide.
      </Text>
    </View>
  );
}

export function LogPanel({ lines, onClear }: { lines: string[]; onClear: () => void }) {
  return (
    <View style={k.section}>
      <View style={k.logHeader}>
        <Text style={k.sectionTitle}>Device log</Text>
        <TouchableOpacity onPress={onClear}>
          <Text style={k.clear}>clear</Text>
        </TouchableOpacity>
      </View>
      <ScrollView style={k.log} nestedScrollEnabled>
        {lines.length === 0 ? (
          <Text style={k.logEmpty}>no events yet</Text>
        ) : (
          lines.map((l, i) => (
            <Text key={i} style={k.logLine} numberOfLines={3}>
              {l}
            </Text>
          ))
        )}
      </ScrollView>
    </View>
  );
}

export function confirmDestructive(title: string, message: string, onConfirm: () => void) {
  Alert.alert(title, message, [
    { text: 'Cancel', style: 'cancel' },
    { text: 'Do it', style: 'destructive', onPress: onConfirm },
  ]);
}

// ── styles ───────────────────────────────────────────────────────────────────

export const k = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#12151a' },
  content: { padding: 16, paddingBottom: 48, gap: 16 },
  h1: { color: '#fff', fontSize: 22, fontWeight: '700', marginBottom: 4 },
  sub: { color: '#8b98a5', fontSize: 13, marginBottom: 12 },
  section: {
    backgroundColor: '#1b2027',
    borderRadius: 12,
    padding: 14,
    borderWidth: 1,
    borderColor: '#262d36',
  },
  sectionTitle: { color: '#e6edf3', fontSize: 15, fontWeight: '700', marginBottom: 10 },
  sectionBody: { gap: 8 },
  btn: { paddingVertical: 12, paddingHorizontal: 14, borderRadius: 9, alignItems: 'center' },
  btnText: { color: '#fff', fontWeight: '600', fontSize: 14 },
  row: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    paddingVertical: 6,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: '#262d36',
  },
  rowLabel: { color: '#8b98a5', fontSize: 13 },
  rowValue: { color: '#e6edf3', fontSize: 13, fontWeight: '600', flexShrink: 1, textAlign: 'right' },
  notice: {
    backgroundColor: '#1b2027',
    borderRadius: 12,
    padding: 14,
    borderWidth: 1,
    borderColor: '#3a4250',
  },
  noticeTitle: { color: '#e76f51', fontSize: 15, fontWeight: '700', marginBottom: 4 },
  noticeText: { color: '#8b98a5', fontSize: 13, lineHeight: 18 },
  logHeader: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  clear: { color: '#5aa9e6', fontSize: 12 },
  log: { maxHeight: 260, backgroundColor: '#0d1015', borderRadius: 8, padding: 8 },
  logLine: { color: '#9fb3c8', fontSize: 11, fontFamily: 'monospace', marginBottom: 2 },
  logEmpty: { color: '#4a5568', fontSize: 12, fontStyle: 'italic' },
});
