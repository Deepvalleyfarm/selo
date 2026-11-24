export const k = (ngwee: number | undefined | null) =>
  `K ${((ngwee ?? 0) / 100).toLocaleString("en-ZM", { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
export const k0 = (ngwee: number | undefined | null) => `K ${Math.round((ngwee ?? 0) / 100).toLocaleString("en-ZM")}`;

export function when(v: unknown): string {
  const d = toDate(v);
  if (!d) return "—";
  const mins = Math.round((Date.now() - d.getTime()) / 60000);
  if (mins < -1) return mins > -60 ? `in ${-mins} min` : `in ${Math.round(-mins / 60)} h`;
  if (mins < 1) return "just now";
  if (mins < 60) return `${mins} min ago`;
  if (mins < 24 * 60) return `${Math.round(mins / 60)} h ago`;
  return d.toLocaleString("en-GB", { day: "numeric", month: "short", year: "numeric", hour: "2-digit", minute: "2-digit", timeZone: "Africa/Lusaka" });
}

export function toDate(v: unknown): Date | null {
  if (!v) return null;
  if (v instanceof Date) return v;
  if (typeof v === "number") return new Date(v);
  if (typeof v === "string") return new Date(v);
  if (typeof (v as { toDate?: () => Date }).toDate === "function") return (v as { toDate: () => Date }).toDate();
  return null;
}

export const maskMsisdn = (e164?: string) => {
  const n = (e164 ?? "").replace("+260", "");
  return n.length === 9 ? `0${n.slice(0, 2)} ••• ••${n.slice(-2)}` : e164 ?? "";
};

export function toCsv(rows: Record<string, unknown>[]): string {
  if (!rows.length) return "";
  const cols = Object.keys(rows[0]);
  const esc = (v: unknown) => `"${String(v ?? "").replace(/"/g, '""')}"`;
  return [cols.join(","), ...rows.map((r) => cols.map((c) => esc(r[c])).join(","))].join("\n");
}

export function download(name: string, text: string) {
  const url = URL.createObjectURL(new Blob([text], { type: "text/csv" }));
  const a = Object.assign(document.createElement("a"), { href: url, download: name });
  a.click(); URL.revokeObjectURL(url);
}
