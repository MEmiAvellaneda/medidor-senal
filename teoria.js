// Cálculos teóricos de la cátedra (Comunicación de Datos, UTN FRT) aplicados a las mediciones.
// Unidad 3: dBm, RSSI, ruido térmico (kTB) y SNR. Unidad 5: Shannon. Unidad 7: velocidades por generación.
window.TEORIA = (() => {
  const K = 1.38e-23; // constante de Boltzmann [J/K]

  // Unidad 3: P[mW] = 10^(dBm/10)
  const dbmAmW = (dbm) => Math.pow(10, dbm / 10);

  // Muestra una potencia en la unidad más legible (mW, µW, nW, pW, fW)
  function potenciaLegible(mW) {
    const u = [["mW", 1], ["µW", 1e-3], ["nW", 1e-6], ["pW", 1e-9], ["fW", 1e-12]];
    for (const [nombre, f] of u) if (mW >= f) return `${(mW / f).toLocaleString("es-AR", { maximumFractionDigits: 2 })} ${nombre}`;
    return `${(mW / 1e-15).toLocaleString("es-AR", { maximumFractionDigits: 2 })} aW`;
  }

  // Unidad 3: N = k·T·B ; N[dBm] = 10·log10(kTB / 1 mW)
  const ruidoTermicoDbm = (anchoMHz, tempC) => 10 * Math.log10((K * (tempC + 273) * anchoMHz * 1e6) / 1e-3);

  // En 4G/5G Android muestra RSRP: la potencia de UNA subportadora de 15 kHz.
  // Un canal LTE de B MHz tiene 5·B bloques de 12 subportadoras, así que la potencia total
  // (sumando en dB, como en la Unidad 3) es: S = RSRP + 10·log10(12 · 5 · B).
  // En WiFi el RSSI ya es la potencia de todo el canal.
  const subportadoras = (anchoMHz) => 12 * 5 * anchoMHz;
  const potenciaTotalDbm = (dbm, anchoMHz, conexion) =>
    conexion === "WiFi" ? dbm : dbm + 10 * Math.log10(subportadoras(anchoMHz));

  // Unidad 3: SNR[dB] = S[dBm] - N[dBm]
  const snrDb = (dbm, anchoMHz, tempC, conexion) => potenciaTotalDbm(dbm, anchoMHz, conexion) - ruidoTermicoDbm(anchoMHz, tempC);

  // Unidad 5: C = B·log2(1 + S/N)
  const shannonMbps = (anchoMHz, snr) => anchoMHz * Math.log2(1 + Math.pow(10, snr / 10));

  // Rangos de calidad. WiFi: RSSI de la Unidad 3 (sensibilidad típica -82 dBm).
  // Celular: rangos usuales del nivel de señal 4G (RSRP) que muestra Android.
  function calidad(dbm, conexion) {
    const r = conexion === "WiFi"
      ? [[-50, "Excelente"], [-67, "Buena"], [-75, "Regular"], [-82, "Mala"]]
      : [[-80, "Excelente"], [-90, "Buena"], [-100, "Regular"], [-110, "Mala"]];
    for (const [lim, txt] of r) if (dbm >= lim) return txt;
    return "Sin servicio útil";
  }

  // Unidad 7: Ancho de banda digital, servicios WAN inalámbricos (velocidad máxima teórica)
  const MAXIMOS = {
    "2G": { texto: "EDGE: hasta 473,6 Kbps", mbps: 0.4736 },
    "3G": { texto: "UMTS hasta 2 Mbps · HSPA+ hasta 42 Mbps", mbps: 42 },
    "4G": { texto: "LTE: 100 Mbps a 1 Gbps", mbps: 100 },
    "5G": { texto: "5G NR: varios Gbps", mbps: 1000 },
  };

  // Correlación lineal de Pearson
  function pearson(xs, ys) {
    const n = xs.length;
    if (n < 3) return null;
    const mx = xs.reduce((a, b) => a + b, 0) / n, my = ys.reduce((a, b) => a + b, 0) / n;
    let sxy = 0, sxx = 0, syy = 0;
    for (let i = 0; i < n; i++) { sxy += (xs[i] - mx) * (ys[i] - my); sxx += (xs[i] - mx) ** 2; syy += (ys[i] - my) ** 2; }
    return sxx && syy ? sxy / Math.sqrt(sxx * syy) : null;
  }

  return { dbmAmW, potenciaLegible, ruidoTermicoDbm, subportadoras, potenciaTotalDbm, snrDb, shannonMbps, calidad, MAXIMOS, pearson };
})();
