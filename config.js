// Configuración del Medidor de Señal (prototipo UTN FRT - Comunicación)
window.CONFIG = {
  // URL de la implementación de Google Apps Script (ver README.md).
  // Si queda vacía, la app funciona en "modo demo": guarda las mediciones solo en este navegador.
  SCRIPT_URL: "https://script.google.com/macros/s/AKfycbxMnSYOA2H6ESEeqcvboXoYdhkGXOX83ubzK_8o_P6RWWfB6TUhYu0eTO1HzgVeC43Nag/exec",

  // Servidor para el test de velocidad (permite CORS, gratuito).
  SPEED_HOST: "https://speed.cloudflare.com",

  // Servicio para detectar el proveedor de internet a partir de la IP pública.
  IP_INFO_URL: "https://ipwho.is/",
};

// Columnas de cada medición (mismo orden que en la planilla de Google Sheets).
window.CAMPOS = [
  "id", "fecha", "tipo_dispositivo", "nombre_dispositivo", "marca", "modelo", "so", "version_so", "navegador",
  "conexion", "tecnologia", "tipo_red_detectado", "empresa", "isp_detectado", "asn",
  "bajada_mbps", "subida_mbps", "latencia_ms", "jitter_ms",
  "senal_dbm", "barras", "lugar", "lat", "lon", "precision_m", "ubicacion_fuente", "ciudad", "comentario", "sinr_db", "ancho_mhz", "origen",
];

window.EMPRESAS = ["Personal", "Claro", "Movistar", "Otra"];
