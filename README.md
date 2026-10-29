# Nexora Reports

Ejecutor seguro de planes lógicos de **Nexora Semantic** sobre PostgreSQL (etapa **R1**).

Un cliente pide ejecutar un plan existente (`dataSourceId` + `planId`). Reports autentica al solicitante, recupera el
plan y su vigencia desde Semantic con la cuenta de servicio de su tenant, valida el contrato
`nexora-logical-plan/1`, lo traduce a **una** consulta parametrizada, la ejecuta con un rol de **solo lectura**,
devuelve el resultado en JSON y lo audita sin datos sensibles.

- Contrato HTTP y errores: [docs/API.md](docs/API.md)
- Diseño, semántica SQL y decisiones: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)
- Estado, pruebas y riesgos: [docs/PROJECT_STATUS.md](docs/PROJECT_STATUS.md)

## Tecnologías

Java 21 · Spring Boot 4.1.1 (WebMVC, Security Resource Server, JDBC, Flyway, Actuator) · Maven · PostgreSQL JDBC ·
HikariCP · Keycloak/OIDC · JUnit 5 · Testcontainers (PostgreSQL) · ArchUnit.

## Requisitos

- JDK 21 y Maven 3.9+.
- Docker (pruebas de integración con Testcontainers y base local).
- Nexora Semantic y su Keycloak de desarrollo (`Nexora-Semantic/docker`).

## Compilar y probar

```bash
mvn clean verify
```

Las pruebas de integración usan la imagen `postgres:17.6` (la misma que Semantic). Para usar otra imagen compatible:
`mvn clean verify -Dnexora.test.postgres-image=<imagen>`.

## Levantar Reports en local

1. **Base de datos de Reports y base sintética**

   ```bash
   cp docker/.env.example docker/.env      # elija sus contraseñas
   docker compose -f docker/docker-compose.yml --env-file docker/.env up -d
   ```

   Crea en `127.0.0.1:5434`:
   - `nexora_reports`: auditoría. Flyway la migra con el propietario; la aplicación usa `nexora_reports_app`
     (solo `SELECT` e `INSERT`).
   - `synthetic_erp`: datos **sintéticos** (`plant`, `finance`, `docs`) y el rol `nexora_reader` (solo `SELECT`).

2. **Semantic y Keycloak**: levántelos desde `Nexora-Semantic` (ver su README). Importe en Semantic los metadatos de
   `synthetic_erp` (Discovery → `POST /api/v1/data-sources/{id}/snapshots`), apruebe definiciones y relaciones y cree
   un plan con la cuenta `nexora-reports-dev` (`POST …/logical-plans`). Anote `dataSourceId` y `planId`.

3. **Keycloak — configuración que Reports necesita** (no existe todavía en el realm de desarrollo de Semantic y R1
   no lo modifica; hágalo en la consola de administración):
   - rol de realm `REPORT_RUNNER` asignado a los usuarios que pueden ejecutar informes;
   - un *client scope* (o *mapper*) de audiencia `nexora-reports` incluido en los tokens de esos usuarios;
   - los usuarios deben llevar `tenant` y `principal_type` (ya los aportan el grupo del tenant y el *mapper* del
     cliente).

   La cuenta de servicio `nexora-reports-dev` (rol `REPORTS_CLIENT`, grupo `tenant-demo`) ya existe y es la que
   Reports usa para llamar a Semantic en nombre del tenant demo.

4. **Arrancar** (todas las credenciales por variables de entorno):

   ```bash
   export REPORTS_DB_URL='jdbc:postgresql://localhost:5434/nexora_reports?currentSchema=reports'
   export REPORTS_DB_USER=nexora_reports_app
   export REPORTS_DB_PASSWORD=<REPORTS_DB_APP_PASSWORD>
   export REPORTS_DB_OWNER_USER=nexora_reports_owner
   export REPORTS_DB_OWNER_PASSWORD=<REPORTS_DB_OWNER_PASSWORD>
   export REPORTS_OIDC_ISSUER_URI=http://localhost:8180/realms/nexora
   export REPORTS_SEMANTIC_BASE_URL=http://localhost:8080

   # Cuenta de servicio REPORTS_CLIENT del tenant demo
   export NEXORA_REPORTS_SEMANTIC_SERVICEACCOUNTS_0_TENANTID=<UUID del tenant demo>
   export NEXORA_REPORTS_SEMANTIC_SERVICEACCOUNTS_0_CLIENTID=nexora-reports-dev
   export NEXORA_REPORTS_SEMANTIC_SERVICEACCOUNTS_0_CLIENTSECRET=<KC_REPORTS_CLIENT_SECRET>

   # Destinos permitidos y conexión de solo lectura de la fuente
   export NEXORA_REPORTS_ENTERPRISE_ALLOWEDHOSTS_0=localhost
   export NEXORA_REPORTS_ENTERPRISE_ALLOWEDPORTS_0=5434
   export NEXORA_REPORTS_ENTERPRISE_SOURCES_0_TENANTID=<UUID del tenant demo>
   export NEXORA_REPORTS_ENTERPRISE_SOURCES_0_DATASOURCEID=<dataSourceId en Semantic>
   export NEXORA_REPORTS_ENTERPRISE_SOURCES_0_HOST=localhost
   export NEXORA_REPORTS_ENTERPRISE_SOURCES_0_PORT=5434
   export NEXORA_REPORTS_ENTERPRISE_SOURCES_0_DATABASE=synthetic_erp
   export NEXORA_REPORTS_ENTERPRISE_SOURCES_0_USERNAME=nexora_reader
   export NEXORA_REPORTS_ENTERPRISE_SOURCES_0_PASSWORD=<SYNTHETIC_READER_PASSWORD>
   export NEXORA_REPORTS_ENTERPRISE_SOURCES_0_SSLMODE=disable   # solo en local; por defecto verify-full

   mvn spring-boot:run      # escucha en :8090
   ```

