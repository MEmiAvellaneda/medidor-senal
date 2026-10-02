// Backend del Medidor de Señal: guarda cada medición como una fila de Google Sheets.
// Pegar este código en Extensiones → Apps Script de la planilla (ver LEEME.md).

const HOJA = "Mediciones";
const CAMPOS = [
  "id", "fecha", "tipo_dispositivo", "nombre_dispositivo", "marca", "modelo", "so", "version_so", "navegador",
  "conexion", "tecnologia", "tipo_red_detectado", "empresa", "isp_detectado", "asn",
  "bajada_mbps", "subida_mbps", "latencia_ms", "jitter_ms",
  "senal_dbm", "barras", "lugar", "lat", "lon", "precision_m", "ubicacion_fuente", "ciudad", "comentario", "sinr_db", "ancho_mhz", "origen",
];

function obtenerHoja() {
  const libro = SpreadsheetApp.getActiveSpreadsheet();
  let hoja = libro.getSheetByName(HOJA);
  if (!hoja) {
    hoja = libro.insertSheet(HOJA);
    hoja.appendRow(CAMPOS);
    hoja.setFrozenRows(1);
  }
  return hoja;
}

// Recibe una medición (JSON) y la agrega como fila.
function doPost(e) {
  const lock = LockService.getScriptLock();
  lock.waitLock(10000);
  try {
    const d = JSON.parse(e.postData.contents);
    obtenerHoja().appendRow(CAMPOS.map((c) => (d[c] === undefined || d[c] === null ? "" : d[c])));
    return respuesta({ ok: true });
  } catch (err) {
    return respuesta({ ok: false, error: String(err) });
  } finally {
    lock.releaseLock();
  }
}

// Devuelve todas las mediciones para el panel de estadísticas.
function doGet() {
  const valores = obtenerHoja().getDataRange().getValues();
  const encabezado = valores.shift() || [];
  const filas = valores.map((fila) => {
    const o = {};
    encabezado.forEach((c, i) => (o[c] = fila[i] instanceof Date ? fila[i].toISOString() : fila[i]));
    return o;
  });
  return respuesta({ filas });
}

function respuesta(obj) {
  return ContentService.createTextOutput(JSON.stringify(obj)).setMimeType(ContentService.MimeType.JSON);
}
