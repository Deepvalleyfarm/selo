const P: Record<string, string> = {
  home: "M3 11l9-7 9 7v9a1 1 0 0 1-1 1h-5v-6h-6v6H4a1 1 0 0 1-1-1z",
  users: "M9 11.5a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7zM2 20c1-3.5 3.8-5 7-5s6 1.5 7 5M17 11.5a2.5 2.5 0 1 0 0-5M17 14c2.5 0 4 1.5 5 4",
  idcard: "M3 5h18v14H3zM9 13a2 2 0 1 0 0-4 2 2 0 0 0 0 4zM6 16c.8-1.5 1.8-2 3-2s2.2.5 3 2M14 10h4M14 13h3",
  box: "M3 7l9-4 9 4v10l-9 4-9-4zM3 7l9 4 9-4M12 11v10",
  bike: "M6 20a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM18 20a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM6 17l4-7h5l3 7M10 10l-2-3H6M15 10l-1-3h3",
  pin: "M12 21s7-6 7-11a7 7 0 0 0-14 0c0 5 7 11 7 11zM12 12.5a2.5 2.5 0 1 0 0-5 2.5 2.5 0 0 0 0 5z",
  coins: "M9 10c3.3 0 6-1.3 6-3s-2.7-3-6-3-6 1.3-6 3 2.7 3 6 3zM3 7v5c0 1.7 2.7 3 6 3s6-1.3 6-3V7M15 11c3.3 0 6 1.3 6 3v4c0 1.7-2.7 3-6 3-2 0-3.8-.5-4.9-1.3",
  gavel: "M14 4l6 6M11 7l6 6M12.5 5.5l-6 6 3 3 6-6M8 13l-5 5 2 2 5-5M4 21h9",
  heart: "M12 20s-8-4.5-8-10a4.5 4.5 0 0 1 8-2.8A4.5 4.5 0 0 1 20 10c0 5.5-8 10-8 10z",
  flag: "M5 21V4M5 4h11l-2 4 2 4H5",
  image: "M3 4h18v16H3zM9 12a2 2 0 1 0 0-4 2 2 0 0 0 0 4zM21 16l-5-5-9 9",
  send: "M3 11l18-8-8 18-2-8z",
  sliders: "M4 6h10M18 6h2M4 12h4M12 12h8M4 18h12M16 8a2 2 0 1 0 0-4 2 2 0 0 0 0 4zM10 14a2 2 0 1 0 0-4 2 2 0 0 0 0 4zM18 20a2 2 0 1 0 0-4 2 2 0 0 0 0 4z",
  report: "M6 3h9l4 4v14H6zM9 13h6M9 17h6M9 9h3",
  log: "M4 6h16M4 12h16M4 18h10",
  lock: "M5 10h14v10H5zM8 10V7a4 4 0 0 1 8 0v3",
  search: "M11 18a7 7 0 1 0 0-14 7 7 0 0 0 0 14zM20 20l-3.5-3.5",
  bell: "M6 16V11a6 6 0 0 1 12 0v5l2 2H4zM10 21h4",
  alert: "M12 4l9 16H3zM12 10v4M12 17v.5",
  close: "M6 6l12 12M18 6L6 18",
};
export const Icon = ({ n, size = 16 }: { n: string; size?: number }) => (
  <svg className="ico" viewBox="0 0 24 24" width={size} height={size} aria-hidden="true"><path d={P[n] ?? ""} /></svg>
);
export const Logo = () => (
  <svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true">
    <path d="M5 7h14l-1.4 11.2a2 2 0 0 1-2 1.8H8.4a2 2 0 0 1-2-1.8z" fill="#CC0023" />
    <path d="M9 7V5.5a3 3 0 0 1 6 0V7" stroke="#CC0023" strokeWidth="2" fill="none" />
    <path d="M8.5 12.5l2.4 2.4 4.6-4.6" stroke="#fff" strokeWidth="2" fill="none" strokeLinecap="round" strokeLinejoin="round" />
  </svg>
);
