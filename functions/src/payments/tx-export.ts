/** CSV for the transaction monitor: BOM for Excel, proper quoting of commas/quotes/newlines in messages. */
const COLS: [string, string][] = [["Date/Time", "processedAt"], ["Reference ID", "referenceId"], ["Internal ID", "identifier"], ["External ID (MNO)", "externalId"],
  ["Mobile Number", "accountNumber"], ["Amount", "amount"], ["Currency", "currency"], ["Status", "status"], ["Payment Type", "paymentType"],
  ["Transaction Type", "type"], ["Message", "message"], ["Narration", "referenceData"]];

export const csvCell = (v: unknown): string => {
  const s = v == null ? "" : typeof v === "number" ? v.toFixed(2) : String(v);
  const safe = /^[=+\-@]/.test(s) ? `'${s}` : s; // no formula injection when opened in Excel
  return /[",\r\n]/.test(safe) ? `"${safe.replace(/"/g, '""')}"` : safe;
};

export function buildTransactionsCsv(rows: Record<string, unknown>[]): string {
  return "﻿" + [COLS.map(([h]) => h).join(","), ...rows.map((r) => COLS.map(([, k]) => csvCell(r[k])).join(","))].join("\r\n");
}
