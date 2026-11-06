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
| **Más** | Sensores por categoría (y explorador de PIDs no decodificados), módulos, terminal AT, viajes en CSV, vehículos, ajustes |

La app consulta los PIDs soportados por la ECU (0100, 0120, 0140…) y solo muestra lo que tu carro reporta.

## Novedades de la versión 1.1

- **Mis vehículos**: marca, modelo, versión, año, motor, combustible, cilindrada, tracción, transmisión y mercado de origen.
  Con eso la app elige el **orden de protocolos** a probar (p. ej. Nissan Frontier NP300 diésel 2013 → KWP lento, KWP rápido,
  ISO 9141, CAN, automático) y **aprende** el que funcionó para la próxima vez. Incluye ejemplos listos
  (Subaru Impreza 2.0i 2014 FB20, Renault Sandero 16V 2012, Nissan Frontier NP300 4x4 diésel 2013).
- **Escaneo completo de protocolos** cuando la ECU no responde: prueba los 10 protocolos del ELM327 y deja usar el que respondió.
- **Más sensores** (≈ 75): turbo (PID 70/87), presión del riel diésel, EGR, DPF, temperatura de gases de escape,
  lambda de banda ancha, torque, odómetro y más; calculados: presión de turbo, torque, potencia, aceleración, AFR.
- **Más gráficas**: líneas, superpuestas, dispersión X-Y (p. ej. RPM vs velocidad muestra las marchas), histograma y medidores;
  combinaciones rápidas, suavizado y ventanas hasta 10 min.
- **Internet**: actualización de la base de vehículos desde este repositorio, decodificación del VIN (NHTSA vPIC),
  búsqueda web del protocolo y botón para compartir un protocolo confirmado y sumarlo a la base.
- **Verificación de módulos (solo lectura)**: qué ECUs responden, su CALID, CVN, nombre y si soportan modos 06/08.
  La app no reprograma nada: escribir software en una ECU con un ELM327 puede dejarla inservible.

## Base de vehículos (`app/src/main/assets/vehicles.json`)

Es la misma que la app descarga en **Más → Ajustes → Base de vehículos**. Para agregar un carro, añade una regla en `rules`
y **sube el número de `version`**; la app la tomará al buscar actualización (sin reinstalar).

```json
{"make": "Nissan", "model": "Frontier|NP300", "fuel": "diesel", "from": 2005, "to": 2015,
 "protocols": ["4", "5", "3"], "slowEcu": true, "confidence": "media", "note": "…"}
```

`make` coincide exacto; `model` y `engine` coinciden si el texto del perfil contiene alguna alternativa (`|`).
Gana la regla más específica. Protocolos: 1 J1850 PWM, 2 J1850 VPW, 3 ISO 9141-2, 4 KWP lento, 5 KWP rápido,
6 CAN 11/500, 7 CAN 29/500, 8 CAN 11/250, 9 CAN 29/250, A J1939.

## Cómo obtener el .apk

### Opción A — GitHub Actions (sin instalar nada)

1. El código de esta versión está en la rama [`feature/obd-pulse-v1.1`](https://github.com/BrayanGM28/OBD2-APP/tree/feature/obd-pulse-v1.1) del repositorio [OBD2-APP](https://github.com/BrayanGM28/OBD2-APP).
2. Cada push a esa rama activa **Actions → Compilar APK** automáticamente.
3. Al terminar, descarga `OBDPulse.apk` desde **Artifacts** en el resumen de la ejecución o desde **Releases**. En un teléfono, inicia sesión en GitHub si el repositorio es privado.

### Opción B — Android Studio

Abre la carpeta del proyecto en Android Studio → espera la sincronización de Gradle →
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
- **No encuentra el protocolo**: elige tu vehículo en Conexión → Mi vehículo; si aun así no responde, usa **Probar todos**.
  Si ningún protocolo responde con el motor encendido, la ECU probablemente no habla OBD-II genérico (solo el protocolo
  propio de la marca), algo común en algunos diésel para Latinoamérica.
- **Terminal**: envía `ATZ`, `ATRV`, `0100` y mira la respuesta cruda para depurar.

## Notas técnicas

- `bt/` — transporte de bytes: `ClassicTransport` (SPP/RFCOMM con 3 estrategias de socket), `BleTransport`
  (GATT; pares FFF0/FFE0/18F0/OBDLink y búsqueda genérica), `DemoTransport` (simulador ELM327).
- `obd/Elm327.kt` — sesión: inicialización, limpieza de respuestas (eco, `SEARCHING...`, tramas ISO-TP), modos 01/02/03/04/07/09/0A.
- `obd/Dtc.kt` — decodificación de códigos (CAN y K-Line) y descripciones.
- Consumo: PID 5E si existe; si no, MAF → L/h; si no hay MAF, estimación speed-density con MAP, RPM, temperatura de admisión y cilindrada.
- CSV de viajes: `files/trips/viaje_AAAA-MM-DD_HH-MM-SS.csv`, separador coma, punto decimal.

El consumo es una estimación. Borrar códigos no repara la falla.