5. **Ejecutar un plan**

   ```bash
   curl -X POST -H "Authorization: Bearer $USER_TOKEN" \
     http://localhost:8090/api/v1/data-sources/$DATA_SOURCE_ID/plans/$PLAN_ID/executions
   ```

   Ejemplo de respuesta en [docs/API.md](docs/API.md).

## Variables de entorno

| Variable | Defecto | Uso |
|---|---|---|
| `REPORTS_PORT` | `8090` | Puerto HTTP |
| `REPORTS_DB_URL` | `jdbc:postgresql://localhost:5434/nexora_reports?currentSchema=reports` | Base de auditoría |
| `REPORTS_DB_USER` / `REPORTS_DB_PASSWORD` | `nexora_reports_app` / vacío | Rol de la aplicación |
| `REPORTS_DB_OWNER_USER` / `REPORTS_DB_OWNER_PASSWORD` | los de la aplicación | Rol con el que Flyway migra |
| `REPORTS_DB_APP_USER` | `nexora_reports_app` | Rol al que V1 concede `SELECT, INSERT` |
| `REPORTS_OIDC_ISSUER_URI` | `http://localhost:8180/realms/nexora` | Emisor (**obligatorio**) |
| `REPORTS_OIDC_AUDIENCE` | `nexora-reports` | Audiencia exigida a los usuarios |
| `REPORTS_TENANT_CLAIM` / `REPORTS_PRINCIPAL_TYPE_CLAIM` / `REPORTS_ROLES_CLAIM` | `tenant` / `principal_type` / `roles` | Claims (admite rutas con punto, p. ej. `realm_access.roles`) |
| `REPORTS_SEMANTIC_BASE_URL` | `http://localhost:8080` | Semantic |
| `REPORTS_SEMANTIC_TOKEN_URI` | token endpoint del realm `nexora` | Grant *client credentials* |
| `REPORTS_SEMANTIC_AUDIENCE` | `nexora-semantic` | Audiencia exigida a los tokens de servicio |
| `REPORTS_SEMANTIC_CONNECT_TIMEOUT` / `REPORTS_SEMANTIC_READ_TIMEOUT` | `2s` / `10s` | Tiempos de espera |
| `NEXORA_REPORTS_SEMANTIC_SERVICEACCOUNTS_<n>_{TENANTID,CLIENTID,CLIENTSECRET}` | — | Una cuenta `REPORTS_CLIENT` por tenant |
| `NEXORA_REPORTS_ENTERPRISE_ALLOWEDHOSTS_<n>` / `…_ALLOWEDPORTS_<n>` | vacío | Destinos permitidos (sin lista, ninguna fuente arranca) |
| `NEXORA_REPORTS_ENTERPRISE_SOURCES_<n>_{TENANTID,DATASOURCEID,HOST,PORT,DATABASE,USERNAME,PASSWORD,SSLMODE,MAXCONCURRENT}` | `SSLMODE=verify-full`, `MAXCONCURRENT=4` | Conexión de solo lectura por (tenant, fuente) |
| `REPORTS_DEFAULT_MAX_ROWS` / `REPORTS_MAX_ROWS_CEILING` | `1000` / `10000` | Filas por defecto y máximo |
| `REPORTS_STATEMENT_TIMEOUT` / `REPORTS_LOCK_TIMEOUT` / `REPORTS_MAX_DURATION` | `30s` / `2s` / `60s` | Límites de tiempo |
| `REPORTS_FETCH_SIZE` / `REPORTS_ACQUIRE_TIMEOUT` / `REPORTS_MAX_CONCURRENT_EXECUTIONS` | `500` / `2s` / `16` | Lectura y concurrencia |

El rol de la base empresarial debe tener **solo** `CONNECT`, `USAGE` y `SELECT`; Reports rechaza superusuarios y
roles con privilegios de escritura sobre las tablas que lee.

## Reglas de este repositorio

- Ninguna funcionalidad futura se documenta como implementada.
- Sin secretos en el repositorio (`docker/.env` está ignorado).
- Datos de prueba exclusivamente sintéticos.
