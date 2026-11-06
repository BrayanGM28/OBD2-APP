# OBD Pulse

App Android para leer los datos de tu carro con un adaptador **ELM327** (Bluetooth Clásico o BLE).
Kotlin + Jetpack Compose, tema oscuro, en español. Android 8.0 o superior.

## Qué hace

| Pestaña | Contenido |
|---|---|
| **Conexión** | Busca adaptadores Clásicos y BLE a la vez, recuerda el último, modo demo sin carro |
| **Tablero** | Tacómetro y velocímetro de aguja, anillos de temperatura/carga/acelerador, consumo instantáneo, voltaje, registro de viaje |
| **Gráficas** | Hasta 4 sensores en tiempo real, ventanas de 30 s a 5 min, pausa, mín/prom/máx |
| **Diagnóstico** | Check Engine, códigos confirmados/pendientes/permanentes con descripción en español, cuadro congelado, monitores de emisiones, borrado de códigos |
| **Más** | Lista de todos los sensores del carro, terminal AT, viajes en CSV (compartir/borrar), ajustes |

La app consulta los PIDs soportados por la ECU (0100, 0120, 0140…) y solo muestra lo que tu carro reporta.

## Cómo obtener el .apk

### Opción A — GitHub Actions (sin instalar nada)

1. Crea un repositorio nuevo en GitHub (puede ser privado).
2. Sube el contenido de esta carpeta. Desde un computador con git:
   ```bash
   cd OBDPulse
   git init
   git add .
   git commit -m "OBD Pulse"
   git branch -M main
   git remote add origin https://github.com/TU_USUARIO/OBDPulse.git
   git push -u origin main
   ```
   Sin git: en GitHub usa **Add file → Upload files** y arrastra el contenido de la carpeta.
   Ojo: la carpeta `.github` es oculta en Mac/Windows; si no se sube, créala a mano con
   **Add file → Create new file**, nombre `.github/workflows/build-apk.yml`, y pega el contenido del archivo.
3. Entra a la pestaña **Actions**: el flujo "Compilar APK" arranca solo (≈ 5–8 min la primera vez).
4. Cuando termine, ve a **Releases** (en la página principal del repo) y descarga `OBDPulse.apk` desde el celular.
   También queda como artefacto en el resumen de la ejecución en Actions.

### Opción B — Android Studio

Abre la carpeta `OBDPulse` en Android Studio → espera la sincronización de Gradle →
**Build → Build App Bundle(s) / APK(s) → Build APK(s)**. El archivo queda en
`app/build/outputs/apk/debug/app-debug.apk`.

## Instalar y conectar

1. En el celular, permite instalar apps de origen desconocido para el navegador o el gestor de archivos.
2. Conecta el ELM327 al puerto OBD-II (normalmente bajo el volante) y pon el encendido en ON o arranca el motor.
3. **ELM327 Mini azul (Bluetooth Clásico):** emparéjalo primero en Ajustes → Bluetooth del teléfono
   (aparece como `OBDII` u `OBD2`, PIN `1234` o `0000`). Luego en la app: **Buscar adaptador** → tócalo.
4. **Adaptadores BLE:** aparecen directamente en la búsqueda, sin emparejar.
5. En Android 10 y 11 la búsqueda necesita la **ubicación del teléfono encendida** (requisito del sistema para Bluetooth).

Prueba primero el **Modo demo**: simula un carro con CAN, códigos de falla y VIN.

## Si algo falla

- **"La ECU no responde"**: encendido en ON; algunos carros solo responden con el motor en marcha.
- **Se desconecta o se cuelga**: en Más → Ajustes sube la *Pausa entre ciclos* a 100–200 ms (pasa con clones baratos).
- **No encuentra el protocolo**: en Ajustes elige el protocolo a mano (los carros desde 2008 suelen ser CAN 11 bit 500 kbps).
- **Terminal**: envía `ATZ`, `ATRV`, `0100` y mira la respuesta cruda para depurar.

## Notas técnicas

- `bt/` — transporte de bytes: `ClassicTransport` (SPP/RFCOMM con 3 estrategias de socket), `BleTransport`
  (GATT; pares FFF0/FFE0/18F0/OBDLink y búsqueda genérica), `DemoTransport` (simulador ELM327).
- `obd/Elm327.kt` — sesión: inicialización, limpieza de respuestas (eco, `SEARCHING...`, tramas ISO-TP), modos 01/02/03/04/07/09/0A.
- `obd/Dtc.kt` — decodificación de códigos (CAN y K-Line) y descripciones.
- Consumo: PID 5E si existe; si no, MAF → L/h; si no hay MAF, estimación speed-density con MAP, RPM, temperatura de admisión y cilindrada.
- CSV de viajes: `files/trips/viaje_AAAA-MM-DD_HH-MM-SS.csv`, separador coma, punto decimal.

El consumo es una estimación. Borrar códigos no repara la falla.
