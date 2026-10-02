"""Medidor de Señal para PC (Windows) - UTN FRT, Comunicación de Datos.

Mide la conexión de esta computadora de forma automática (nombre del equipo, marca, WiFi: RSSI,
banda, canal, estándar, velocidad de enlace; test de velocidad; proveedor) y la envía a la misma
planilla que la app web. Solo usa la biblioteca estándar de Python.

Uso:
    py medidor_pc.py                 una medición
    py medidor_pc.py --repetir 10    10 mediciones, una por minuto (monitoreo)
    py medidor_pc.py --repetir 10 --cada 30
"""
import argparse
import csv
import http.client
import json
import math
import os
import platform
import re
import socket
import statistics
import subprocess
import time
import unicodedata
import urllib.request
import uuid
from datetime import datetime, timezone

AQUI = os.path.dirname(os.path.abspath(__file__))
SPEED_HOST = "https://speed.cloudflare.com"
IP_INFO_URL = "https://ipwho.is/"
UA = {"User-Agent": "MedidorSenalUTN/1.0"}
SIN_VENTANA = getattr(subprocess, "CREATE_NO_WINDOW", 0)

# Mismas columnas que config.js y la planilla
CAMPOS = [
    "id", "fecha", "tipo_dispositivo", "nombre_dispositivo", "marca", "modelo", "so", "version_so", "navegador",
    "conexion", "tecnologia", "tipo_red_detectado", "empresa", "isp_detectado", "asn",
    "bajada_mbps", "subida_mbps", "latencia_ms", "jitter_ms",
    "senal_dbm", "barras", "lugar", "lat", "lon", "precision_m", "ubicacion_fuente", "ciudad", "comentario", "sinr_db", "ancho_mhz", "origen",
]


def leer_script_url():
    """Toma SCRIPT_URL de ../config.js para no configurar dos veces."""
    try:
        with open(os.path.join(AQUI, "..", "config.js"), encoding="utf-8") as f:
            m = re.search(r'SCRIPT_URL:\s*"([^"]*)"', f.read())
            return m.group(1) if m else ""
    except OSError:
        return ""


def ejecutar(cmd):
    r = subprocess.run(cmd, capture_output=True, creationflags=SIN_VENTANA)
    try:
        return r.stdout.decode("utf-8")   # Windows 11 suele devolver UTF-8
    except UnicodeDecodeError:
        return r.stdout.decode("oem" if os.name == "nt" else "latin-1", errors="ignore")


def sin_acentos(s):
    return "".join(c for c in unicodedata.normalize("NFD", s) if unicodedata.category(c) != "Mn").lower()


# ---------- Equipo ----------
def datos_equipo():
    marca = modelo = ""
    if os.name == "nt":
        salida = ejecutar(["powershell", "-NoProfile", "-Command",
                           "Get-CimInstance Win32_ComputerSystem | Select-Object Manufacturer,Model | ConvertTo-Json"])
        try:
            d = json.loads(salida)
            marca, modelo = (d.get("Manufacturer") or "").strip(), (d.get("Model") or "").strip()
        except ValueError:
            pass
    so, version = platform.system(), platform.release()
    if so == "Windows":
        build = int((platform.version().split(".") + ["0", "0", "0"])[2] or 0)
        version = "11" if build >= 22000 else version
    elif so == "Darwin":
        so, version = "macOS", platform.mac_ver()[0]
    return {"nombre_dispositivo": socket.gethostname(), "marca": marca, "modelo": modelo,
            "so": so, "version_so": version, "tipo_dispositivo": "Computadora", "navegador": "Programa PC"}


# ---------- WiFi (netsh, en español o inglés) ----------
def datos_wifi():
    if os.name != "nt":
        return {}
    w = {}
    for linea in ejecutar(["netsh", "wlan", "show", "interfaces"]).splitlines():
        if ":" not in linea:
            continue
        k, v = linea.split(":", 1)
        k, v = sin_acentos(k.strip()), v.strip()
        if k in ("estado", "state"): w["estado"] = sin_acentos(v)
        elif k == "ssid": w["ssid"] = v
        elif k in ("banda", "band"): w["banda"] = v
        elif k in ("canal", "channel"): w["canal"] = v
        elif k in ("tipo de radio", "radio type"): w["radio"] = v
        elif k.startswith(("velocidad de recepcion", "receive rate")): w["rx"] = v
        elif k.startswith(("velocidad de transmision", "transmit rate")): w["tx"] = v
        elif k in ("senal", "signal"): w["senal_pct"] = v.replace("%", "").strip()
        elif k == "rssi": w["rssi"] = v
    if w.get("estado", "").startswith(("conectado", "connected")):
        return w
    return {}


