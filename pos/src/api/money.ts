/**
 * Exact decimal arithmetic over the string `Money` type, mirroring the backend's
 * numeric(19,4) HALF_UP (Appendix D, decision D4). Values are held as a BigInt of
 * ten-thousandths so nothing ever touches a float. Use this for the running "amount
 * due" / change the cashier sees while tendering; the server remains the source of
 * truth for the persisted totals.
 */
import type { Money } from "./types";

const SCALE = 4n;
const UNIT = 10000n; // 10^4

function toUnits(m: Money): bigint {
  const s = (m ?? "0").trim();
  const neg = s.startsWith("-");
  const body = neg ? s.slice(1) : s;
  const [intPart, fracRaw = ""] = body.split(".");
  const frac = (fracRaw + "0000").slice(0, Number(SCALE));
  const units = BigInt(intPart || "0") * UNIT + BigInt(frac || "0");
  return neg ? -units : units;
}

function fromUnits(units: bigint): Money {
  const neg = units < 0n;
  const abs = neg ? -units : units;
  const intPart = abs / UNIT;
  const frac = (abs % UNIT).toString().padStart(Number(SCALE), "0");
  return `${neg ? "-" : ""}${intPart}.${frac}`;
}

export const ZERO: Money = "0.0000";

export function money(value: number | string): Money {
  if (typeof value === "number") {
    // Round to 4 dp via string to avoid float noise.
    return fromUnits(BigInt(Math.round(value * 10000)));
  }
  return fromUnits(toUnits(value));
}

export function add(a: Money, b: Money): Money {
  return fromUnits(toUnits(a) + toUnits(b));
}

export function sub(a: Money, b: Money): Money {
  return fromUnits(toUnits(a) - toUnits(b));
}

export function mul(a: Money, factor: number | string): Money {
  // factor may be a fractional quantity; scale it to 4dp then divide back out.
  const f = toUnits(typeof factor === "number" ? money(factor) : String(factor));
  return fromUnits((toUnits(a) * f) / UNIT);
}

export function cmp(a: Money, b: Money): number {
  const x = toUnits(a);
  const y = toUnits(b);
  return x < y ? -1 : x > y ? 1 : 0;
}

export function isNegative(a: Money): boolean {
  return toUnits(a) < 0n;
}

export function isZero(a: Money): boolean {
  return toUnits(a) === 0n;
}

export function toNumber(a: Money): number {
  return Number(a);
}

/** Retail display: grouped thousands, 2 decimal places, optional currency code. */
export function formatMoney(m: Money, currency?: string): string {
  const units = toUnits(m);
  const neg = units < 0n;
  const abs = neg ? -units : units;
  // Round 4dp -> 2dp HALF_UP for display.
  const cents = (abs + 50n) / 100n; // ten-thousandths -> hundredths, half-up
  const intPart = cents / 100n;
  const frac = (cents % 100n).toString().padStart(2, "0");
  const grouped = intPart.toString().replace(/\B(?=(\d{3})+(?!\d))/g, ",");
  const body = `${neg ? "-" : ""}${grouped}.${frac}`;
  return currency ? `${currency} ${body}` : body;
}
