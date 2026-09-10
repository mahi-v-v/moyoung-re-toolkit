import React, { useEffect, useState } from 'react';
import { ScrollView, Text, View } from 'react-native';

import {
  Btn,
  LogPanel,
  NotConnected,
  Row,
  Section,
  k,
  useConnected,
  useDeviceLog,
} from '@/components/GlassKit';
import { Glass, GlassEventSubscriptions } from '@/modules/glass-sdk';

export default function CaptureScreen() {
  const connected = useConnected();
  const { lines, clear } = useDeviceLog();

  const [audio, setAudio] = useState<{ recording: boolean; duration: number } | null>(null);
  const [video, setVideo] = useState<{ fps: number; maxDuration: number } | null>(null);
  const [media, setMedia] = useState<{ photoCount: number; videoCount: number } | null>(null);
  const [wifi, setWifi] = useState<{ state: number; connected: boolean } | null>(null);
  const [download, setDownload] = useState<number | null>(null);
  const [lastImage, setLastImage] = useState<string | null>(null);

  useEffect(() => {
    const subs = [
      GlassEventSubscriptions.onAudioState((e) =>
        setAudio({ recording: e.recording, duration: e.duration })
      ),
      GlassEventSubscriptions.onVideoConfig((e) =>
        setVideo({ fps: e.fps, maxDuration: e.maxDuration })
      ),
      GlassEventSubscriptions.onMediaCount((e) =>
        setMedia({ photoCount: e.photoCount, videoCount: e.videoCount })
      ),
      GlassEventSubscriptions.onWifiState((e) => setWifi({ state: e.state, connected: e.connected })),
      GlassEventSubscriptions.onDownloadProgress((e) => setDownload(e.progress)),
      GlassEventSubscriptions.onAiImage((e) => setLastImage(e.path)),
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
        <Text style={k.h1}>Capture</Text>
        <Text style={k.sub}>Photo, video, audio, and pulling media off the device over Wi-Fi.</Text>
      </View>

      <Section title="Photo">
        <Btn title="Take photo (normal)" onPress={() => Glass.takePhoto('ModeNormal')} />
        <Btn
          title="Take photo (AI recognition)"
          color="#456990"
          onPress={() => Glass.takePhoto('ModeAIRecognition')}
        />
        <Btn
          title="Take photo (continuous)"
          color="#456990"
          onPress={() => Glass.takePhoto('ModeContinuous')}
        />
        <Row label="Last AI image" value={lastImage ?? '—'} />
        <Text style={k.sub}>
          AI-recognition shots come back as a file via the AI dialogue listener, not as a normal
          media file.
        </Text>
      </Section>

      <Section title="Audio recording">
        <Row
          label="State"
          value={audio ? `${audio.recording ? 'recording' : 'idle'} (${audio.duration}s)` : '—'}
        />
        <Btn title="Query audio state" onPress={() => Glass.queryAudioState()} />
        <Btn title="Start recording (until stopped)" onPress={() => Glass.startAudio(0)} />
        <Btn title="Start recording (30s)" onPress={() => Glass.startAudio(30)} />
        <Btn title="Stop recording" color="#e76f51" onPress={() => Glass.stopAudio()} />
      </Section>

      <Section title="Video config">
        <Row label="Current" value={video ? `${video.fps} fps, max ${video.maxDuration}s` : '—'} />
        <Btn title="Query video config" onPress={() => Glass.queryVideoConfig()} />
        <Btn title="Set 30 fps / 60 s" color="#456990" onPress={() => Glass.sendVideoConfig(30, 60)} />
        <Btn title="Set 15 fps / 120 s" color="#456990" onPress={() => Glass.sendVideoConfig(15, 120)} />
      </Section>

      <Section title="Media files (over Wi-Fi)">
        <Row label="On device" value={media ? `${media.photoCount} photos, ${media.videoCount} videos` : '—'} />
        <Row
          label="Wi-Fi"
          value={wifi ? `state=${wifi.state}, ${wifi.connected ? 'connected' : 'not connected'}` : '—'}
        />
        <Row label="Download" value={download === null ? '—' : `${download}%`} />
        <Btn title="1. Query new media" onPress={() => Glass.queryNewMediaFile()} />
        <Btn title="2. Enable device Wi-Fi (FILE)" onPress={() => Glass.enableWifi('FILE')} />
        <Btn title="3. Connect Wi-Fi" onPress={() => Glass.connectWifi()} />
        <Btn title="4. Download media" onPress={() => Glass.downloadMediaFile()} />
        <Btn title="5. Disable Wi-Fi" color="#e76f51" onPress={() => Glass.disableWifi()} />
        <Text style={k.sub}>
          Order matters: enable → wait for the connected callback (5–10 s) → download → disable.
          Leaving the AP up drains the battery fast.
        </Text>
      </Section>

      <Section title="Device logs">
        <Btn title="Download device log file" color="#456990" onPress={() => Glass.downloadLogFile()} />
        <Text style={k.sub}>
          Pulls the device's own logs over the same Wi-Fi channel — often the fastest way to learn
          what the firmware is actually doing.
        </Text>
      </Section>

      <Section title="Live / translation / AI">
        <Btn title="Enable Wi-Fi (LIVE)" color="#456990" onPress={() => Glass.enableWifi('LIVE')} />
        <Btn title="Start translation" color="#456990" onPress={() => Glass.startTranslation()} />
        <Btn title="Pause translation" color="#456990" onPress={() => Glass.pauseTranslation()} />
        <Btn title="Stop translation" color="#e76f51" onPress={() => Glass.stopTranslation()} />
        <Btn title="Exit AI dialogue" color="#e76f51" onPress={() => Glass.exitAiDialogue()} />
      </Section>

      <LogPanel lines={lines} onClear={clear} />
    </ScrollView>
  );
}