def dbm_desde_porcentaje(pct):
    # Windows: 0 % ≈ -100 dBm y 100 % ≈ -50 dBm (lineal). Solo si no hay RSSI.
    return round(int(pct) / 2 - 100)


# ---------- Proveedor ----------
def empresa_desde(isp):
    s = (isp or "").lower()
    if re.search(r"claro|amx", s): return "Claro"
    if re.search(r"telef[oó]nica|movistar", s): return "Movistar"
    if re.search(r"telecom|personal", s): return "Personal"
    return "Otra"


def datos_ip():
    try:
        with urllib.request.urlopen(urllib.request.Request(IP_INFO_URL, headers=UA), timeout=10) as r:
            d = json.load(r)
        c = d.get("connection", {})
        return {"isp_detectado": c.get("isp") or c.get("org") or "", "asn": f"AS{c['asn']}" if c.get("asn") else "",
                "ciudad": d.get("city", ""), "lat": round(d.get("latitude", 0), 3), "lon": round(d.get("longitude", 0), 3),
                "ubicacion_fuente": "IP (aprox.)"}
    except Exception:
        return {}


# ---------- Test de velocidad ----------
def pedir(url, datos=None):
    req = urllib.request.Request(url, data=datos, headers=UA, method="POST" if datos else "GET")
    with urllib.request.urlopen(req, timeout=60) as r:
        total = 0
        while True:
            bloque = r.read(65536)
            if not bloque:
                return total
            total += len(bloque)


def latencia(n=10):
    # Una sola conexión reutilizada: así se mide el viaje ida y vuelta y no el armado de TCP/TLS
    conn = http.client.HTTPSConnection(SPEED_HOST.split("//")[1], timeout=15)
    tiempos = []
    for i in range(n + 1):
        t0 = time.perf_counter()
        conn.request("GET", "/__down?bytes=0", headers=UA)
        conn.getresponse().read()
        if i:  # la primera incluye el armado de la conexión
            tiempos.append((time.perf_counter() - t0) * 1000)
    conn.close()
    jitter = sum(abs(a - b) for a, b in zip(tiempos, tiempos[1:])) / (len(tiempos) - 1)
    return statistics.median(tiempos), jitter


def serie(fn, tamanos):
    res = []
    for b in tamanos:
        t0 = time.perf_counter()
        bytes_ = fn(b)
        s = time.perf_counter() - t0
        res.append(bytes_ * 8 / s / 1e6)
        print(f"   {b / 1e6:5.1f} MB → {res[-1]:7.1f} Mbps")
        if s > 2.5:
            break
    return max(res[1:] or res)


def bajada():
    return serie(lambda b: pedir(f"{SPEED_HOST}/__down?bytes={int(b)}"), [2e5, 2e6, 10e6, 25e6])


def subida():
    return serie(lambda b: (pedir(f"{SPEED_HOST}/__up", os.urandom(int(b))), int(b))[1], [2e5, 1e6, 4e6, 10e6])


# ---------- Teoría (Unidades 3 y 5) ----------
def teoria(dbm, ancho_mhz=20, temp_c=25):
    k = 1.38e-23
    ruido = 10 * math.log10(k * (temp_c + 273) * ancho_mhz * 1e6 / 1e-3)
    snr = dbm - ruido
    c = ancho_mhz * math.log2(1 + 10 ** (snr / 10))
    return ruido, snr, c


