import type { Transaction, TransactionKind, TripLeg } from "../api/types";

// Which kinds a trip can count, mirroring the backend's COUNTABLE_KINDS.
// Costs are negated to read positive, so money moving — payroll,
// transfers, card payments — would quietly subtract from a trip.
export const TRIP_COUNTABLE_KINDS: ReadonlySet<TransactionKind> = new Set<TransactionKind>([
  "expense",
  "uncategorized",
  "refund",
  "reimbursement",
]);

/** Why this row can't be ticked into the trip, or null if it can. */
export function tripBlockReason(tx: Transaction, tripId: number | null): string | null {
  if (tx.trip && tx.trip.trip_id !== tripId) {
    return `Counts toward ${tx.trip.trip_name}. Untick it there first.`;
  }
  if (!TRIP_COUNTABLE_KINDS.has(tx.kind)) {
    return "Money moving rather than spending. A trip counts expenses, refunds and reimbursements.";
  }
  return null;
}

/** A decimal string as an exact integer count of 10^-places units. */
function scaled(value: string, places: number): bigint {
  const negative = value.trim().startsWith("-");
  const [whole, fraction = ""] = value.trim().replace(/^[-+]/, "").split(".");
  const digits = BigInt(whole || "0") * 10n ** BigInt(places) + BigInt((fraction + "0".repeat(places)).slice(0, places) || "0");
  return negative ? -digits : digits;
}

/** What a counted row adds to its trip: the outflow-negative amount
 *  flipped, after the roommate split and the trip's own share. Mirrors
 *  `trips.spend_amount` on the backend to the cent — exact integer
 *  arithmetic and halves rounded away from zero — because this figure
 *  sits next to the total the server computes, and the two must agree. */
export function tripSpend(tx: Transaction): number {
  const cents = -scaled(tx.amount, 2);
  const personal = tx.split ? scaled(tx.split.personal_share, 4) : 10_000n;
  const share = tx.trip ? scaled(tx.trip.share, 4) : 10_000n;
  // cents x 10^4 x 10^4: round back to whole cents, halves away from zero.
  const raw = cents * personal * share;
  const magnitude = ((raw < 0n ? -raw : raw) + 50_000_000n) / 100_000_000n;
  return Number(raw < 0n ? -magnitude : magnitude) / 100;
}

/** The stop whose dates contain `date` — the rule the backend files by. */
export function legForDate(legs: TripLeg[], date: string | null): number | null {
  if (!date) return null;
  return legs.find((leg) => leg.start_date <= date && date <= leg.end_date)?.id ?? null;
}
