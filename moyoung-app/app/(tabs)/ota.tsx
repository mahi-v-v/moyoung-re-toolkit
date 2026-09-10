import React, { useCallback, useEffect, useState } from 'react';
import { Alert, ScrollView, StyleSheet, Text, TextInput, View } from 'react-native';

import {
  Btn,
  LogPanel,
  NotConnected,
  Row,
  Section,
  confirmDestructive,
  k,
  useConnected,
  useDeviceLog,
} from '@/components/GlassKit';
import { Glass, GlassEventSubscriptions } from '@/modules/glass-sdk';

/**
 * The OTA / firmware screen — the main reason this app exists.
 *
 * Two chips, two update paths (see docs/02_Firmware_and_OTA.md, docs/09_Vision_Firmware_V821.md):
 *   Core / Jieli — BLE DFU, dual-bank A/B → a bad image auto-rolls-back. The SAFE chip.
 *   Vision / Allwinner — over the device's Wi-Fi, swupdate .swu. Higher risk.
 *
 * The "Bundled tinker builds" are shipped inside the APK (module assets/firmware/) and resolved to
 * a real path by the native `resolveBundledFirmware`. Our Vision .swu is deliberately rootfs-ONLY
 * (writes /dev/mmcblk0p9 only — never boot0/uboot), which removes the worst brick vector. See
 * docs/11_Streaming_Bitrate_Analysis.md and docs/12_Firmware_Patching_and_Flashing.md.
 */

type BuildKind = 'core' | 'vision';
interface BuildMeta {
  title: string;
  kind: BuildKind;
  color: string;
  desc: string;
  disabled?: boolean;
}

// Friendly metadata for the builds we ship. Ordered = recommended sequence.
// Vision first — that's the bitrate goal. Core is disabled: our my_glasses_sdk.aar drop was built
// against a different jl_bt_ota API than the shipping v1.11.0 (registerBluetoothCallback signature
// mismatch), and Core isn't needed for the bitrate change anyway.
const BUILD_ORDER = [
  'vision_stock_ab.swu',
  'vision_noop.swu',
  'vision_6mbps_adb.swu',
  'core_0.0.9_dryrun.ufw',
  'core_0.0.1_relabeled.ufw',
];
const BUILDS: Record<string, BuildMeta> = {
  'vision_stock_ab.swu': {
    title: '0 · Vision GENUINE -ab (control test)',
    kind: 'vision',
    color: '#5aa9e6',
    desc:
      'Unmodified vendor dual-bank (-ab) image, 12.5 MB — the exact file the official app flashes. ' +
      'CONTROL TEST: if THIS reaches 100% + reboots + version-changes, the transport works and our repack ' +
      'was the problem; if it also stalls ~22%, the fault is the transport. It is A/B (writes the inactive ' +
      'slot, rollback-safe). Judge ONLY by a real reboot + version change — never by "completed".',
  },
  'vision_noop.swu': {
    title: '1 · Vision no-op (unchanged rootfs)',
    kind: 'vision',
    color: '#e9c46a',
    desc:
      'Byte-for-byte vendor rootfs, repacked rootfs-ONLY (writes mmcblk0p9 only, never ' +
      'boot0/uboot). Zero functional change — proves the Wi-Fi transfer/write/reboot pipeline. Do this first.',
  },
  'vision_6mbps_adb.swu': {
    title: '2 · Vision 6 Mbps + adb-over-Wi-Fi',
    kind: 'vision',
    color: '#b23a48',
    desc:
      'Live-stream bitrate 1.5→6 Mbps (2-byte patch) + enables adb on tcp:5555 (recovery/verify ' +
      'over Wi-Fi). Still rootfs-ONLY. Flash after the no-op succeeds.',
  },
  'core_0.0.9_dryrun.ufw': {
    title: '3 · Core 0.0.9 (Jieli, BLE DFU)',
    kind: 'core',
    color: '#2a9d8f',
    desc:
      'JieLi Core OTA over BLE — dual-bank A/B, so a bad image rolls back on its own (the safe chip). ' +
      'Now enabled: jl_bt_ota pinned to v1.10.0 to match the SDK API. 0.0.9 keeps STA Wi-Fi (not in ' +
      'the app’s force-AP list), and the device version should read 0.0.9 after. Keep still & charged.',
  },
  'core_0.0.1_relabeled.ufw': {
    title: '4 · Core 0.0.1 RELABELED (E3 modified-image test)',
    kind: 'core',
    color: '#9b5de5',
    desc:
      'Stock 0.0.8 Core with ONLY the version string relabeled 0.0.8→0.0.1 (re-encrypted, all CRCs ' +
      'rebuilt by tools/core-firmware/ufw_relabel.py — validated byte-identical round-trip). Functionally ' +
      'identical to 0.0.8; this proves a MODIFIED, re-encrypted Core image boots (the open E3 gate). ' +
      'Dual-bank A/B, rollback-safe. After reboot the device version should read 0.0.1. Keep still & charged.',
  },
};

