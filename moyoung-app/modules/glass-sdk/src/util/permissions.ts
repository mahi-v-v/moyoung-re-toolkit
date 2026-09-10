import { Alert, Linking, PermissionsAndroid, Platform } from 'react-native';

import { Glass } from '../native/Glass';

export interface PermissionResult {
  granted: boolean;
  error?: string;
}

export type ReadyReason = 'ok' | 'permission' | 'bluetooth-off';

export interface ReadyResult {
  ok: boolean;
  reason: ReadyReason;
  error?: string;
}

/**
 * Android 12+ splits Bluetooth into BLUETOOTH_SCAN / BLUETOOTH_CONNECT and no longer requires
 * location for scanning *if* you declare neverForLocation — we don't, so location is still
 * requested. NEARBY_WIFI_DEVICES (13+) is needed for the device Wi-Fi AP used by media download
 * and the Allwinner OTA.
 *
 * The native scanner silently no-ops without BLUETOOTH_SCAN, so always call this before startScan.
 */
export async function requestBluetoothPermissions(): Promise<PermissionResult> {
  if (Platform.OS !== 'android') return { granted: true };

  try {
    const perms: string[] = [];
    const api = typeof Platform.Version === 'number' ? Platform.Version : parseInt(String(Platform.Version), 10);

    if (api >= 31) {
      perms.push(
        PermissionsAndroid.PERMISSIONS.BLUETOOTH_SCAN,
        PermissionsAndroid.PERMISSIONS.BLUETOOTH_CONNECT
      );
    }
    perms.push(PermissionsAndroid.PERMISSIONS.ACCESS_FINE_LOCATION);
    if (api >= 33 && (PermissionsAndroid.PERMISSIONS as any).NEARBY_WIFI_DEVICES) {
      perms.push((PermissionsAndroid.PERMISSIONS as any).NEARBY_WIFI_DEVICES);
    }

    const result = await PermissionsAndroid.requestMultiple(perms as any);
    const denied = Object.entries(result).filter(
      ([, v]) => v !== PermissionsAndroid.RESULTS.GRANTED
    );

    if (denied.length > 0) {
      return { granted: false, error: `denied: ${denied.map(([k]) => k).join(', ')}` };
    }
    return { granted: true };
  } catch (e: any) {
    return { granted: false, error: e?.message ?? String(e) };
  }
}

/**
 * The one call to make before scanning or connecting: ensures BLE permissions are granted AND
 * Bluetooth is actually switched on, prompting the user for each in turn. Returns a structured
 * result so the caller can show the right message.
 */
export async function ensureBluetoothReady(): Promise<ReadyResult> {
  const perm = await requestBluetoothPermissions();
  if (!perm.granted) {
    return { ok: false, reason: 'permission', error: perm.error };
  }

  // Permission is needed before we may query/enable the adapter on Android 12+.
  let on = await Glass.isBluetoothEnabled().catch(() => false);
  if (!on) {
    // Pops the system enable dialog; the user's choice isn't returned, so re-check after.
    await Glass.requestEnableBluetooth().catch(() => false);
    on = await Glass.isBluetoothEnabled().catch(() => false);
  }
  if (!on) {
    return { ok: false, reason: 'bluetooth-off' };
  }

  return { ok: true, reason: 'ok' };
}

export function showBluetoothOffAlert() {
  Alert.alert(
    'Bluetooth is off',
    'Turn Bluetooth on to scan for and connect to the glasses.',
    [
      { text: 'Cancel', style: 'cancel' },
      { text: 'Open settings', onPress: () => Linking.openSettings() },
    ]
  );
}

export function showPermissionDeniedAlert() {
  Alert.alert(
    'Permissions required',
    'Bluetooth (and nearby-devices) permissions are needed to scan for and connect to the glasses.',
    [
      { text: 'Cancel', style: 'cancel' },
      { text: 'Open settings', onPress: () => Linking.openSettings() },
    ]
  );
}
