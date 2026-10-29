# Validación de extremo a extremo de R1

`e2e/Invoke-R1Validation.ps1` valida Reports R1 contra **Keycloak, Nexora Semantic y Nexora Discovery reales**,
PostgreSQL 17.6 y **datos sintéticos** (`docker/postgres/init/sql/synthetic-erp.sql`). Es una herramienta de
validación: no añade funcionalidad a Reports.

## Qué hace

1. Discovery lee la base sintética; Semantic la importa en una fuente **nueva** del tenant `demo` (y otra del tenant
   `other`). `e2e.editor` crea conceptos, métricas, dimensiones y relaciones, y `e2e.approver` las aprueba.
2. La cuenta de servicio `nexora-reports-dev` (`REPORTS_CLIENT`) crea seis planes lógicos en Semantic.
3. Arranca Reports (`-StartReports`) y ejecuta 24 comprobaciones: autenticación y tenant del JWT, cuenta de servicio,
   pertenencia del plan, resultados esperados, `STALE`, SQL arbitrario, otra fuente u otro tenant, límite de filas,
   `lock_timeout` y auditoría.
4. Escribe `validation/out/r1-e2e-<fecha>.md` y `.json` (ignorados por Git). Nunca escribe secretos ni tokens.

## Cambios que hace fuera de Reports (requieren autorización)

- **Keycloak**, solo con `-ApplyKeycloakSetup`. **Añade** al realm `nexora`, sin modificar nada existente:
  - el rol `REPORT_RUNNER`;
  - el cliente público `nexora-reports-e2e` (audiencias `nexora-semantic` y `nexora-reports`, `principal_type=HUMAN`);
  - el cliente público `nexora-reports-e2e-ai` (`principal_type=AI`);
  - el cliente confidencial `nexora-reports-e2e-other` (cuenta de servicio con `REPORTS_CLIENT`, grupo `tenant-other`);
  - los usuarios `e2e.editor`, `e2e.approver` y `e2e.runner` (grupo `tenant-demo`) y `e2e.runner.other` (grupo
    `tenant-other`), con contraseñas aleatorias que solo existen en memoria.

  `-RevertKeycloakSetup` borra exactamente esos objetos. Además, recrear el contenedor de Keycloak devuelve el realm
  al fichero de importación.
- **Semantic (datos de desarrollo)**: crea en cada ejecución dos fuentes `r1-e2e-*` con sus definiciones,
  relaciones y planes, todo sintético, y **desactiva una relación creada por la propia ejecución** para provocar un
  plan `STALE`. No modifica nada que ya existiera.
- **Discovery**: ninguno. Se arranca con argumentos de línea de comandos, no se cambia su configuración.

## Requisitos (Windows)

- Docker Desktop, JDK 21, Maven 3.9+ y **PowerShell 7** (`winget install Microsoft.PowerShell`).
- Entorno de desarrollo de Semantic levantado **con los tenants dados de alta** (README de Semantic, «Alta de
  tenants»), y Semantic en `http://localhost:8080`.
- Use siempre `localhost` (no `127.0.0.1`) para Keycloak: el emisor de los tokens depende del nombre de host y
  Semantic espera `http://localhost:8180/realms/nexora`.

## Pasos (PowerShell 7, `pwsh`)

```powershell
# 0. Ubicación
Set-Location "D:\desarrollo\java\Nexora AI\Nexora-Reports"

# 1. Las 124 pruebas con PostgreSQL 17.6 (imagen oficial postgres:17.6 vía Testcontainers)
mvn clean verify

# 2. Base de auditoría y base sintética (PostgreSQL 17.6) en 127.0.0.1:5434
Copy-Item docker\.env.example docker\.env      # edite las contraseñas antes de seguir
docker compose -f docker\docker-compose.yml --env-file docker\.env up -d

# 3. Discovery en otra ventana (sin cambiar su configuración)
java -jar "..\Nexora Discovery\target\nexora-discovery-0.1.0-SNAPSHOT.jar" --server.port=8070 `
  --nexora.discovery.api.targets.allowed-networks=127.0.0.1/32 `
  --nexora.discovery.api.targets.allowed-ports=5434

# 4. Validación (solo tras autorizar los cambios en Keycloak descritos arriba)
pwsh -File validation\e2e\Invoke-R1Validation.ps1 -ApplyKeycloakSetup -StartReports

# 5. Opcional: retirar de Keycloak los objetos de validación
pwsh -File validation\e2e\Invoke-R1Validation.ps1 -RevertKeycloakSetup
```

Los secretos se leen de `..\Nexora-Semantic\docker\.env` (`KC_BOOTSTRAP_ADMIN_PASSWORD`, `KC_DEV_USER_PASSWORD`,
`KC_REPORTS_CLIENT_SECRET`) y de `docker\.env` (`REPORTS_DB_OWNER_PASSWORD`, `REPORTS_DB_APP_PASSWORD`,
`SYNTHETIC_READER_PASSWORD`), o de variables de entorno con el mismo nombre. Para la auditoría y el bloqueo de
prueba, el script ejecuta `psql` dentro del contenedor `nexora-reports-postgres` (parámetro `-Psql`).

El script termina con código 0 si todas las comprobaciones pasan, y 1 si alguna falla.