# ---------- Envío ----------
def enviar(fila, script_url):
    if script_url:
        try:
            req = urllib.request.Request(script_url, data=json.dumps(fila).encode(),
                                         headers={"Content-Type": "text/plain;charset=utf-8", **UA})
            urllib.request.urlopen(req, timeout=20).read()
            print("✔ Enviada a la planilla compartida.")
        except Exception as e:
            print(f"✖ No se pudo enviar a la planilla ({e}). Se guarda solo en el CSV local.")
    ruta = os.path.join(AQUI, "mediciones_pc.csv")
    nueva = not os.path.exists(ruta)
    with open(ruta, "a", newline="", encoding="utf-8-sig") as f:
        w = csv.DictWriter(f, fieldnames=CAMPOS, delimiter=";")
        if nueva:
            w.writeheader()
        w.writerow(fila)
    print(f"✔ Guardada en {ruta}")


def medir(script_url, lugar, comentario):
    fila = dict.fromkeys(CAMPOS, "")
    fila.update(id=str(uuid.uuid4()), fecha=datetime.now(timezone.utc).isoformat(), lugar=lugar, comentario=comentario, origen="pc")

    print("\n» Equipo"); fila.update(datos_equipo())
    print(f"   {fila['nombre_dispositivo']} · {fila['marca']} {fila['modelo']} · {fila['so']} {fila['version_so']}")

    w = datos_wifi()
    if w:
        rssi = int(w["rssi"]) if w.get("rssi", "").lstrip("-").isdigit() else dbm_desde_porcentaje(w.get("senal_pct", "0"))
        fila.update(conexion="WiFi", tecnologia=w.get("radio", ""), senal_dbm=rssi,
                    tipo_red_detectado=f"{w.get('radio', '')} · {w.get('banda', '')} · canal {w.get('canal', '?')} · enlace {w.get('rx', '?')}/{w.get('tx', '?')} Mbps")
        print(f"» WiFi '{w.get('ssid', '')}': RSSI {rssi} dBm ({w.get('senal_pct', '?')} %), {fila['tipo_red_detectado']}")
    else:
        fila.update(conexion="Cable")
        print("» Conexión por cable (o WiFi no detectado)")

    ip = datos_ip(); fila.update(ip)
    fila["empresa"] = empresa_desde(ip.get("isp_detectado"))
    print(f"» Proveedor: {ip.get('isp_detectado', '?')} ({fila['empresa']}) · {ip.get('ciudad', '')}")

    print("» Latencia..."); lat, jit = latencia()
    print(f"   {lat:.0f} ms, jitter {jit:.1f} ms")
    print("» Bajada..."); baj = bajada()
    print("» Subida..."); sub = subida()
    fila.update(latencia_ms=round(lat, 1), jitter_ms=round(jit, 1), bajada_mbps=round(baj, 2), subida_mbps=round(sub, 2))

    print(f"\n  RESULTADO  bajada {baj:.1f} Mbps · subida {sub:.1f} Mbps · latencia {lat:.0f} ms · jitter {jit:.1f} ms")
    if fila["senal_dbm"] != "":
        ruido, snr, c = teoria(fila["senal_dbm"])
        print(f"  TEORÍA     {fila['senal_dbm']} dBm = {10 ** (fila['senal_dbm'] / 10) * 1e9:.2f} pW · ruido kTB (20 MHz, 25 °C) = {ruido:.1f} dBm")
        print(f"             SNR = {snr:.1f} dB · Shannon C = B·log2(1+S/N) = {c:.0f} Mbps · aprovechamiento {100 * baj / c:.0f} %")
    enviar(fila, script_url)


def main():
    p = argparse.ArgumentParser(description="Medidor de Señal para PC - UTN FRT")
    p.add_argument("--repetir", type=int, default=1, help="cantidad de mediciones (monitoreo)")
    p.add_argument("--cada", type=int, default=60, help="segundos entre mediciones")
    p.add_argument("--lugar", default="", help="Interior, Exterior, ...")
    p.add_argument("--comentario", default="", help="texto libre, ej.: laboratorio 2")
    a = p.parse_args()
    script_url = leer_script_url()
    print("Medidor de Señal · UTN FRT · Comunicación de Datos")
    print("Planilla: " + ("conectada" if script_url else "no configurada (modo demo: solo CSV local)"))
    for i in range(a.repetir):
        if a.repetir > 1:
            print(f"\n===== Medición {i + 1} de {a.repetir} =====")
        try:
            medir(script_url, a.lugar, a.comentario)
        except Exception as e:
            print(f"✖ Error en la medición: {e}")
        if i < a.repetir - 1:
            time.sleep(a.cada)


if __name__ == "__main__":
    main()
