import React, { useState } from 'react';
import { ScrollView, Text, TextInput, View } from 'react-native';

import { Btn, LogPanel, Row, Section, k, useConnected, useDeviceLog } from '@/components/GlassKit';
import { Glass } from '@/modules/glass-sdk';

/**
 * Raw probing surface. Everything here goes through the adapter's `sendCommand` seam, so new
 * experiments never require a native rebuild — you add a branch in MoyoungAdapter.sendCommand.
 */
export default function DebugScreen() {
  const connected = useConnected();
  const { lines, clear, log } = useDeviceLog();

  const [command, setCommand] = useState('rawControl');
  const [params, setParams] = useState('{"bytes":[2,1,1]}');
  const [caps, setCaps] = useState<string | null>(null);

  const send = async () => {
    let parsed: Record<string, unknown> = {};
    if (params.trim()) {
      try {
        parsed = JSON.parse(params);
      } catch (e: any) {
        log(`bad JSON params: ${e?.message ?? e}`);
        return;
      }
    }
    log(`> sendCommand(${command}, ${params})`);
    try {
      await Glass.sendCommand(command, parsed);
    } catch (e: any) {
      log(`sendCommand threw: ${e?.message ?? e}`);
    }
  };

  return (
    <ScrollView style={k.container} contentContainerStyle={k.content}>
      <View>
        <Text style={k.h1}>Debug</Text>
        <Text style={k.sub}>Arbitrary command probing and the raw native trace stream.</Text>
      </View>

      <Section title="Environment">
        <Row label="Connected" value={connected ? 'yes' : 'no'} />
        <Btn
          title="Get capabilities"
          color="#456990"
          onPress={async () => {
            const c = await Glass.getCapabilities();
            setCaps(JSON.stringify(c));
            log(`capabilities: ${JSON.stringify(c)}`);
          }}
        />
        <Text style={k.logLine}>{caps ?? '—'}</Text>
        <Btn
          title="Get current state"
          color="#456990"
          onPress={async () => log(`state: ${await Glass.getCurrentState()}`)}
        />
        <Btn
          title="Is Bluetooth enabled"
          color="#456990"
          onPress={async () => log(`bluetooth: ${await Glass.isBluetoothEnabled()}`)}
        />
      </Section>

      <Section title="Raw command">
        <Text style={k.rowLabel}>command</Text>
        <TextInput
          style={input}
          value={command}
          onChangeText={setCommand}
          autoCapitalize="none"
          placeholder="rawControl"
          placeholderTextColor="#4a5568"
        />
        <Text style={k.rowLabel}>params (JSON)</Text>
        <TextInput
          style={[input, { minHeight: 80 }]}
          value={params}
          onChangeText={setParams}
          autoCapitalize="none"
          multiline
          placeholder='{"bytes":[2,1,1]}'
          placeholderTextColor="#4a5568"
        />
        <Btn title="Send" onPress={send} disabled={!connected} />
        <Text style={k.sub}>
          Wire new probes as branches in MoyoungAdapter.sendCommand. Candidates worth trying: the
          shared-CRP opcodes the glasses API does not expose (alarms, hardware self-test, SOS
          state, vibration level) — the firmware may still answer them.
        </Text>
      </Section>

      <Section title="Quick probes">
        <Btn title="queryFeatureState" color="#456990" onPress={() => Glass.queryFeatureState()} />
        <Btn title="queryBattery" color="#456990" onPress={() => Glass.queryBattery()} />
        <Btn title="queryDeviceId" color="#456990" onPress={() => Glass.queryDeviceId()} />
        <Btn title="queryWakeWord" color="#456990" onPress={() => Glass.queryWakeWord()} />
        <Btn title="queryWearCheck" color="#456990" onPress={() => Glass.queryWearCheck()} />
        <Btn title="queryAudioState" color="#456990" onPress={() => Glass.queryAudioState()} />
        <Btn title="queryVideoConfig" color="#456990" onPress={() => Glass.queryVideoConfig()} />
        <Btn title="queryNewMediaFile" color="#456990" onPress={() => Glass.queryNewMediaFile()} />
      </Section>

      <LogPanel lines={lines} onClear={clear} />
    </ScrollView>
  );
}

const input = {
  backgroundColor: '#0d1015',
  borderRadius: 8,
  borderWidth: 1,
  borderColor: '#262d36',
  color: '#e6edf3',
  paddingHorizontal: 12,
  paddingVertical: 10,
  fontSize: 13,
  fontFamily: 'monospace' as const,
};
