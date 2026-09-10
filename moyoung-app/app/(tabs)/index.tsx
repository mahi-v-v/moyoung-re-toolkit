import React, { useEffect, useState } from 'react';
import { ScrollView, Switch, Text, TouchableOpacity, View } from 'react-native';

import { Btn, Row, Section, SkeletonNotice, k, useDeviceLog } from '@/components/GlassKit';
import {
  classifyVendor,
  ensureBluetoothReady,
  Glass,
  GlassConnectionState,
  GlassEventSubscriptions,
  showBluetoothOffAlert,
  showPermissionDeniedAlert,
  type DeviceFoundEvent,
} from '@/modules/glass-sdk';

export default function ConnectScreen() {
  const { log } = useDeviceLog();

  const [scanning, setScanning] = useState(false);
  const [showAll, setShowAll] = useState(true);
  const [devices, setDevices] = useState<DeviceFoundEvent[]>([]);
  const [state, setState] = useState<GlassConnectionState>(GlassConnectionState.IDLE);
  const [connected, setConnected] = useState<{ id: string; name?: string } | null>(null);
  const [battery, setBattery] = useState<{ level: number; charging: boolean } | null>(null);
  const [btEnabled, setBtEnabled] = useState<boolean | null>(null);

  useEffect(() => {
    // On launch, ask for BLE permissions and prompt to turn Bluetooth on if it's off — so the
    // very first thing the user sees is the permission/enable flow, not a silent failure.
    (async () => {
      await ensureBluetoothReady().catch(() => {});
      Glass.isBluetoothEnabled().then(setBtEnabled).catch(() => setBtEnabled(null));
    })();
    Glass.getCurrentState().then(setState).catch(() => {});

    const subs = [
      GlassEventSubscriptions.onDeviceFound((e) =>
        setDevices((prev) => (prev.some((d) => d.deviceId === e.deviceId) ? prev : [...prev, e]))
      ),
      GlassEventSubscriptions.onStateChanged((e) => {
        setState(e.newState);
        if (e.newState === GlassConnectionState.IDLE) {
          setConnected(null);
          setBattery(null);
        }
      }),
      GlassEventSubscriptions.onConnected((e) =>
        setConnected({ id: e.deviceId, name: e.deviceName })
      ),
      GlassEventSubscriptions.onBattery((e) =>
        setBattery({ level: e.level, charging: e.isCharging })
      ),
    ];
    return () => subs.forEach((s) => s.remove());
  }, []);

  // Gate every BLE action on permissions + Bluetooth being on, prompting for whichever is missing.
  const passGate = async (): Promise<boolean> => {
    const ready = await ensureBluetoothReady();
    Glass.isBluetoothEnabled().then(setBtEnabled).catch(() => {});
    if (ready.ok) return true;
    if (ready.reason === 'permission') {
      log(`permission denied: ${ready.error ?? ''}`);
      showPermissionDeniedAlert();
    } else if (ready.reason === 'bluetooth-off') {
      log('bluetooth is off');
      showBluetoothOffAlert();
    }
    return false;
  };

  const toggleScan = async () => {
    if (scanning) {
      await Glass.stopScan();
      setScanning(false);
      return;
    }
    if (!(await passGate())) return;
    setDevices([]);
    const ok = await Glass.startScan();
    if (!ok) {
      log('startScan returned false — is Bluetooth on?');
      return;
    }
    setScanning(true);
  };

  const connect = async (d: DeviceFoundEvent) => {
    if (!(await passGate())) return;
    if (scanning) {
      await Glass.stopScan();
      setScanning(false);
    }
    log(`connecting to ${d.deviceId} (${d.deviceName || 'unnamed'})`);
    await Glass.connect(d.deviceId, 'moyoung', d.deviceName);
  };

  const visible = showAll
    ? devices
    : devices.filter((d) => classifyVendor(d.advertisement, d.deviceName) === 'moyoung');
  const sorted = [...visible].sort((a, b) => (b.rssi ?? -999) - (a.rssi ?? -999));

  return (
    <ScrollView style={k.container} contentContainerStyle={k.content}>
      <View>
        <Text style={k.h1}>MoYoung Tinker</Text>
        <Text style={k.sub}>
          Scan, connect and poke at the glasses. BLE scanning is live; device commands need wiring.
        </Text>
      </View>

      <SkeletonNotice />

      <Section title="Status">
        <Row label="Bluetooth" value={btEnabled === null ? '—' : btEnabled ? 'on' : 'off'} />
        <Row label="Connection" value={state} />
        <Row
          label="Device"
          value={connected ? `${connected.name ?? 'unnamed'} (${connected.id})` : '—'}
        />
        <Row
          label="Battery"
          value={battery ? `${battery.level}%${battery.charging ? ' ⚡' : ''}` : '—'}
        />
      </Section>

      <Section title="Scan">
        <View
          style={{ flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' }}>
          <Text style={k.rowLabel}>Show all BLE devices</Text>
          <Switch value={showAll} onValueChange={setShowAll} />
        </View>
        <Btn
          title={scanning ? 'Stop scan' : 'Start scan'}
          color={scanning ? '#e76f51' : '#2a9d8f'}
          onPress={toggleScan}
        />
        <Text style={k.sub}>
          {sorted.length} device{sorted.length === 1 ? '' : 's'}
          {showAll ? '' : ' matching MoYoung heuristics'}
        </Text>
      </Section>

      <Section title="Devices">
        {sorted.length === 0 ? (
          <Text style={k.logEmpty}>Nothing found yet. Start a scan.</Text>
        ) : (
          sorted.map((d) => {
            const vendor = classifyVendor(d.advertisement, d.deviceName);
            return (
              <TouchableOpacity
                key={d.deviceId}
                style={[k.btn, { backgroundColor: '#232a33', alignItems: 'flex-start' }]}
                onPress={() => connect(d)}>
                <Text style={{ color: '#e6edf3', fontWeight: '700' }}>
                  {d.deviceName || '(no name)'}
                  {vendor === 'moyoung' ? '  ✦' : ''}
                </Text>
                <Text style={{ color: '#8b98a5', fontSize: 11, fontFamily: 'monospace' }}>
                  {d.deviceId}   rssi {d.rssi}
                </Text>
              </TouchableOpacity>
            );
          })
        )}
      </Section>

      {connected && (
        <Section title="Session">
          <Btn title="Sync time" onPress={() => Glass.syncTime()} />
          <Btn title="Query battery" onPress={() => Glass.queryBattery()} />
          <Btn title="Query device ID" onPress={() => Glass.queryDeviceId()} />
          <Btn
            title="Firmware version (Jieli)"
            onPress={() => Glass.queryDeviceVersion('VerFirmware')}
          />
          <Btn
            title="Firmware version (Allwinner)"
            onPress={() => Glass.queryDeviceVersion('VerFirmware1')}
          />
          <Btn title="Disconnect" color="#e76f51" onPress={() => Glass.disconnect()} />
        </Section>
      )}
    </ScrollView>
  );
}
