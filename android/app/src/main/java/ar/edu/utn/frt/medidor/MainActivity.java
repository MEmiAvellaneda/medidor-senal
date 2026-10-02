package ar.edu.utn.frt.medidor;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.telephony.CellIdentityLte;
import android.telephony.CellInfo;
import android.telephony.CellInfoLte;
import android.telephony.CellSignalStrength;
import android.telephony.CellSignalStrengthLte;
import android.telephony.CellSignalStrengthNr;
import android.telephony.SignalStrength;
import android.telephony.TelephonyManager;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Medidor de Señal - UTN FRT, Comunicación de Datos.
 * Lee automáticamente equipo, red (RSRP, RSRQ, SINR, banda, ancho de canal / WiFi RSSI, canal, estándar),
 * mide velocidad, ubica por GPS, aplica la teoría de la cátedra y envía a la planilla compartida.
 */
public class MainActivity extends Activity {

    // URL de la planilla (Apps Script). Se puede cambiar desde "Configuración" en la app.
    static final String SCRIPT_URL_POR_DEFECTO = "";
    static final String SPEED_HOST = "https://speed.cloudflare.com";
    static final String IP_INFO_URL = "https://ipwho.is/";

    static final String[] CAMPOS = {
            "id", "fecha", "tipo_dispositivo", "nombre_dispositivo", "marca", "modelo", "so", "version_so", "navegador",
            "conexion", "tecnologia", "tipo_red_detectado", "empresa", "isp_detectado", "asn",
            "bajada_mbps", "subida_mbps", "latencia_ms", "jitter_ms",
            "senal_dbm", "barras", "lugar", "lat", "lon", "precision_m", "ubicacion_fuente", "ciudad", "comentario",
            "sinr_db", "ancho_mhz", "origen"};

    // Colores (mismos que la versión web)
    static final int BG = Color.parseColor("#F2F4F7"), SURFACE = Color.WHITE, FG = Color.parseColor("#142030"),
            MUTED = Color.parseColor("#5A6779"), LINE = Color.parseColor("#D8DEE6"), ACCENT = Color.parseColor("#1D55C0"),
            AMBER = Color.parseColor("#B86E00"), GOOD = Color.parseColor("#1D8549"), BAD = Color.parseColor("#BF3A2B");

    final Handler ui = new Handler(Looper.getMainLooper());
    TextView tEquipo, tRed, tVelocidad, tFase, tResultados, tTeoria, tEstado;
    Button bMedir, bEnviar, bCompartir;
    EditText eLugar, eComentario, eUrl;
    CheckBox cConsentimiento;
    final Map<String, Object> medicion = new HashMap<>();
    Red redActual = new Red();
    boolean midiendo = false;
    SharedPreferences prefs;

    // ======================================================================
    // Datos de la red
    // ======================================================================
    static class Red {
        String conexion = "", tecnologia = "", detalle = "", operador = "";
        Integer dbm, rsrq, barras;
        Double sinr, anchoMHz;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("medidor", MODE_PRIVATE);
        construirUI();
        pedirPermisos();
        detectarEquipo();
        refrescarRed.run();
        new Thread(this::detectarISP).start();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacks(refrescarRed);
    }

