# Medidor de Señal — prototipo (UTN FRT · Comunicación)

App web para medir la conexión desde cualquier celular (Android o iPhone) y juntar las mediciones para hacer estadística.

## Tres formas de medir, una sola planilla

| Versión | Para quién | Qué detecta sola |
|---|---|---|
| **App Android** (`MedidorSenal-UTN.apk`) | Celulares Android | Nombre del equipo, marca y modelo, empresa (del chip), WiFi o datos, 4G/5G, **RSRP, RSRQ, SINR real**, banda, ancho de canal, PCI; en WiFi: RSSI, banda, canal, estándar y velocidad de enlace. GPS y test de velocidad |
| **Web** (`index.html`) | iPhone y cualquier navegador | Marca y sistema operativo, empresa por IP, velocidad y GPS. La señal se carga a mano |
| **PC** (`pc/medidor_pc.py`) | Computadoras con Windows | Nombre del equipo, marca y modelo, **RSSI**, banda, canal, estándar WiFi, velocidad de enlace, proveedor y test de velocidad. Modo monitoreo con `--repetir` |

Todas mandan las mediciones a la misma planilla, y el panel muestra de dónde viene cada una (columna "origen").

### App Android
- Instalar `MedidorSenal-UTN.apk` en el celular. Hay que permitir "instalar apps de origen desconocido", porque la app no está en Play Store.
- Al abrirla, aceptar los permisos de **ubicación** y **teléfono**. Son necesarios para leer la señal y la celda.
- La URL de la planilla se configura una sola vez, abajo de todo, en "Configuración". Si se escribe en `SCRIPT_URL_POR_DEFECTO` de `MainActivity.java` antes de compilar, ya viene cargada.
- Las herramientas de Android no funcionan en carpetas con caracteres especiales (como la "ñ" de "señal"). Para recompilar, copiá la carpeta `android` a una ruta sin acentos ni eñes y compilá desde ahí:
  ```
  cd android
  set JAVA_HOME=%LOCALAPPDATA%\MedidorSenal\jdk17
  %LOCALAPPDATA%\MedidorSenal\gradle-8.7\bin\gradle assembleDebug
  ```

### Programa para PC
```
py pc\medidor_pc.py
py pc\medidor_pc.py --repetir 10 --cada 60 --comentario "laboratorio"
```
Toma la URL de la planilla de `config.js` y además guarda todo en `pc/mediciones_pc.csv`.

### Modo en vivo (presentación)
En `estadisticas.html`, el botón **"Modo en vivo (QR)"** (o abrir `estadisticas.html#vivo`) muestra un código QR que lleva a la app de medición. El panel se actualiza solo cada 8 segundos y muestra cada medición nueva a medida que llega.

## Archivos

| Archivo | Para qué sirve |
|---|---|
| `index.html` | La app de medición (lo que abre cada persona en su celular) |
| `estadisticas.html` | Panel con medidas estadísticas, gráficos, mapa y exportación a CSV |
| `config.js` | Configuración: URL de la planilla y servicios usados |
| `estilos.css` | Estilos compartidos |
| `apps-script/Codigo.gs` | Código que guarda las mediciones en Google Sheets |

## Qué mide y cómo

| Dato | Cómo se obtiene | Android | iPhone |
|---|---|---|---|
| Velocidad de bajada y subida | Descarga y sube archivos de prueba a `speed.cloudflare.com` y mide el tiempo | ✅ | ✅ |
| Latencia y jitter | 10 pedidos chicos al mismo servidor (mediana y variación entre pedidos) | ✅ | ✅ |
| WiFi o datos móviles | API *Network Information* del navegador | ✅ automático en Chrome | ✋ lo elige la persona |
| Tecnología (4G/5G) | La persona la elige mirando su celular | ✋ | ✋ |
| Marca y modelo | *User agent* y *Client Hints* del navegador | ✅ en Chrome | ✅ (Apple, sin modelo exacto) |
| Sistema operativo y versión | *User agent* | ✅ | ✅ |
| Empresa (Personal, Claro, Movistar) | Proveedor de la IP pública (`ipwho.is`); con datos móviles coincide con la empresa de la línea | ✅ sugerida | ✅ sugerida |
| Ubicación | GPS del celular con permiso; si no, ciudad aproximada por IP | ✅ | ✅ |
| Señal en dBm | La persona la copia de Ajustes (opcional) | ✋ | ✋ (iOS no la muestra) |

