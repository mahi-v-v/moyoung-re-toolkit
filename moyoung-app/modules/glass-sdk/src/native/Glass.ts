import { requireNativeModule } from 'expo-modules-core';

/**
 * Typed JS surface over the `GlassModule` Expo native module.
 *
 * Mirrors modules/glass-sdk/android/.../GlassModule.kt 1:1. Command names, event names and the
 * connection-state enum must stay in sync with the Kotlin side.
 */
const GlassModule = requireNativeModule('GlassModule');

// ── state ────────────────────────────────────────────────────────────────────

export enum GlassConnectionState {
  IDLE = 'IDLE',
  SCANNING = 'SCANNING',
  CONNECTING = 'CONNECTING',
  CONNECTED = 'CONNECTED',
  DISCONNECTING = 'DISCONNECTING',
  ERROR = 'ERROR',
}

// ── events ───────────────────────────────────────────────────────────────────

export const GlassEvents = {
  DEVICE_FOUND: 'GLASS_DEVICE_FOUND',
  STATE_CHANGED: 'GLASS_STATE_CHANGED',
  CONNECTED: 'GLASS_CONNECTED',
  DISCONNECTED: 'GLASS_DISCONNECTED',
  READY: 'GLASS_READY',
  ERROR: 'GLASS_ERROR',
  BATTERY: 'GLASS_BATTERY',
  WEAR_STATUS: 'GLASS_WEAR_STATUS',
  WAKEWORD_STATUS: 'GLASS_WAKEWORD_STATUS',
  DEVICE_VERSION: 'GLASS_DEVICE_VERSION',
  DEVICE_ID: 'GLASS_DEVICE_ID',
  FEATURE_STATE: 'GLASS_FEATURE_STATE',
  WIFI_STATE: 'GLASS_WIFI_STATE',
  MEDIA_COUNT: 'GLASS_MEDIA_COUNT',
  DOWNLOAD_PROGRESS: 'GLASS_DOWNLOAD_PROGRESS',
  DOWNLOAD_COMPLETE: 'GLASS_DOWNLOAD_COMPLETE',
  OTA_PROGRESS: 'GLASS_OTA_PROGRESS',
  OTA_STATE: 'GLASS_OTA_STATE',
  AI_STATE: 'GLASS_AI_STATE',
  AI_AUDIO: 'GLASS_AI_AUDIO',
  AI_IMAGE: 'GLASS_AI_IMAGE',
  TRANSLATION_AUDIO: 'GLASS_TRANSLATION_AUDIO',
  AUDIO_STATE: 'GLASS_AUDIO_STATE',
  VIDEO_CONFIG: 'GLASS_VIDEO_CONFIG',
  FIRMWARE_INFO: 'GLASS_FIRMWARE_INFO',
} as const;

// ── payload types ────────────────────────────────────────────────────────────

export interface GlassAdvertisement {
  name?: string;
  serviceUuids?: string[];
  serviceData?: Record<string, string>;
  manufacturerData?: Record<string, string>;
  rawAdvertisement?: string;
}

export interface DeviceFoundEvent {
  deviceId: string;
  deviceName: string;
  rssi: number;
  advertisement?: GlassAdvertisement;
}

export interface StateChangedEvent { newState: GlassConnectionState; reason?: string }
export interface ConnectedEvent { deviceId: string; deviceName?: string }
export interface DisconnectedEvent { reason: string; wasExpected: boolean }
export interface ErrorEvent { message: string; isFatal: boolean }
export interface BatteryEvent { level: number; isCharging: boolean; voltage?: string }
export interface ToggleEvent { enabled: boolean }
export interface DeviceVersionEvent { type: string; version: string; tpVersion: number }
export interface DeviceIdEvent { deviceId: string }
export interface FeatureStateEvent { state: string }
export interface WifiStateEvent { wifiType?: string; state: number; connected: boolean; message?: string }
export interface MediaCountEvent { photoCount: number; videoCount: number; detail?: string }
export interface DownloadProgressEvent { progress: number; total: number }
export interface DownloadCompleteEvent { dirPath: string; files: string }
export interface OtaProgressEvent { progress: number; phase?: string }
export interface OtaStateEvent { state: string; errorCode: number; message?: string }
export interface AiStateEvent { state: string; isTimeout: boolean }
export interface AudioStateEvent { recording: boolean; duration: number }
export interface VideoConfigEvent { fps: number; maxDuration: number }
export interface FirmwareInfoEvent { info: string }