    void pedirPermisos() {
        List<String> faltan = new ArrayList<>();
        for (String p : new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.READ_PHONE_STATE}) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) faltan.add(p);
        }
        if (!faltan.isEmpty()) requestPermissions(faltan.toArray(new String[0]), 1);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        refrescarRed.run();
    }

    boolean tiene(String permiso) {
        return checkSelfPermission(permiso) == PackageManager.PERMISSION_GRANTED;
    }

    // Lectura en vivo de la señal cada 2 segundos
    final Runnable refrescarRed = new Runnable() {
        @Override
        public void run() {
            try {
                redActual = leerRed();
                mostrarRed(redActual);
            } catch (Exception e) {
                tRed.setText("No se pudo leer la red: " + e.getMessage());
            }
            ui.removeCallbacks(this);
            ui.postDelayed(this, 2000);
        }
    };

    Red leerRed() {
        Red r = new Red();
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
        TelephonyManager tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        r.operador = normalizarOperador(tm.getSimOperatorName());

        if (nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            leerWifi(r);
        } else if (nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            leerCelular(r, tm);
        } else if (nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            r.conexion = "Cable";
        } else {
            r.conexion = "Sin conexión";
        }
        return r;
    }

    @SuppressWarnings("deprecation")
    void leerWifi(Red r) {
        r.conexion = "WiFi";
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        WifiInfo wi = wm.getConnectionInfo();
        int rssi = wi.getRssi(), f = wi.getFrequency();
        r.dbm = rssi;
        r.barras = WifiManager.calculateSignalLevel(rssi, 5);
        String estandar = "";
        if (Build.VERSION.SDK_INT >= 30) {
            switch (wi.getWifiStandard()) {
                case 4: estandar = "WiFi 4 (802.11n)"; break;
                case 5: estandar = "WiFi 5 (802.11ac)"; break;
                case 6: estandar = "WiFi 6 (802.11ax)"; break;
                case 8: estandar = "WiFi 7 (802.11be)"; break;
                case 1: estandar = "802.11a/b/g"; break;
            }
        }
        r.tecnologia = estandar;
        // Ancho del canal: se busca la red conectada entre los resultados del escaneo
        try {
            String bssid = wi.getBSSID();
            for (ScanResult sr : wm.getScanResults()) {
                if (sr.BSSID != null && sr.BSSID.equalsIgnoreCase(bssid)) {
                    int[] anchos = {20, 40, 80, 160, 160, 320};
                    if (sr.channelWidth >= 0 && sr.channelWidth < anchos.length) r.anchoMHz = (double) anchos[sr.channelWidth];
                }
            }
        } catch (SecurityException ignored) { }
        String banda = f >= 5925 ? "6 GHz" : f >= 4900 ? "5 GHz" : "2,4 GHz";
        StringBuilder d = new StringBuilder();
        if (!estandar.isEmpty()) d.append(estandar).append(" · ");
        d.append(banda).append(" · canal ").append(canalWifi(f)).append(" (").append(f).append(" MHz)");
        if (r.anchoMHz != null) d.append(" · ancho ").append(r.anchoMHz.intValue()).append(" MHz");
        d.append(" · enlace ").append(wi.getLinkSpeed()).append(" Mbps");
        if (Build.VERSION.SDK_INT >= 29) d.append(" (rx ").append(wi.getRxLinkSpeedMbps()).append(" / tx ").append(wi.getTxLinkSpeedMbps()).append(")");
        r.detalle = d.toString();
    }

    static int canalWifi(int f) {
        if (f == 2484) return 14;
        if (f >= 2412 && f <= 2472) return (f - 2407) / 5;
        if (f >= 5925) return (f - 5950) / 5;
        if (f >= 4900) return (f - 5000) / 5;
        return 0;
    }

    void leerCelular(Red r, TelephonyManager tm) {
        r.conexion = "Datos móviles";
        int tipo = TelephonyManager.NETWORK_TYPE_UNKNOWN;
        if (tiene(Manifest.permission.READ_PHONE_STATE)) {
            try { tipo = tm.getDataNetworkType(); } catch (SecurityException ignored) { }
        }
        switch (tipo) {
            case TelephonyManager.NETWORK_TYPE_NR: r.tecnologia = "5G"; break;
            case TelephonyManager.NETWORK_TYPE_LTE: r.tecnologia = "4G"; break;
            case TelephonyManager.NETWORK_TYPE_HSPAP: case TelephonyManager.NETWORK_TYPE_HSPA:
            case TelephonyManager.NETWORK_TYPE_HSDPA: case TelephonyManager.NETWORK_TYPE_HSUPA:
            case TelephonyManager.NETWORK_TYPE_UMTS: case TelephonyManager.NETWORK_TYPE_EVDO_0:
            case TelephonyManager.NETWORK_TYPE_EVDO_A: case TelephonyManager.NETWORK_TYPE_EVDO_B:
                r.tecnologia = "3G"; break;
            case TelephonyManager.NETWORK_TYPE_EDGE: case TelephonyManager.NETWORK_TYPE_GPRS:
            case TelephonyManager.NETWORK_TYPE_GSM: case TelephonyManager.NETWORK_TYPE_CDMA:
            case TelephonyManager.NETWORK_TYPE_1xRTT:
                r.tecnologia = "2G"; break;
        }
        StringBuilder d = new StringBuilder();

        // Intensidad: RSRP, RSRQ y SINR que mide el propio módem
        if (Build.VERSION.SDK_INT >= 29) {
            SignalStrength ss = tm.getSignalStrength();
            if (ss != null) {
                r.barras = ss.getLevel();
                for (CellSignalStrength cs : ss.getCellSignalStrengths()) {
                    if (cs instanceof CellSignalStrengthNr) {
                        CellSignalStrengthNr nr = (CellSignalStrengthNr) cs;
                        if (valido(nr.getSsRsrp())) r.dbm = nr.getSsRsrp();
                        if (valido(nr.getSsRsrq())) r.rsrq = nr.getSsRsrq();
                        if (valido(nr.getSsSinr())) r.sinr = (double) nr.getSsSinr();
                        r.tecnologia = "5G";
                        break;
                    } else if (cs instanceof CellSignalStrengthLte) {
                        CellSignalStrengthLte lte = (CellSignalStrengthLte) cs;
                        if (valido(lte.getRsrp())) r.dbm = lte.getRsrp();
                        if (valido(lte.getRsrq())) r.rsrq = lte.getRsrq();
                        if (valido(lte.getRssnr())) {
                            double s = lte.getRssnr();
                            r.sinr = Math.abs(s) > 50 ? s / 10.0 : s;   // algunos equipos informan décimas de dB
                        }
                    }
                }
            }
        }

        // Celda: banda, ancho de canal, PCI, EARFCN (requiere permiso de ubicación)
        if (tiene(Manifest.permission.ACCESS_FINE_LOCATION)) {
            try {
                List<CellInfo> celdas = tm.getAllCellInfo();
                if (celdas != null) for (CellInfo ci : celdas) {
                    if (!ci.isRegistered() || !(ci instanceof CellInfoLte)) continue;
                    CellInfoLte l = (CellInfoLte) ci;
                    CellIdentityLte id = l.getCellIdentity();
                    if (r.dbm == null) r.dbm = l.getCellSignalStrength().getRsrp();
                    if (r.rsrq == null && valido(l.getCellSignalStrength().getRsrq())) r.rsrq = l.getCellSignalStrength().getRsrq();
                    if (r.barras == null) r.barras = l.getCellSignalStrength().getLevel();
                    if (Build.VERSION.SDK_INT >= 30 && id.getBands().length > 0) d.append("banda ").append(id.getBands()[0]).append(" · ");
                    if (Build.VERSION.SDK_INT >= 28 && valido(id.getBandwidth())) r.anchoMHz = id.getBandwidth() / 1000.0;
                    d.append("EARFCN ").append(id.getEarfcn()).append(" · PCI ").append(id.getPci()).append(" · ");
                    break;
                }
            } catch (SecurityException ignored) { }
        }
        if (r.anchoMHz != null) d.append("ancho ").append(fmt(r.anchoMHz, 1)).append(" MHz · ");
        if (r.rsrq != null) d.append("RSRQ ").append(r.rsrq).append(" dB · ");
        if (r.sinr != null) d.append("SINR ").append(fmt(r.sinr, 1)).append(" dB");
        r.detalle = d.toString().replaceAll(" · $", "");
    }

    static boolean valido(int v) {
        return v != Integer.MAX_VALUE && v != CellInfo.UNAVAILABLE;
    }

    static String normalizarOperador(String n) {
        String s = n == null ? "" : n.toLowerCase(Locale.ROOT);
        if (s.contains("personal")) return "Personal";
        if (s.contains("claro")) return "Claro";
        if (s.contains("movistar") || s.contains("tuenti")) return "Movistar";
        return n == null || n.isEmpty() ? "" : n;
    }

    static String calidad(int dbm, String conexion) {
        int[][] r = "WiFi".equals(conexion) ? new int[][]{{-50}, {-67}, {-75}, {-82}} : new int[][]{{-80}, {-90}, {-100}, {-110}};
        String[] t = {"Excelente", "Buena", "Regular", "Mala"};
        for (int i = 0; i < 4; i++) if (dbm >= r[i][0]) return t[i];
        return "Sin servicio útil";
    }

    void mostrarRed(Red r) {
        StringBuilder s = new StringBuilder();
        s.append(r.conexion);
        if (!r.tecnologia.isEmpty()) s.append(" · ").append(r.tecnologia);
        if (!r.operador.isEmpty()) s.append(" · ").append(r.operador);
        if (r.dbm != null) {
            s.append("\n").append(r.dbm).append(" dBm").append("WiFi".equals(r.conexion) ? " (RSSI)" : " (RSRP)")
                    .append(" · ").append(calidad(r.dbm, r.conexion));
            if (r.barras != null) s.append(" · ").append(barritas(r.barras));
        } else if ("Datos móviles".equals(r.conexion) && !tiene(Manifest.permission.READ_PHONE_STATE)) {
            s.append("\nDá permiso de teléfono y ubicación para leer la señal.");
        }
        if (!r.detalle.isEmpty()) s.append("\n").append(r.detalle);
        tRed.setText(s.toString());
    }

    static String barritas(int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 4; i++) b.append(i < n ? "▮" : "▯");
        return b.toString();
    }

    // ======================================================================
    // Equipo
    // ======================================================================
    static final Map<String, String> SAMSUNG = new HashMap<>();
    static {
        String[][] m = {{"A032", "Galaxy A03 Core"}, {"A035", "Galaxy A03"}, {"A045", "Galaxy A04"}, {"A047", "Galaxy A04s"},
                {"A055", "Galaxy A05"}, {"A057", "Galaxy A05s"}, {"A135", "Galaxy A13"}, {"A145", "Galaxy A14"},
                {"A146", "Galaxy A14 5G"}, {"A155", "Galaxy A15"}, {"A156", "Galaxy A15 5G"}, {"A245", "Galaxy A24"},
                {"A256", "Galaxy A25 5G"}, {"A325", "Galaxy A32"}, {"A336", "Galaxy A33 5G"}, {"A346", "Galaxy A34 5G"},
                {"A356", "Galaxy A35 5G"}, {"A525", "Galaxy A52"}, {"A536", "Galaxy A53 5G"}, {"A546", "Galaxy A54 5G"},
                {"A556", "Galaxy A55 5G"}, {"G991", "Galaxy S21"}, {"S901", "Galaxy S22"}, {"S911", "Galaxy S23"},
                {"S918", "Galaxy S23 Ultra"}, {"S921", "Galaxy S24"}, {"S928", "Galaxy S24 Ultra"}};
        for (String[] p : m) SAMSUNG.put(p[0], p[1]);
    }

    static String nombreComercial(String modelo) {
        if (modelo != null && modelo.toUpperCase(Locale.ROOT).startsWith("SM-") && modelo.length() >= 7) {
            String n = SAMSUNG.get(modelo.substring(3, 7).toUpperCase(Locale.ROOT));
            if (n != null) return n + " (" + modelo + ")";
        }
        return modelo;
    }

    void detectarEquipo() {
        String nombre = Settings.Global.getString(getContentResolver(), Settings.Global.DEVICE_NAME);
        if (nombre == null || nombre.isEmpty()) nombre = Build.MODEL;
        String marca = Build.MANUFACTURER.isEmpty() ? "" : Build.MANUFACTURER.substring(0, 1).toUpperCase(Locale.ROOT) + Build.MANUFACTURER.substring(1);
        String modelo = nombreComercial(Build.MODEL);
        medicion.put("nombre_dispositivo", nombre);
        medicion.put("marca", marca);
        medicion.put("modelo", modelo);
        medicion.put("so", "Android");
        medicion.put("version_so", Build.VERSION.RELEASE);
        medicion.put("tipo_dispositivo", "Celular");
        medicion.put("navegador", "App Android");
        medicion.put("origen", "android");
        tEquipo.setText(nombre + "\n" + marca + " " + modelo + " · Android " + Build.VERSION.RELEASE);
    }

    void detectarISP() {
        try {
            JSONObject d = new JSONObject(leerTexto(IP_INFO_URL));
            JSONObject c = d.optJSONObject("connection");
            if (c != null) {
                medicion.put("isp_detectado", c.optString("isp", c.optString("org")));
                if (c.has("asn")) medicion.put("asn", "AS" + c.optInt("asn"));
            }
            medicion.put("ciudad", d.optString("city"));
            medicion.put("_ipLat", d.optDouble("latitude"));
            medicion.put("_ipLon", d.optDouble("longitude"));
        } catch (Exception ignored) { }
    }

    // ======================================================================
    // Test de velocidad (mismo método que la web y la PC)
    // ======================================================================
    interface Progreso { void en(double mbps); }

    static String leerTexto(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000); c.setReadTimeout(15000);
        try (InputStream in = c.getInputStream()) {
            return new String(leerTodo(in), StandardCharsets.UTF_8);
        }
    }

    // InputStream.readAllBytes() recién existe en Android 13: se lee a mano
    static byte[] leerTodo(InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    static long bajarBytes(String url, long t0, Progreso p) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setUseCaches(false); c.setConnectTimeout(10000); c.setReadTimeout(30000);
        long total = 0;
        try (InputStream in = c.getInputStream()) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                double s = (System.nanoTime() - t0) / 1e9;
                if (p != null && s > 0.15) p.en(total * 8 / s / 1e6);
            }
        }
        return total;
    }

    double[] latencia() throws Exception {
        List<Double> t = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            long t0 = System.nanoTime();
            bajarBytes(SPEED_HOST + "/__down?bytes=0", t0, null);
            double ms = (System.nanoTime() - t0) / 1e6;
            if (i > 0) t.add(ms);   // la primera incluye el armado de la conexión
            fase("Latencia · " + Math.round(ms) + " ms");
        }
        double jit = 0;
        for (int i = 1; i < t.size(); i++) jit += Math.abs(t.get(i) - t.get(i - 1));
        Collections.sort(t);
        double mediana = (t.get(4) + t.get(5)) / 2;
        return new double[]{mediana, jit / (t.size() - 1)};
    }

    double bajada() throws Exception {
        double mejor = 0;
        long[] tam = {200_000, 2_000_000, 10_000_000, 25_000_000};
        for (int i = 0; i < tam.length; i++) {
            long t0 = System.nanoTime();
            long bytes = bajarBytes(SPEED_HOST + "/__down?bytes=" + tam[i], t0, v -> velocidad(v, "Mbps · bajada"));
            double s = (System.nanoTime() - t0) / 1e9, v = bytes * 8 / s / 1e6;
            if (i > 0 || tam.length == 1) mejor = Math.max(mejor, v);
            if (s > 2.5) break;
        }
        return mejor;
    }

    double subida() throws Exception {
        double mejor = 0;
        long[] tam = {200_000, 1_000_000, 4_000_000, 10_000_000};
        byte[] datos = new byte[10_000_000];
        new Random().nextBytes(datos);
        for (int i = 0; i < tam.length; i++) {
            long t0 = System.nanoTime();
            HttpURLConnection c = (HttpURLConnection) new URL(SPEED_HOST + "/__up").openConnection();
            c.setDoOutput(true); c.setRequestMethod("POST");
            c.setFixedLengthStreamingMode((int) tam[i]);
            c.setConnectTimeout(10000); c.setReadTimeout(30000);
            try (OutputStream o = c.getOutputStream()) { o.write(datos, 0, (int) tam[i]); }
            try (InputStream in = c.getInputStream()) { leerTodo(in); }
            double s = (System.nanoTime() - t0) / 1e9, v = tam[i] * 8 / s / 1e6;
            velocidad(v, "Mbps · subida");
            if (i > 0) mejor = Math.max(mejor, v);
            if (s > 2.5) break;
        }
        return mejor;
    }

    // ======================================================================
    // Ubicación
    // ======================================================================
    @SuppressWarnings("deprecation")
    Location ubicar() {
        if (!tiene(Manifest.permission.ACCESS_FINE_LOCATION)) return null;
        LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        try {
            for (String prov : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
                Location l = lm.getLastKnownLocation(prov);
                if (l != null && System.currentTimeMillis() - l.getTime() < 120_000) return l;
            }
            final Location[] res = {null};
            final CountDownLatch listo = new CountDownLatch(1);
            LocationListener ll = loc -> { res[0] = loc; listo.countDown(); };
            String prov = lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ? LocationManager.GPS_PROVIDER : LocationManager.NETWORK_PROVIDER;
            ui.post(() -> {
                try { lm.requestSingleUpdate(prov, ll, Looper.getMainLooper()); } catch (Exception e) { listo.countDown(); }
            });
            listo.await(12, TimeUnit.SECONDS);
            ui.post(() -> lm.removeUpdates(ll));
            if (res[0] != null) return res[0];
            return lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
        } catch (Exception e) {
            return null;
        }
    }

    // ======================================================================
    // Medición completa
    // ======================================================================
    void medir() {
        if (midiendo) return;
        midiendo = true;
        bMedir.setEnabled(false); bMedir.setText("Midiendo…");
        bEnviar.setEnabled(false); bCompartir.setEnabled(false);
        tResultados.setText("—"); tTeoria.setText("");
        new Thread(() -> {
            try {
                Red r = leerRed();
                double[] lat = latencia();
                double baj = bajada();
                double sub = subida();
                fase("Ubicación…");
                Location loc = ubicar();

                medicion.put("id", UUID.randomUUID().toString());
                SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT);
                iso.setTimeZone(TimeZone.getTimeZone("UTC"));
                medicion.put("fecha", iso.format(new Date()));
                medicion.put("conexion", r.conexion);
                medicion.put("tecnologia", r.tecnologia);
                medicion.put("tipo_red_detectado", r.detalle);
                medicion.put("empresa", r.operador);
                medicion.put("senal_dbm", r.dbm);
                medicion.put("barras", r.barras);
                medicion.put("sinr_db", r.sinr == null ? null : Math.round(r.sinr * 10) / 10.0);
                medicion.put("ancho_mhz", r.anchoMHz);
                medicion.put("latencia_ms", Math.round(lat[0] * 10) / 10.0);
                medicion.put("jitter_ms", Math.round(lat[1] * 10) / 10.0);
                medicion.put("bajada_mbps", Math.round(baj * 100) / 100.0);
                medicion.put("subida_mbps", Math.round(sub * 100) / 100.0);
                String ubic;
                if (loc != null) {
                    medicion.put("lat", Math.round(loc.getLatitude() * 1e5) / 1e5);
                    medicion.put("lon", Math.round(loc.getLongitude() * 1e5) / 1e5);
                    medicion.put("precision_m", Math.round(loc.getAccuracy()));
                    medicion.put("ubicacion_fuente", "GPS");
                    ubic = "GPS ±" + Math.round(loc.getAccuracy()) + " m";
                } else if (medicion.get("_ipLat") != null) {
                    medicion.put("lat", medicion.get("_ipLat"));
                    medicion.put("lon", medicion.get("_ipLon"));
                    medicion.put("ubicacion_fuente", "IP (aprox.)");
                    ubic = "aproximada por IP";
                } else {
                    ubic = "no disponible";
                }
                final String ubicTxt = ubic;
                final Red rf = r;
                ui.post(() -> {
                    velocidad(baj, "Mbps · resultado de bajada");
                    tResultados.setText(String.format(Locale.ROOT,
                            "Bajada   %.1f Mbps\nSubida   %.1f Mbps\nLatencia %.0f ms\nJitter   %.1f ms\nUbicación: %s",
                            baj, sub, lat[0], lat[1], ubicTxt));
                    tTeoria.setText(teoria(rf, baj));
                    bEnviar.setEnabled(true); bCompartir.setEnabled(true);
                });
            } catch (Exception e) {
                ui.post(() -> {
                    fase("Error en la medición");
                    estado("No se pudo completar la medición: " + e.getMessage(), BAD);
                });
            } finally {
                ui.post(() -> { midiendo = false; bMedir.setEnabled(true); bMedir.setText("Medir de nuevo"); });
            }
        }).start();
    }

    // ======================================================================
    // Teoría de la cátedra (Unidades 3, 5 y 7)
    // ======================================================================
    static double ruidoTermicoDbm(double anchoMHz, double tempC) {
        return 10 * Math.log10(1.38e-23 * (tempC + 273) * anchoMHz * 1e6 / 1e-3);
    }

    static double shannonMbps(double anchoMHz, double snrDb) {
        return anchoMHz * Math.log(1 + Math.pow(10, snrDb / 10)) / Math.log(2);
    }

    static String teoria(Red r, double bajada) {
        StringBuilder s = new StringBuilder();
        if (r.dbm == null) return "Sin dato de señal: no se puede aplicar el cálculo teórico.";
        double B = r.anchoMHz != null ? r.anchoMHz : 20, T = 25;
        boolean wifi = "WiFi".equals(r.conexion);
        double mW = Math.pow(10, r.dbm / 10.0);
        s.append(r.dbm).append(" dBm = ").append(fmt(mW * 1e12, 2)).append(" fW  →  calidad ").append(calidad(r.dbm, r.conexion)).append("\n\n");
        double S = r.dbm;
        if (!wifi) {
            int sub = 12 * 5 * (int) Math.round(B);
            S = r.dbm + 10 * Math.log10(sub);
            s.append("RSRP es la potencia de UNA subportadora de 15 kHz. Con B = ").append(fmt(B, 1))
                    .append(" MHz hay ").append(sub).append(" subportadoras:\nS = ").append(r.dbm).append(" + 10·log(").append(sub)
                    .append(") = ").append(fmt(S, 1)).append(" dBm\n\n");
        }
        double N = ruidoTermicoDbm(B, T);
        double snrEst = S - N, cEst = shannonMbps(B, snrEst);
        s.append("Ruido térmico N = kTB (").append(fmt(B, 1)).append(" MHz, 25 °C) = ").append(fmt(N, 1)).append(" dBm\n");
        s.append("SNR estimada (solo ruido térmico) = ").append(fmt(snrEst, 1)).append(" dB\n");
        s.append("Shannon C = B·log₂(1+S/N) = ").append(fmt(cEst, 0)).append(" Mbps\n");
        if (r.sinr != null) {
            double cReal = shannonMbps(B, r.sinr);
            s.append("\nSINR REAL medida por el módem = ").append(fmt(r.sinr, 1)).append(" dB\n");
            s.append("→ incluye interferencia de otras celdas, por eso es menor que la estimada.\n");
            s.append("Shannon con SINR real = ").append(fmt(cReal, 0)).append(" Mbps\n");
            s.append("Tu bajada es el ").append(fmt(100 * bajada / cReal, 0)).append(" % de ese límite.\n");
        } else {
            s.append("Tu bajada es el ").append(fmt(100 * bajada / cEst, 0)).append(" % de ese límite.\n");
        }
        Map<String, String> max = new HashMap<>();
        max.put("2G", "EDGE: hasta 473,6 Kbps"); max.put("3G", "UMTS hasta 2 Mbps · HSPA+ hasta 42 Mbps");
        max.put("4G", "LTE: 100 Mbps a 1 Gbps"); max.put("5G", "5G NR: varios Gbps");
        if (!wifi && max.containsKey(r.tecnologia)) s.append("\nMáximo teórico de ").append(r.tecnologia).append(" (Unidad 7): ").append(max.get(r.tecnologia));
        return s.toString();
    }

    static String fmt(double v, int dec) {
        return String.format(new Locale("es", "AR"), "%." + dec + "f", v);
    }

    // ======================================================================
    // Envío a la planilla
    // ======================================================================
    void enviar() {
        if (!cConsentimiento.isChecked()) { estado("Para enviar tenés que aceptar el uso de los datos.", BAD); return; }
        medicion.put("lugar", eLugar.getText().toString().trim());
        medicion.put("comentario", eComentario.getText().toString().trim());
        String url = eUrl.getText().toString().trim();
        prefs.edit().putString("script_url", url).apply();
        JSONObject j = new JSONObject();
        try {
            for (String c : CAMPOS) { Object v = medicion.get(c); j.put(c, v == null ? "" : v); }
        } catch (Exception ignored) { }
        guardarHistorial(j);
        if (url.isEmpty()) {
            estado("Guardada en el celular. Configurá la URL de la planilla para compartirla con el grupo.", AMBER);
            return;
        }
        bEnviar.setEnabled(false);
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setDoOutput(true); c.setRequestMethod("POST"); c.setInstanceFollowRedirects(true);
                c.setRequestProperty("Content-Type", "text/plain;charset=utf-8");
                c.setConnectTimeout(15000); c.setReadTimeout(20000);
                try (OutputStream o = c.getOutputStream()) { o.write(j.toString().getBytes(StandardCharsets.UTF_8)); }
                int code = c.getResponseCode();
                ui.post(() -> estado(code < 400 ? "¡Listo! Tu medición se envió a la planilla compartida." : "La planilla respondió con error " + code + ".", code < 400 ? GOOD : BAD));
            } catch (Exception e) {
                ui.post(() -> { estado("No se pudo enviar (" + e.getMessage() + "). Quedó guardada en el celular.", BAD); bEnviar.setEnabled(true); });
            }
        }).start();
    }

    void guardarHistorial(JSONObject j) {
        try {
            org.json.JSONArray a = new org.json.JSONArray(prefs.getString("historial", "[]"));
            a.put(j);
            prefs.edit().putString("historial", a.toString()).apply();
        } catch (Exception ignored) { }
    }

    void compartir() {
        String txt = "Medidor de Señal UTN FRT\n" + tEquipo.getText() + "\n" + tRed.getText() + "\n\n" + tResultados.getText() + "\n\n" + tTeoria.getText();
        Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, txt);
        startActivity(Intent.createChooser(i, "Compartir resultado"));
    }

    // ======================================================================
    // Interfaz (construida en código, sin XML)
    // ======================================================================
    int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    void fase(String t) { ui.post(() -> tFase.setText(t)); }

    void velocidad(double v, String f) {
        ui.post(() -> { tVelocidad.setText(v >= 100 ? fmt(v, 0) : fmt(v, 1)); tFase.setText(f); });
    }

    void estado(String t, int color) {
        tEstado.setVisibility(View.VISIBLE); tEstado.setText(t); tEstado.setTextColor(color);
    }

    GradientDrawable fondo(int color, int borde, int radio) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color); g.setCornerRadius(dp(radio)); g.setStroke(dp(1), borde);
        return g;
    }

    TextView texto(String t, int sp, int color, boolean negrita) {
        TextView v = new TextView(this);
        v.setText(t); v.setTextSize(sp); v.setTextColor(color);
        if (negrita) v.setTypeface(Typeface.DEFAULT_BOLD);
        return v;
    }

    LinearLayout tarjeta(LinearLayout padre, String titulo) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(14), dp(16), dp(16));
        c.setBackground(fondo(SURFACE, LINE, 14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(14);
        padre.addView(c, lp);
        TextView t = texto(titulo.toUpperCase(Locale.ROOT), 11, MUTED, true);
        t.setTypeface(Typeface.MONOSPACE, Typeface.BOLD); t.setLetterSpacing(0.08f);
        c.addView(t);
        return c;
    }

    Button boton(String t, boolean primario) {
        Button b = new Button(this);
        b.setText(t); b.setAllCaps(false); b.setTextSize(16);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(primario ? Color.WHITE : FG);
        b.setBackground(fondo(primario ? ACCENT : SURFACE, primario ? ACCENT : LINE, 12));
        b.setPadding(dp(12), dp(14), dp(12), dp(14));
        return b;
    }

    EditText campo(String pista) {
        EditText e = new EditText(this);
        e.setHint(pista); e.setTextSize(15); e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT);
        return e;
    }

    void construirUI() {
        getWindow().setStatusBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        LinearLayout raiz = new LinearLayout(this);
        raiz.setOrientation(LinearLayout.VERTICAL);
        raiz.setPadding(dp(16), dp(24), dp(16), dp(40));
        sv.addView(raiz);

        TextView eyebrow = texto("UTN FRT · COMUNICACIÓN DE DATOS", 11, MUTED, true);
        eyebrow.setTypeface(Typeface.MONOSPACE, Typeface.BOLD); eyebrow.setLetterSpacing(0.08f);
        raiz.addView(eyebrow);
        raiz.addView(texto("Medidor de Señal", 28, FG, true));

        LinearLayout cEq = tarjeta(raiz, "Tu equipo");
        tEquipo = texto("…", 16, FG, false); cEq.addView(tEquipo);

        LinearLayout cRed = tarjeta(raiz, "Red en vivo");
        tRed = texto("Leyendo…", 16, FG, false); tRed.setLineSpacing(0, 1.15f); cRed.addView(tRed);

        LinearLayout cMed = tarjeta(raiz, "Medición");
        tVelocidad = texto("0.0", 56, FG, true);
        tVelocidad.setTypeface(Typeface.MONOSPACE, Typeface.BOLD); tVelocidad.setGravity(Gravity.CENTER);
        cMed.addView(tVelocidad, new LinearLayout.LayoutParams(-1, -2));
        tFase = texto("Mbps · listo para medir", 12, MUTED, true);
        tFase.setTypeface(Typeface.MONOSPACE, Typeface.BOLD); tFase.setGravity(Gravity.CENTER);
        cMed.addView(tFase, new LinearLayout.LayoutParams(-1, -2));
        bMedir = boton("Iniciar medición", true);
        LinearLayout.LayoutParams lpb = new LinearLayout.LayoutParams(-1, -2); lpb.topMargin = dp(14);
        cMed.addView(bMedir, lpb);
        bMedir.setOnClickListener(v -> medir());
        cMed.addView(texto("Usa entre 10 y 40 MB de datos.", 13, MUTED, false));
        tResultados = texto("—", 16, FG, false);
        tResultados.setTypeface(Typeface.MONOSPACE);
        LinearLayout.LayoutParams lpr = new LinearLayout.LayoutParams(-1, -2); lpr.topMargin = dp(10);
        cMed.addView(tResultados, lpr);

        LinearLayout cTeo = tarjeta(raiz, "Análisis teórico (Unidades 3, 5 y 7)");
        tTeoria = texto("Hacé una medición para ver el cálculo.", 14, FG, false);
        tTeoria.setLineSpacing(0, 1.15f);
        cTeo.addView(tTeoria);

        LinearLayout cEnv = tarjeta(raiz, "Enviar al grupo");
        eLugar = campo("Lugar: interior, exterior, en movimiento…"); cEnv.addView(eLugar);
        eComentario = campo("Comentario (ej.: aula 3)"); cEnv.addView(eComentario);
        cConsentimiento = new CheckBox(this);
        cConsentimiento.setText("Acepto que esta medición se guarde para un trabajo de la UTN FRT. No se guardan números de teléfono ni direcciones IP.");
        cConsentimiento.setTextColor(FG); cConsentimiento.setTextSize(13);
        cEnv.addView(cConsentimiento);
        bEnviar = boton("Enviar medición", true); bEnviar.setEnabled(false);
        cEnv.addView(bEnviar, lpb);
        bEnviar.setOnClickListener(v -> enviar());
        bCompartir = boton("Compartir resultado", false); bCompartir.setEnabled(false);
        LinearLayout.LayoutParams lpc = new LinearLayout.LayoutParams(-1, -2); lpc.topMargin = dp(8);
        cEnv.addView(bCompartir, lpc);
        bCompartir.setOnClickListener(v -> compartir());
        tEstado = texto("", 14, FG, true); tEstado.setVisibility(View.GONE);
        cEnv.addView(tEstado, lpr);

        LinearLayout cCfg = tarjeta(raiz, "Configuración");
        cCfg.addView(texto("URL de la planilla (Apps Script):", 13, MUTED, false));
        eUrl = campo("https://script.google.com/macros/s/…/exec");
        eUrl.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        eUrl.setText(prefs.getString("script_url", SCRIPT_URL_POR_DEFECTO));
        cCfg.addView(eUrl);

        setContentView(sv);
    }
}
