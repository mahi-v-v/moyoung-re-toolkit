import type { GlassAdvertisement } from '../native/Glass';

export type GlassVendor = 'moyoung' | 'unknown';

/**
 * Classify a scan result. Done in JS (not native) so the rule can be changed with a JS reload
 * instead of a native rebuild — important while you're still learning what the device advertises.
 *
 * ⚠️ UNVERIFIED: the marker set below is a starting heuristic. We have not yet captured a real
 * MoYoung advertisement, and the vendor SDK builds its service UUIDs in obfuscated code (only the
 * CCCD 0x2902 is hardcoded — see docs/04_BLE_Protocol_Reference.md).
 *
 * To pin this down: scan with `showAll` enabled in the Connect tab, find your glasses, and read
 * the raw advertisement in the Debug tab. Then add the real service UUID / manufacturer ID here.
 * Prefer advertisement markers over device names — names are user-mutable.
 */

/** Name prefixes seen on MoYoung-family devices. Extend as you learn more. */
const NAME_HINTS = ['moyoung', 'my-glass', 'myglass', 'glasses', 'crp'];

/** Service UUIDs / manufacturer IDs — fill in once captured from a real device. */
const SERVICE_UUID_HINTS: string[] = [];
const MANUFACTURER_ID_HINTS: string[] = [];

export function classifyVendor(
  adv: GlassAdvertisement | undefined,
  name?: string
): GlassVendor {
  const deviceName = (name ?? adv?.name ?? '').toLowerCase();

  if (NAME_HINTS.some((h) => deviceName.includes(h))) return 'moyoung';

  const uuids = (adv?.serviceUuids ?? []).map((u) => u.toLowerCase());
  if (SERVICE_UUID_HINTS.some((h) => uuids.some((u) => u.includes(h)))) return 'moyoung';

  const mfg = Object.keys(adv?.manufacturerData ?? {}).map((k) => k.toLowerCase());
  if (MANUFACTURER_ID_HINTS.some((h) => mfg.includes(h))) return 'moyoung';

  return 'unknown';
}

/**
 * The MoYoung scan record additionally encodes firmware type / battery / charging
 * (vendor-side: `CRPScanRecordParser.parseScanRecord`). Once you've captured a real advert and
 * worked out the layout, decode it here so the scan list can show battery before connecting.
 */
export function parseMoyoungScanRecord(
  _rawHex: string | undefined
): { firmwareType?: string; battery?: number; isCharging?: boolean } | undefined {
  // TODO: implement once the advertisement layout is confirmed on a real device.
  return undefined;
}