export interface CommandResult { success: boolean; errorCode?: number; message?: string }

/** VersionInfo.VersionType — VerFirmware/VerFirmware1 are the two chips. */
export type VersionType =
  | 'VerFirmware'
  | 'VerFirmware1'
  | 'VerProtocol'
  | 'VerGithash'
  | 'VerTpVersion'
  | 'VerTpCheckSum';

export type PhotoMode = 'ModeNormal' | 'ModeAIRecognition' | 'ModeContinuous';
export type WifiType = 'FILE' | 'OTA' | 'LIVE';
export type FlowStatusType = 'FlowStatusStart' | 'FlowStatusComplete' | 'FlowStatusInterrupt';

// ── API ──────────────────────────────────────────────────────────────────────

export const Glass = {
  // environment
  isBluetoothEnabled: (): Promise<boolean> => GlassModule.isBluetoothEnabled(),
  /** Pops the system dialog to turn Bluetooth on. Resolves true if BT is (already) on. */
  requestEnableBluetooth: (): Promise<boolean> => GlassModule.requestEnableBluetooth(),
  /** Whether the phone's Wi-Fi radio is on — required for the Vision OTA to join the glasses' AP. */
  isWifiEnabled: (): Promise<boolean> => GlassModule.isWifiEnabled(),
  /** Opens the system Wi-Fi toggle (panel on Android 10+). Apps can't enable Wi-Fi programmatically. */
  requestEnableWifi: (): Promise<boolean> => GlassModule.requestEnableWifi(),
  getCurrentState: (): Promise<GlassConnectionState> => GlassModule.getCurrentState(),
  isConnected: (): Promise<boolean> => GlassModule.isConnected(),
  getCapabilities: (): Promise<Record<string, boolean>> => GlassModule.getCapabilities(),

  // discovery / connection
  /** Vendor scan — decodes MoYoung's advert (firmwareType, battery, isCharging). */
  startScan: (): Promise<boolean> => GlassModule.startScan(),
  stopScan: (): Promise<boolean> => GlassModule.stopScan(),
  /** Raw-BLE sweep showing EVERY advertising device. Fallback when the vendor scan misses one. */
  startGenericScan: (): Promise<boolean> => GlassModule.startGenericScan(),
  stopGenericScan: (): Promise<boolean> => GlassModule.stopGenericScan(),
  connect: (deviceId: string, vendor = 'moyoung', name?: string): Promise<boolean> =>
    GlassModule.connect(deviceId, vendor, name ?? null),
  disconnect: (): Promise<boolean> => GlassModule.disconnect(),

  // device info
  syncTime: (): Promise<boolean> => GlassModule.syncTime(),
  queryBattery: (): Promise<boolean> => GlassModule.queryBattery(),
  queryDeviceVersion: (type: VersionType): Promise<boolean> => GlassModule.queryDeviceVersion(type),
  queryDeviceId: (): Promise<boolean> => GlassModule.queryDeviceId(),
  queryFeatureState: (): Promise<boolean> => GlassModule.queryFeatureState(),

  // settings
  setWakeWord: (enabled: boolean): Promise<boolean> => GlassModule.setWakeWord(enabled),
  queryWakeWord: (): Promise<boolean> => GlassModule.queryWakeWord(),
  setWearCheck: (enabled: boolean): Promise<boolean> => GlassModule.setWearCheck(enabled),
  queryWearCheck: (): Promise<boolean> => GlassModule.queryWearCheck(),
  sendLanguage: (language: number): Promise<boolean> => GlassModule.sendLanguage(language),

  // capture
  takePhoto: (mode: PhotoMode = 'ModeNormal'): Promise<boolean> => GlassModule.takePhoto(mode),
  startAudio: (seconds = 0): Promise<boolean> => GlassModule.startAudio(seconds),
  stopAudio: (): Promise<boolean> => GlassModule.stopAudio(),
  queryAudioState: (): Promise<boolean> => GlassModule.queryAudioState(),
  queryVideoConfig: (): Promise<boolean> => GlassModule.queryVideoConfig(),
  sendVideoConfig: (fps: number, maxDuration: number): Promise<boolean> =>
    GlassModule.sendVideoConfig(fps, maxDuration),

  // wi-fi + media
  enableWifi: (type: WifiType): Promise<boolean> => GlassModule.enableWifi(type),
  disableWifi: (): Promise<boolean> => GlassModule.disableWifi(),
  connectWifi: (): Promise<boolean> => GlassModule.connectWifi(),
  queryNewMediaFile: (): Promise<boolean> => GlassModule.queryNewMediaFile(),
  downloadMediaFile: (): Promise<boolean> => GlassModule.downloadMediaFile(),
  downloadLogFile: (): Promise<boolean> => GlassModule.downloadLogFile(),
  /**
   * One-call device-log pull: arms the FILE Wi-Fi chain enableWifi(FILE) → connectWifi() →
   * downloadLogFile(), then dumps the pulled log's contents to the device log / Metro. Our
   * teardown-free probe for a relayed swupdate/OTA abort reason after a failed Vision flash.
   */
  pullDeviceLogAuto: (): Promise<boolean> => GlassModule.sendCommand('pullDeviceLogAuto', {}),

  // OTA
  checkFirmware: (mac: string, fw1 = '', fw2 = ''): Promise<boolean> =>
    GlassModule.checkFirmware(mac, fw1, fw2),
  startJieliOta: (filePath?: string): Promise<boolean> =>
    GlassModule.startJieliOta(filePath ?? null),
  abortJieliOta: (): Promise<boolean> => GlassModule.abortJieliOta(),
  startAllwinnerOta: (filePath: string): Promise<boolean> => GlassModule.startAllwinnerOta(filePath),
  /**
   * One-call Allwinner/Vision OTA: arms the vendor-documented auto-chain
   * enableWifi(OTA) → connectWifi() → startAllWinnerOta(file). Preferred over the manual steps.
   */
  startVisionOtaAuto: (filePath: string): Promise<boolean> =>
    GlassModule.sendCommand('startVisionOtaAuto', { path: filePath }),

  /**
   * Laptop-harness Vision OTA: brings up the glasses AP over BLE but does NOT let the phone join —
   * a laptop takes the 192.168.31.2 server slot so we can capture who truncates the transfer. The
   * flow pauses at OTA state `awaitingExternalServer`; join+start the laptop, then call
   * {@link continueVisionOtaExternal}. See tools/vision-ota-harness/.
   */
  startVisionOtaExternal: (filePath: string): Promise<boolean> =>
    GlassModule.sendCommand('startVisionOtaExternal', { path: filePath }),

  /** Fire the BLE OTA trigger once the laptop server + Wireshark are running (external-server mode). */
  continueVisionOtaExternal: (): Promise<boolean> =>
    GlassModule.sendCommand('continueVisionOtaExternal', {}),
  resumeAllwinnerOta: (): Promise<boolean> => GlassModule.resumeAllwinnerOta(),
  abortAllwinnerOta: (): Promise<boolean> => GlassModule.abortAllwinnerOta(),

  /** Firmware bundled into the APK (module android assets/firmware/). */
  listBundledFirmware: (): Promise<string[]> => GlassModule.listBundledFirmware(),
  /** Copy a bundled build to a real path and return it (feed into start*Ota). */
  resolveBundledFirmware: (name: string): Promise<string> =>
    GlassModule.resolveBundledFirmware(name),

  // device management (destructive)
  restart: (): Promise<boolean> => GlassModule.restart(),
  factoryReset: (): Promise<boolean> => GlassModule.factoryReset(),
  shutdown: (): Promise<boolean> => GlassModule.shutdown(),
  removeBond: (): Promise<boolean> => GlassModule.removeBond(),

  // AI / translation
  startTranslation: (): Promise<boolean> => GlassModule.startTranslation(),
  pauseTranslation: (): Promise<boolean> => GlassModule.pauseTranslation(),
  stopTranslation: (): Promise<boolean> => GlassModule.stopTranslation(),
  sendAiDialogueState: (type: FlowStatusType): Promise<boolean> =>
    GlassModule.sendAiDialogueState(type),
  exitAiDialogue: (): Promise<boolean> => GlassModule.exitAiDialogue(),

  /** Extension seam — arbitrary/undocumented pokes. See MoyoungAdapter.sendCommand. */
  sendCommand: (command: string, params: Record<string, unknown> = {}): Promise<boolean> =>
    GlassModule.sendCommand(command, params),
};

