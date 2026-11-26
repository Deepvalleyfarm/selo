/**
 * Cart pricing, shared by the checkout quote and order creation so the buyer pays exactly what they were shown.
 * Applies, per product, the better of the listing's own discount and an active Marketing Centre campaign, then
 * Buy-One-Get-One-Free units, then bundle prices. Campaigns never stack on the same unit.
 */
export type CampaignType = "DISCOUNT" | "BOGO" | "FLASH" | "BUNDLE";
export interface Campaign {
  id: string; sellerUid: string; type: CampaignType; title: string; listingIds: string[]; status: string;
  discountPct?: number; bundlePriceNgwee?: number; startsAt: number; endsAt: number | null; maxUnits?: number | null; unitsSold?: number;
}
export interface CartLine { listingId: string; title: string; qty: number; priceNgwee: number; discountPct?: number; discountNgwee?: number }
export interface PricedLine { listingId: string; title: string; qty: number; listNgwee: number; unitNgwee: number; freeQty: number; lineNgwee: number; promo: string | null }
export interface Applied { campaignId: string; type: CampaignType; title: string; savingNgwee: number }
export interface PricedCart { lines: PricedLine[]; fullNgwee: number; goodsNgwee: number; savingsNgwee: number; applied: Applied[] }

export function isLive(c: Campaign, nowMs: number) {
  return c.status === "ACTIVE" && c.startsAt <= nowMs && (c.endsAt == null || nowMs < c.endsAt) && (c.maxUnits == null || (c.unitsSold ?? 0) < c.maxUnits);
}

/** Listing's own price after its built-in discount (same rule as the app's Listing.finalPriceNgwee). */
export function listingUnit(l: CartLine) {
  if ((l.discountNgwee ?? 0) > 0) return Math.max(0, l.priceNgwee - l.discountNgwee!);
  if ((l.discountPct ?? 0) > 0) return Math.round(l.priceNgwee * (1 - Math.min(90, l.discountPct!) / 100));
  return l.priceNgwee;
}

export function priceCart(cart: CartLine[], campaigns: Campaign[], nowMs = Date.now()): PricedCart {
  const live = campaigns.filter((c) => isLive(c, nowMs));
  const applied = new Map<string, Applied>();
  const credit = (c: Campaign, n: number) => { if (n <= 0) return; const a = applied.get(c.id) ?? { campaignId: c.id, type: c.type, title: c.title, savingNgwee: 0 }; a.savingNgwee += n; applied.set(c.id, a); };

  const lines: PricedLine[] = cart.map((l) => {
    const qty = Math.max(1, Math.floor(l.qty));
    let unit = listingUnit(l), promo: string | null = null;
    // Percentage campaigns (discount / limited-time offer): take the best price, never both.
    for (const c of live.filter((c) => (c.type === "DISCOUNT" || c.type === "FLASH") && c.listingIds.includes(l.listingId))) {
      const p = Math.round(l.priceNgwee * (1 - Math.min(90, Math.max(1, c.discountPct ?? 0)) / 100));
      if (p < unit) { unit = p; promo = c.id; }
    }
    if (promo) credit(live.find((c) => c.id === promo)!, (listingUnit(l) - unit) * qty);
    // Buy one, get one free: every second unit is free (applied to the item's normal price; doesn't stack on a % deal).
    let free = 0;
    const bogo = !promo ? live.find((c) => c.type === "BOGO" && c.listingIds.includes(l.listingId)) : undefined;
    if (bogo) { free = Math.floor(qty / 2); promo = bogo.id; credit(bogo, free * unit); }
    return { listingId: l.listingId, title: l.title, qty, listNgwee: l.priceNgwee, unitNgwee: unit, freeQty: free, lineNgwee: (qty - free) * unit, promo };
  });

  // Bundles: every complete set of the bundle's products (not already on another deal) costs the bundle price.
  for (const b of live.filter((c) => c.type === "BUNDLE" && c.listingIds.length >= 2 && (c.bundlePriceNgwee ?? 0) > 0)) {
    const members = b.listingIds.map((id) => lines.find((x) => x.listingId === id && !x.promo));
    if (members.some((m) => !m)) continue;
    const sets = Math.min(...members.map((m) => m!.qty));
    const setPrice = members.reduce((s, m) => s + m!.unitNgwee, 0);
    const saving = Math.max(0, setPrice - b.bundlePriceNgwee!) * sets;
    if (saving <= 0) continue;
    // spread the saving over the members in proportion to price so per-line totals stay meaningful
    let left = saving;
    members.forEach((m, i) => { const share = i === members.length - 1 ? left : Math.round(saving * m!.unitNgwee / setPrice); left -= share; m!.lineNgwee -= share; m!.promo = b.id; });
    credit(b, saving);
  }
  const fullNgwee = lines.reduce((s, l) => s + l.qty * l.listNgwee, 0);
  const goodsNgwee = lines.reduce((s, l) => s + l.lineNgwee, 0);
  return { lines, fullNgwee, goodsNgwee, savingsNgwee: fullNgwee - goodsNgwee, applied: [...applied.values()] };
}

/** Short label buyers see on product cards. */
export function promoLabel(c: Pick<Campaign, "type" | "discountPct" | "title">): string {
  switch (c.type) {
    case "BOGO": return "Buy 1 Get 1 Free";
    case "FLASH": return `Limited time · ${c.discountPct ?? 0}% off`;
    case "BUNDLE": return "Bundle deal";
    default: return `${c.discountPct ?? 0}% off`;
  }
}

/** Seller input checks (also enforced in the app). */
export function campaignProblem(c: Partial<Campaign>, nowMs = Date.now()): string | null {
  if (!c.type || !["DISCOUNT", "BOGO", "FLASH", "BUNDLE"].includes(c.type)) return "Choose a campaign type.";
  if (!c.title || c.title.trim().length < 3) return "Give the campaign a name.";
  const ids = c.listingIds ?? [];
  if (!ids.length) return "Choose at least one product.";
  if (c.type === "BUNDLE" && (ids.length < 2 || ids.length > 5)) return "A bundle needs 2 to 5 products.";
  if ((c.type === "DISCOUNT" || c.type === "FLASH") && !((c.discountPct ?? 0) >= 1 && (c.discountPct ?? 0) <= 90)) return "Set a discount between 1% and 90%.";
  if (c.type === "BUNDLE" && !((c.bundlePriceNgwee ?? 0) > 0)) return "Set the bundle price.";
  if (c.endsAt != null && c.endsAt <= (c.startsAt ?? nowMs)) return "The end time must be after the start.";
  if (c.type === "FLASH" && (c.endsAt == null || c.endsAt - (c.startsAt ?? nowMs) > 7 * 86_400_000)) return "A limited-time offer must end within 7 days.";
  return null;
}
