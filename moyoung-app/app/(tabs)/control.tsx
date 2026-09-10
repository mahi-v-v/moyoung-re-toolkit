import React, { useEffect, useState } from 'react';
import { ScrollView, Text, View } from 'react-native';

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
import { Glass, GlassEventSubscriptions, type VersionType } from '@/modules/glass-sdk';

const VERSION_TYPES: VersionType[] = [
  'VerFirmware',
  'VerFirmware1',
  'VerProtocol',
  'VerGithash',
  'VerTpVersion',
  'VerTpCheckSum',
];

export default function ControlScreen() {
  const connected = useConnected();
  const { lines, clear } = useDeviceLog();

  const [wakeWord, setWakeWord] = useState<boolean | null>(null);
  const [wear, setWear] = useState<boolean | null>(null);
  const [features, setFeatures] = useState<string | null>(null);

  useEffect(() => {
    const subs = [
      GlassEventSubscriptions.onWakeWordStatus((e) => setWakeWord(e.enabled)),
      GlassEventSubscriptions.onWearStatus((e) => setWear(e.enabled)),
      GlassEventSubscriptions.onFeatureState((e) => setFeatures(e.state)),
    ];
    return () => subs.forEach((s) => s.remove());
  }, []);

  if (!connected) {
    return (
      <ScrollView style={k.container} contentContainerStyle={k.content}>
        <NotConnected />
        <LogPanel lines={lines} onClear={clear} />
      </ScrollView>
    );
  }

  return (
    <ScrollView style={k.container} contentContainerStyle={k.content}>
      <View>
        <Text style={k.h1}>Control</Text>
        <Text style={k.sub}>Device state, toggles and management commands.</Text>
      </View>

      <Section title="Feature state (RunningStatus)">
        <Btn title="Query feature state" onPress={() => Glass.queryFeatureState()} />
        <Text style={k.logLine}>{features ?? 'not queried yet'}</Text>
        <Text style={k.sub}>
          The live map of what the device is doing: takePicture, aiVisual, audioRecording,
          videoRecording, fileSync, livingMode, slaveActive, simuInterpretation, aiDialogue,
          slaveOta, jieliOta.
        </Text>
      </Section>

      <Section title="Voice wake-up">
        <Row label="Reported state" value={wakeWord === null ? '—' : wakeWord ? 'enabled' : 'disabled'} />
        <Btn title="Query wake word" onPress={() => Glass.queryWakeWord()} />
        <Btn title="Enable wake word" onPress={() => Glass.setWakeWord(true)} />
        <Btn title="Disable wake word" color="#e76f51" onPress={() => Glass.setWakeWord(false)} />
        <Text style={k.sub}>
          On/off only — the SDK exposes no way to set a custom phrase (same limitation as the Cyan
          glasses).
        </Text>
      </Section>

      <Section title="Wear detection">
        <Row label="Reported state" value={wear === null ? '—' : wear ? 'enabled' : 'disabled'} />
        <Btn title="Query wear check" onPress={() => Glass.queryWearCheck()} />
        <Btn title="Enable wear check" onPress={() => Glass.setWearCheck(true)} />
        <Btn title="Disable wear check" color="#e76f51" onPress={() => Glass.setWearCheck(false)} />
      </Section>

      <Section title="Version probes">
        {VERSION_TYPES.map((t) => (
          <Btn key={t} title={t} color="#456990" onPress={() => Glass.queryDeviceVersion(t)} />
        ))}
        <Text style={k.sub}>
          VerFirmware / VerFirmware1 are the two chips (Jieli / Allwinner). VerGithash gives the
          exact build commit — useful for matching a device against a downloaded image.
        </Text>
      </Section>

      <Section title="Misc">
        <Btn title="Sync time" onPress={() => Glass.syncTime()} />
        <Btn title="Send language (0)" color="#456990" onPress={() => Glass.sendLanguage(0)} />
      </Section>

      <Section title="Danger zone">
        <Btn
          title="Restart device"
          color="#e76f51"
          onPress={() =>
            confirmDestructive('Restart?', 'The glasses will reboot and drop the BLE link.', () =>
              Glass.restart()
            )
          }
        />
        <Btn
          title="Shut down"
          color="#e76f51"
          onPress={() =>
            confirmDestructive('Shut down?', 'You will need to power the glasses on manually.', () =>
              Glass.shutdown()
            )
          }
        />
        <Btn
          title="Remove bond (unpair)"
          color="#e76f51"
          onPress={() =>
            confirmDestructive('Unpair?', 'Removes the bond; you will need to pair again.', () =>
              Glass.removeBond()
            )
          }
        />
        <Btn
          title="FACTORY RESET"
          color="#b23a48"
          onPress={() =>
            confirmDestructive(
              'Factory reset?',
              'This wipes user data on the device. There is no undo.',
              () => Glass.factoryReset()
            )
          }
        />
      </Section>

      <LogPanel lines={lines} onClear={clear} />
    </ScrollView>
  );
}