// ── event subscription ───────────────────────────────────────────────────────

export interface Subscription { remove(): void }

export function subscribeToGlassEvents(
  name: string,
  handler: (payload: any) => void
): Subscription {
  return GlassModule.addListener(name, handler);
}

function parseAdvertisement(raw: unknown): GlassAdvertisement | undefined {
  if (typeof raw !== 'string' || !raw) return undefined;
  try {
    return JSON.parse(raw) as GlassAdvertisement;
  } catch {
    return undefined;
  }
}

/** Typed helpers, one per event. */
export const GlassEventSubscriptions = {
  onDeviceFound: (h: (e: DeviceFoundEvent) => void) =>
    subscribeToGlassEvents(GlassEvents.DEVICE_FOUND, (raw: any) =>
      h({ ...raw, advertisement: parseAdvertisement(raw?.advertisement) })
    ),
  onStateChanged: (h: (e: StateChangedEvent) => void) =>
    subscribeToGlassEvents(GlassEvents.STATE_CHANGED, h),
  onConnected: (h: (e: ConnectedEvent) => void) =>
    subscribeToGlassEvents(GlassEvents.CONNECTED, h),
  onDisconnected: (h: (e: DisconnectedEvent) => void) =>
    subscribeToGlassEvents(GlassEvents.DISCONNECTED, h),
  onReady: (h: (e: { deviceId: string }) => void) =>
    subscribeToGlassEvents(GlassEvents.READY, h),
  /** Also the generic native trace channel — see BaseGlassAdapter.trace. */
  onError: (h: (e: ErrorEvent) => void) => subscribeToGlassEvents(GlassEvents.ERROR, h),
  onBattery: (h: (e: BatteryEvent) => void) => subscribeToGlassEvents(GlassEvents.BATTERY, h),
  onWearStatus: (h: (e: ToggleEvent) => void) =>
    subscribeToGlassEvents(GlassEvents.WEAR_STATUS, h),
  onWakeWordStatus: (h: (e: ToggleEvent) => void) =>
    subscribeToGlassEvents(GlassEvents.WAKEWORD_STATUS, h),
  onDeviceVersion: (h: (e: DeviceVersionEvent) => void) =>
    subscribeToGlassEvents(GlassEvents.DEVICE_VERSION, h),
  onDeviceId: (h: (e: DeviceIdEvent) => void) =>
    subscribeToGlassEvents(GlassEvents.DEVICE_ID, h),
  onFeatureState: (h: (e: FeatureStateEvent) => void) =>
    subscribeToGlassEvents(GlassEvents.FEATURE_STATE, h),
  onWifiState: (h: (e: WifiStateEvent) => void) =>
    subscribeToGlassEvents(GlassEvents.WIFI_STATE, h),
  onMediaCount: (h: (e: MediaCountEvent) => void) =>
    subscribeToGlassEvents(GlassEvents.MEDIA_COUNT, h),
  onDownloadProgress: (h: (e: DownloadProgressEvent) => void) =>
    subscribeToGlassEvents(GlassEvents.DOWNLOAD_PROGRESS, h),
  onDownloadComplete: (h: (e: DownloadCompleteEvent) => void) =>
    subscribeToGlassEvents(GlassEvents.DOWNLOAD_COMPLETE, h),
  onOtaProgress: (h: (e: OtaProgressEvent) => void) =>
    subscribeToGlassEvents(GlassEvents.OTA_PROGRESS, h),
  onOtaState: (h: (e: OtaStateEvent) => void) =>
    subscribeToGlassEvents(GlassEvents.OTA_STATE, h),
  onAiState: (h: (e: AiStateEvent) => void) => subscribeToGlassEvents(GlassEvents.AI_STATE, h),
  onAiImage: (h: (e: { path: string }) => void) =>
    subscribeToGlassEvents(GlassEvents.AI_IMAGE, h),
  onAudioState: (h: (e: AudioStateEvent) => void) =>
    subscribeToGlassEvents(GlassEvents.AUDIO_STATE, h),
  onVideoConfig: (h: (e: VideoConfigEvent) => void) =>
    subscribeToGlassEvents(GlassEvents.VIDEO_CONFIG, h),
  onFirmwareInfo: (h: (e: FirmwareInfoEvent) => void) =>
    subscribeToGlassEvents(GlassEvents.FIRMWARE_INFO, h),
};