function ProgressBar({ value }: { value: number | null }) {
  const pct = value === null ? 0 : Math.max(0, Math.min(100, value));
  return (
    <View style={s.barTrack}>
      <View style={[s.barFill, { width: `${pct}%` as `${number}%` }]} />
      <Text style={s.barLabel}>{value === null ? 'idle' : `${pct}%`}</Text>
    </View>
  );
}

export default function OtaScreen() {
  const connected = useConnected();
  const { lines, clear, log } = useDeviceLog();

  const [progress, setProgress] = useState<number | null>(null);
  const [otaState, setOtaState] = useState<string | null>(null);
  const [firmware, setFirmware] = useState<string | null>(null);
  const [wifiConnected, setWifiConnected] = useState(false);
  const [wifiMsg, setWifiMsg] = useState<string | null>(null);

  const [available, setAvailable] = useState<string[]>([]);
  const [selected, setSelected] = useState<{ name: string; path: string } | null>(null);
  const [busy, setBusy] = useState(false);

  const [mac, setMac] = useState('');
  const [jieliPath, setJieliPath] = useState('');
  const [allwinnerPath, setAllwinnerPath] = useState('');

  useEffect(() => {
    const subs = [
      GlassEventSubscriptions.onOtaProgress((e) => setProgress(e.progress)),
      GlassEventSubscriptions.onOtaState((e) => {
        setOtaState(`${e.state}${e.message ? ` — ${e.message}` : ''}`);
        // release the busy latch on any terminal state
        if (/completed|aborted|error/i.test(e.state)) setBusy(false);
      }),
      GlassEventSubscriptions.onFirmwareInfo((e) => setFirmware(e.info)),
      GlassEventSubscriptions.onWifiState((e) => {
        setWifiConnected(e.connected);
        setWifiMsg(`${e.wifiType ?? ''} state=${e.state} connected=${e.connected}`.trim());
      }),
    ];
    return () => subs.forEach((sub) => sub.remove());
  }, []);

  // Which builds are actually present in the APK.
  useEffect(() => {
    Glass.listBundledFirmware()
      .then((names) => setAvailable(names))
      .catch((e) => log(`listBundledFirmware failed: ${e?.message ?? e}`));
  }, [log]);

  const selectBuild = useCallback(
    async (name: string) => {
      try {
        const path = await Glass.resolveBundledFirmware(name);
        setSelected({ name, path });
        setProgress(null);
        setOtaState(null);
        if (BUILDS[name]?.kind === 'vision') setAllwinnerPath(path);
        else setJieliPath(path);
        log(`selected ${name}\n  → ${path}`);
      } catch (e: any) {
        log(`resolve ${name} failed: ${e?.message ?? e}`);
      }
    },
    [log]
  );

  const flashCore = useCallback(
    (path: string) =>
      confirmDestructive(
        'Flash the Core (Jieli) build?',
        'BLE DFU on the audio/BLE MCU. Dual-bank A/B, so a bad image should roll back on its own — ' +
          'but keep the glasses still and charged, and do not power off mid-flash.',
        () => {
          setBusy(true);
          setProgress(0);
          log(`startJieliOta(${path})`);
          Glass.startJieliOta(path);
        }
      ),
    [log]
  );

  // The Vision OTA joins the glasses' Wi-Fi AP, which needs the PHONE's Wi-Fi radio ON. If it's off,
  // the SDK's connectWifi() silently never connects and the whole chain hangs with no error. Gate the
  // flash on Wi-Fi being enabled, and offer to open the Wi-Fi panel. Returns true if OK to proceed.
  const ensureWifiOn = useCallback(async (): Promise<boolean> => {
    try {
      if (await Glass.isWifiEnabled()) return true;
    } catch {
      return true; // if the check itself fails, don't block the flash
    }
    return new Promise<boolean>((resolve) => {
      Alert.alert(
        'Turn Wi-Fi on first',
        'The Vision flash needs the phone’s Wi-Fi ON to join the glasses’ network. It is currently OFF — ' +
          'otherwise the flash hangs silently. Turn Wi-Fi on, then tap Flash again.',
        [
          { text: 'Cancel', style: 'cancel', onPress: () => resolve(false) },
          { text: 'Open Wi-Fi settings', onPress: () => { Glass.requestEnableWifi(); resolve(false); } },
        ]
      );
    });
  }, []);

  const startVision = useCallback(
    async (path: string) => {
      if (!(await ensureWifiOn())) return;
      confirmDestructive(
        'Push this .swu to the Vision (Allwinner) SoC?',
        'Writes the rootfs partition in place (no A/B). This build is rootfs-only so it never ' +
          'touches the bootloader, but do NOT power off mid-flash, keep Wi-Fi close, and make sure ' +
          'the battery is well charged. Continue?',
        () => {
          setBusy(true);
          setProgress(0);
          log(`startAllwinnerOta(${path})`);
          Glass.startAllwinnerOta(path);
        }
      );
    },
    [ensureWifiOn, log]
  );

  const startVisionAuto = useCallback(
    async (path: string) => {
      if (!(await ensureWifiOn())) return;
      confirmDestructive(
        'Flash the Vision (Allwinner) firmware?',
        'Runs the vendor Wi-Fi OTA end-to-end: enables the device Wi-Fi, joins it, then transfers ' +
          'the image. Keep the glasses charged and do NOT power off — they reboot when done. Continue?',
        () => {
          setBusy(true);
          setProgress(0);
          log(`startVisionOtaAuto(${path})`);
          Glass.startVisionOtaAuto(path);
        }
      );
    },
    [ensureWifiOn, log]
  );

  // Laptop-harness diagnostic. The phone triggers over BLE but does NOT join the AP, so a LAPTOP is
  // the 192.168.31.2:8182 server and Wireshark on it captures who truncates the transfer (removes
  // Android from the data path entirely). See tools/vision-ota-harness/. NOTE: no ensureWifiOn() here —
  // we WANT the phone's Wi-Fi off so the laptop can take .2.
  const startVisionExternal = useCallback(
    (path: string) => {
      Alert.alert(
        'Laptop-server flash (diagnostic)',
        'The phone brings up the glasses Wi-Fi but will NOT join it — your LAPTOP serves the file so we ' +
          'can see who tears the transfer down.\n\n' +
          '1) Turn this phone’s Wi-Fi OFF now (so the laptop can take 192.168.31.2).\n' +
          '2) Tap Start. When the OTA state reads “awaitingExternalServer”, on the LAPTOP: join the OPEN ' +
          'glasses AP, set static IP 192.168.31.2, run vision_ota_server.py, start Wireshark (tcp.port==8182).\n' +
          '3) Then tap “Continue — laptop ready”.',
        [
          { text: 'Cancel', style: 'cancel' },
          {
            text: 'Start',
            onPress: () => {
              setBusy(true);
              setProgress(0);
              log(`startVisionOtaExternal(${path})`);
              Glass.startVisionOtaExternal(path);
            },
          },
        ]
      );
    },
    [log]
  );

  if (!connected) {
    return (
      <ScrollView style={k.container} contentContainerStyle={k.content}>
        <NotConnected />
        <LogPanel lines={lines} onClear={clear} />
      </ScrollView>
    );
  }

  const sel = selected ? BUILDS[selected.name] : null;

  return (
    <ScrollView style={k.container} contentContainerStyle={k.content}>
      <View>
        <Text style={k.h1}>OTA / Firmware</Text>
        <Text style={k.sub}>
          Flash a Vision build (Wi-Fi) or the Core build (BLE, dual-bank A/B — the safe chip). Keep the
          unit charged and do not power off mid-flash. Details: docs/12_Firmware_Patching_and_Flashing.md.
        </Text>
      </View>

      <Section title="Status">
        <ProgressBar value={progress} />
        <Row label="OTA state" value={otaState ?? '—'} />
        <Row label="Selected build" value={selected?.name ?? 'none'} />
        <Row label="Wi-Fi" value={wifiMsg ?? (wifiConnected ? 'connected' : '—')} />
      </Section>

      <Section title="Bundled tinker builds">
        {BUILD_ORDER.filter((n) => available.length === 0 || available.includes(n)).map((name) => {
          const b = BUILDS[name];
          const isSel = selected?.name === name;
          return (
            <View key={name} style={[s.card, isSel && { borderColor: b.color }]}>
              <Text style={[s.cardTitle, { color: b.color }]}>{b.title}</Text>
              <Text style={s.cardDesc}>{b.desc}</Text>
              <Btn
                title={
                  b.disabled
                    ? 'Unavailable'
                    : isSel
                      ? 'Selected ✓  (tap to re-copy)'
                      : 'Select this build'
                }
                color={isSel ? '#3a4250' : b.color}
                disabled={b.disabled}
                onPress={() => selectBuild(name)}
              />
            </View>
          );
        })}
        {available.length > 0 && !BUILD_ORDER.some((n) => available.includes(n)) && (
          <Text style={k.sub}>No known builds bundled. assets/firmware/: {available.join(', ')}</Text>
        )}
      </Section>

      {sel && selected && (
        <Section title={`Flash — ${selected.name}`}>
          {sel.kind === 'core' ? (
            <>
              <Text style={k.sub}>BLE DFU · dual-bank A/B · one tap.</Text>
              <Btn
                title="Flash Core build (BLE, dual-bank)"
                color={sel.color}
                busy={busy}
                onPress={() => flashCore(selected.path)}
              />
              <Btn title="Abort" color="#e76f51" onPress={() => Glass.abortJieliOta()} />
            </>
          ) : (
            <>
              <Text style={k.sub}>
                Vendor-documented flow, one tap: enable device Wi-Fi (OTA) → phone joins it →
                transfer. Keep the glasses still & charged; they reboot at the end.
              </Text>
              <Btn
                title="Flash Vision (auto Wi-Fi)"
                color={sel.color}
                busy={busy}
                onPress={() => startVisionAuto(selected.path)}
              />
              <Btn title="Resume" color="#456990" onPress={() => Glass.resumeAllwinnerOta()} />
              <Btn title="Abort" color="#e76f51" onPress={() => Glass.abortAllwinnerOta()} />
              <Text style={k.sub}>Manual fallback (only if auto stalls):</Text>
              <Btn title="1 · Enable Wi-Fi (OTA)" color="#3a4250" onPress={() => Glass.enableWifi('OTA')} />
              <Btn title="2 · Connect Wi-Fi" color="#3a4250" onPress={() => Glass.connectWifi()} />
              <Btn
                title={wifiConnected ? '3 · Start flash' : '3 · Start flash (Wi-Fi not ready)'}
                color="#3a4250"
                onPress={() => startVision(selected.path)}
              />
              <Btn title="Disable Wi-Fi" color="#e76f51" onPress={() => Glass.disableWifi()} />

              <Text style={[k.sub, { marginTop: 12 }]}>
                Diagnostic — LAPTOP-server harness: removes the phone from the data path so Wireshark on
                the laptop shows who truncates the transfer. Needs the laptop running
                tools/vision-ota-harness/vision_ota_server.py. Turn the phone’s Wi-Fi OFF first.
              </Text>
              <Btn
                title="Flash via LAPTOP server (diagnostic)"
                color="#8a5a83"
                onPress={() => startVisionExternal(selected.path)}
              />
              {otaState?.includes('awaitingExternalServer') && (
                <Btn
                  title="▶ Continue — laptop joined & server running"
                  color="#e9c46a"
                  onPress={() => {
                    log('continueVisionOtaExternal()');
                    Glass.continueVisionOtaExternal();
                  }}
                />
              )}
            </>
          )}
        </Section>
      )}

      <Section title="Diagnostics — pull device log (BLE/Wi-Fi)">
        <Text style={k.sub}>
          Pulls the glasses’ on-device log over the FILE Wi-Fi transport (device→phone, the reliable
          direction) and dumps its contents here + to the Metro terminal. Use right after a Vision OTA
          stalls — it may carry the swupdate/OTA abort reason the Core relays from the Vision. Enabling
          + joining the device Wi-Fi takes ~5–10 s; approve the “join network?” dialog.
        </Text>
        <Btn
          title="Pull device log"
          color="#456990"
          onPress={() => {
            log('pullDeviceLogAuto()');
            Glass.pullDeviceLogAuto();
          }}
        />
      </Section>

      <Section title="Check for firmware (cloud)">
        <TextInput
          style={s.input}
          placeholder="device MAC (AA:BB:CC:DD:EE:FF)"
          placeholderTextColor="#4a5568"
          autoCapitalize="characters"
          value={mac}
          onChangeText={setMac}
        />
        <Btn
          title="Check firmware version"
          onPress={() => {
            log(`checkFirmware(${mac})`);
            Glass.checkFirmware(mac, '', '');
          }}
        />
        <Text style={k.logLine}>{firmware ?? 'not checked yet'}</Text>
        <Text style={k.sub}>
          Hits altair.moyoung.com/api/v1/firmware/check-upgrade and returns fileUrl + md5 — how you
          obtain official images to analyse.
        </Text>
      </Section>

      <Section title="Advanced · manual file path">
        <TextInput
          style={s.input}
          placeholder="Jieli .ufw path (blank = server download)"
          placeholderTextColor="#4a5568"
          autoCapitalize="none"
          value={jieliPath}
          onChangeText={setJieliPath}
        />
        <Btn
          title="Start Jieli OTA (manual)"
          color="#e9c46a"
          onPress={() =>
            confirmDestructive(
              'Start Jieli OTA?',
              'Dual-bank A/B — should fall back on a bad write. Do not power off mid-flash.',
              () => Glass.startJieliOta(jieliPath.trim() || undefined)
            )
          }
        />
        <TextInput
          style={s.input}
          placeholder="Allwinner .swu path (required)"
          placeholderTextColor="#4a5568"
          autoCapitalize="none"
          value={allwinnerPath}
          onChangeText={setAllwinnerPath}
        />
        <Btn
          title="Start Allwinner OTA (manual)"
          color="#b23a48"
          disabled={!allwinnerPath.trim()}
          onPress={() =>
            confirmDestructive(
              'Push firmware to the Allwinner SoC?',
              'Arbitrary-file Wi-Fi flash. A rootfs-only .swu is far safer than a full image. Continue?',
              () => Glass.startAllwinnerOta(allwinnerPath.trim())
            )
          }
        />
        <Text style={k.sub}>
          ⚠️ Accepts any file — the primary tinkering surface and the primary way to brick a unit.
        </Text>
      </Section>

      <LogPanel lines={lines} onClear={clear} />
    </ScrollView>
  );
}

const s = StyleSheet.create({
  barTrack: {
    height: 22,
    backgroundColor: '#0d1015',
    borderRadius: 6,
    borderWidth: 1,
    borderColor: '#262d36',
    justifyContent: 'center',
    overflow: 'hidden',
  },
  barFill: {
    position: 'absolute',
    left: 0,
    top: 0,
    bottom: 0,
    backgroundColor: '#2a9d8f',
  },
  barLabel: { color: '#e6edf3', fontSize: 12, fontWeight: '700', textAlign: 'center' },
  card: {
    backgroundColor: '#0d1015',
    borderRadius: 10,
    borderWidth: 1,
    borderColor: '#262d36',
    padding: 12,
    gap: 8,
  },
  cardTitle: { fontSize: 14, fontWeight: '700' },
  cardDesc: { color: '#8b98a5', fontSize: 12, lineHeight: 17 },
  input: {
    backgroundColor: '#0d1015',
    borderRadius: 8,
    borderWidth: 1,
    borderColor: '#262d36',
    color: '#e6edf3',
    paddingHorizontal: 12,
    paddingVertical: 10,
    fontSize: 13,
    fontFamily: 'monospace',
  },
});