**Limitación:** ningún navegador puede leer la potencia de la señal (dBm). Para eso hace falta una app nativa de Android, y en iPhone no se puede ni así.

**Privacidad:** no se guardan nombres, teléfonos ni la IP. Cada medición pide consentimiento.

## Relación con las unidades de la materia

| Unidad | Concepto | Dónde aparece en la app |
|---|---|---|
| 1. Sistemas de Comunicación | Fuente, transmisor, canal, receptor; latencia y *jitter*; medios no guiados (WiFi, telefonía móvil) | Test de latencia y jitter; comparación WiFi vs datos móviles |
| 3. Atenuación, Distorsión y Ruido | dBm → mW, RSSI y sensibilidad (-82 dBm en WiFi), ruido térmico N = kTB, SNR en dB | Conversión de la señal ingresada, calidad de la señal, cálculo de ruido y SNR |
| 4. Teoría de la Información | Tasa de información en bits/s | Las velocidades se expresan en Mbps (millones de bits de información por segundo) |
| 5. Canales de Comunicaciones | Capacidad de Shannon C = B·log₂(1 + S/N) | Capacidad teórica de cada medición y % de aprovechamiento real |
| 6. Errores en la transmisión | Retransmisiones (ARQ) y corrección (FEC) | Explica por qué la velocidad real queda muy por debajo de Shannon |
| 7. Técnicas de transmisión | Ancho de banda digital por generación (EDGE, UMTS, HSPA+, LTE, 5G NR) | Comparación de la velocidad medida con el máximo de cada tecnología |

`teoria.js` tiene todas las fórmulas. En 4G, Android muestra el **RSRP** (potencia de una subportadora de 15 kHz). Para la SNR se pasa a potencia total del canal sumando en dB: S = RSRP + 10·log₁₀(12 · 5 · B[MHz]). Para 20 MHz eso da 1200 subportadoras, o sea +30,8 dB.

## Probarlo ya (modo demo)

Sin configurar nada, la app guarda las mediciones en el navegador. En `estadisticas.html` está el botón **"Cargar 60 mediciones de ejemplo"**, que genera datos inventados (marcados como ejemplo) para mostrar cómo se ven los gráficos.

Para abrirlo en la PC, desde la carpeta `medidor-senal`:

```
py -m http.server 8000
```

y entrar a http://localhost:8000

## Juntar las mediciones de todos (Google Sheets)

1. Crear una planilla nueva en Google Sheets.
2. Ir a **Extensiones → Apps Script**, borrar lo que haya y pegar el contenido de `apps-script/Codigo.gs`. Guardar.
3. **Implementar → Nueva implementación → Tipo: Aplicación web**.
   - Ejecutar como: **Yo**
   - Quién tiene acceso: **Cualquier usuario**
4. Autorizar los permisos y copiar la URL que termina en `/exec`.
5. Pegar esa URL en `config.js`, en `SCRIPT_URL`.

Las mediciones aparecen como filas en la hoja "Mediciones", y el panel de estadísticas las lee de ahí.

## Publicarlo para que lo usen desde el celular

El GPS solo funciona en páginas con **https**. La forma más simple y gratuita es **GitHub Pages**:

1. Crear un repositorio en GitHub y subir el contenido de la carpeta `medidor-senal`.
2. En el repositorio: **Settings → Pages → Branch: main → Save**.
3. En un minuto queda publicado en `https://TU-USUARIO.github.io/NOMBRE-REPO/`. Ese link (o un QR) es lo que se comparte.
