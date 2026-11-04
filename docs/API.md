# Nexora Reports — API (R1 y A4-B9)

R1: ejecutar un plan lógico **ya creado y autorizado en Nexora Semantic** (abajo). A4-B9 añade la ejecución de
planes de reporte compilados por Semantic, la lectura de la ejecución, la descarga del Excel y la gestión de la
tenencia: ver [«A4-B9»](#a4-b9--planes-de-reporte-excel-y-tenencia).

## `POST /api/v1/data-sources/{dataSourceId}/plans/{planId}/executions`

| Aspecto | Valor |
|---|---|
| Autenticación | `Authorization: Bearer <JWT>` emitido por el proveedor OIDC configurado (Keycloak en desarrollo) |
| Audiencia exigida | `nexora-reports` (configurable con `REPORTS_OIDC_AUDIENCE`) |
| Permiso | `reports:execute`, concedido por el rol `REPORT_RUNNER` |
| Tenant | Sale **solo** del claim `tenant` del token (UUID canónico). Nunca de la ruta ni del cuerpo |
| Tipos de principal | `HUMAN` y `SERVICE` pueden ejecutar con el rol. `AI` **nunca** recibe el permiso (no existe política de delegación) |
| Cuerpo | Opcional. Solo `{"maxRows": n}` con `1 ≤ n ≤` techo del servidor (10 000 por defecto) |
| Respuesta | `200` con el resultado tabular; cabeceras `X-Execution-Id` y `X-Request-Id` |

La petición **no** acepta SQL, documentos de plan, tenants, hosts, puertos, usuarios ni contraseñas: cualquier
propiedad desconocida se rechaza con `400 malformed_request`, igual que los números enviados como texto o las claves
duplicadas.

### Ejemplo (datos sintéticos)

```bash
curl -X POST \
  -H "Authorization: Bearer $USER_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"maxRows": 100}' \
  http://localhost:8090/api/v1/data-sources/<dataSourceId>/plans/<planId>/executions
```

Respuesta para el plan «`suma_valor` y `num_lecturas` por `fecha` (MONTH) y `nota`, con `nota IN (ok, revisar)`,
`fecha ≥ 2026-01-01T00:00+01:00` y `suma_valor > 100`» sobre la base sintética (`docker/postgres/init/sql`):

```json
{
  "executionId": "6f1c…",
  "planId": "dfb3fe2c-2b92-47b2-8176-6f7fe48b8030",
  "dataSourceId": "40d88cc6-d9c8-4c8b-b08e-c3363683a128",
  "planFingerprint": "62228166d4ef…",
  "validity": "CURRENT",
  "columns": [
    {"name": "fecha", "role": "DIMENSION", "type": "DATE"},
    {"name": "nota", "role": "DIMENSION", "type": "TEXT"},
    {"name": "suma_valor", "role": "METRIC", "type": "NUMERIC"},
    {"name": "num_lecturas", "role": "METRIC", "type": "INTEGER"}
  ],
  "rows": [["2026-01-01", "revisar", "200.500", 1]],
  "rowCount": 1,
  "truncated": false,
  "maxRows": 100,
  "durationMs": 41,
  "warnings": []
}
```

### Campos de la respuesta

| Campo | Significado |
|---|---|
| `executionId` | Identificador de la ejecución (también en la auditoría) |
| `planId`, `dataSourceId` | El plan y la fuente ejecutados |
| `planFingerprint` | Huella **opaca** devuelta por Semantic. Solo se compara por igualdad; no es una firma verificable |
| `validity` | `CURRENT` o `CURRENT_WITH_CHANGES` (estado confirmado por Semantic **después** de la consulta) |
| `columns` | En el orden de `rows`: `name` (clave de la definición), `role` (`DIMENSION`/`METRIC`) y `type` |
| `rows` | Filas como arrays. Ver representación de tipos abajo |
| `rowCount`, `truncated`, `maxRows` | Filas devueltas; `truncated=true` si había más filas que `maxRows` |
| `durationMs` | Duración total de la ejecución en Reports |
| `warnings` | `PLAN_CURRENT_WITH_CHANGES`, `RESULT_TRUNCATED` (mensajes fijos, sin datos) |

Representación de los valores (`type`):

| Tipo | JSON |
|---|---|
| `INTEGER` (`COUNT`) | número |
| `NUMERIC` (`SUM`, `AVG`, dimensiones numéricas) | **texto decimal exacto** (`"200.500"`), para no perder precisión |
| `DATE` | `"2026-01-01"`. Las dimensiones temporales devuelven el **primer día del intervalo** |
| `TIMESTAMP` | `"2026-01-12T02:00"` (hora de pared) |
| `TIMESTAMP_TZ` | instante en UTC, `"2026-01-12T02:00Z"` |
| `BOOLEAN` | `true` / `false` |
| `TEXT`, `TIME`, `UUID` | texto |
| NULL | `null` (un valor de agrupación NULL forma su propio grupo y se ordena al final) |

Las filas se ordenan por las claves de agrupación (ascendente, NULL al final), de modo que el truncamiento es
determinista.

## Errores

Cuerpo `application/problem+json` estable:

```json
{
  "type": "about:blank",
  "title": "Conflict",
  "status": 409,
  "code": "plan_stale",
  "detail": "The plan is STALE in Semantic and cannot be executed.",
  "instance": "/api/v1/data-sources/…/plans/…/executions",
  "correlationId": "c0dcd4e4-…"
}
```

Nunca incluye SQL, valores de filtros, trazas, credenciales ni detalles de conexión. `detail` es un texto fijo por
código. El motivo interno queda en el log con el `correlationId`.

| HTTP | `code` | Cuándo |
|---|---|---|
| 400 | `malformed_request` | Cuerpo no JSON, propiedades desconocidas (`sql`, `plan`, `host`, `tenantId`…), tipos incorrectos |
| 400 | `validation_failed` | `maxRows` fuera de rango, UUID inválido en la ruta (con `errors[{field,message}]`) |
| 401 | `unauthorized` | Sin token, firma/emisor/audiencia/expiración inválidos, sin `tenant` o `principal_type` válidos |
| 403 | `forbidden` | Sin `reports:execute` (incluye a cualquier principal `AI`) |
| 403 | `requester_not_allowed` | El tenant no está activo en Semantic |
| 404 | `not_found` | La fuente o el plan no existen **para el tenant del token** |
| 405 / 406 / 415 | `method_not_allowed` / `not_acceptable` / `unsupported_media_type` | |
| 409 | `plan_stale` | Semantic marca el plan como `STALE` |
| 409 | `plan_changed_during_execution` | El plan dejó de ser ejecutable mientras corría la consulta; el resultado se descartó |
| 409 | `physical_schema_mismatch` | El esquema vivo no coincide con el plan (tabla/columna ausente, tipo de otra categoría, PK distinta en un JOIN) |
| 409 | `data_source_not_configured` | No hay conexión de solo lectura configurada para (tenant, fuente) |
| 409 | `data_source_not_executable` | La fuente no es PostgreSQL o no está `ACTIVE` |
| 409 | `result_too_large` | (A4-B4, camino `/2`; R1 no lo emite) Las filas superan el límite de filas o de memoria estimada de la ejecución |
| 409 | `tenant_scope_unverified` | (A4-B5, camino `/2`; R1 no lo emite) No hay un registro de tenencia aprobado o alguna comprobación de tenencia falla (TLS, identidad de la base y del clúster, rol, esquemas, objetos del plan); no se ha leído ningún dato |
| 409 | `value_not_representable` | (A4-B4, camino `/2`; R1 no lo emite) Un valor de la fuente no se puede representar exactamente en su tipo de salida (NaN, infinito, decimales en un entero) |
| 422 | `plan_not_executable` | El plan no supera la validación estricta; incluye `violations[{code,path}]` |
| 429 | `too_many_executions` | Límite de ejecuciones concurrentes alcanzado |
| 500 | `data_source_role_not_read_only` | El rol de base de datos configurado no es estrictamente de solo lectura |
| 500 | `data_source_target_not_allowed` | El destino configurado no está en la lista permitida |
| 500 | `execution_failed` / `internal_error` | Error no previsto |
| 502 | `semantic_contract_violation` | Semantic devolvió algo incoherente (otro tenant, otro plan, otra fuente…) |
| 502 | `service_identity_invalid` | La cuenta de servicio del tenant no es válida o su token no lleva ese tenant |
| 503 | `semantic_unavailable` | Semantic o el emisor de tokens no responden |
| 503 | `plan_validity_unconfirmed` | Semantic no pudo confirmar la vigencia (nunca se presupone) |
| 503 | `data_source_unavailable` | No se puede conectar a la base empresarial o faltan privilegios de lectura |
| 503 | `audit_unavailable` | No se pudo registrar la auditoría: el resultado **no** se entrega |
| 504 | `execution_timeout` | `statement_timeout`, `lock_timeout` o duración máxima superados |

Códigos de `violations` (422): `UNREADABLE_DOCUMENT`, `UNSUPPORTED_FORMAT_VERSION`, `MISSING_FIELD`,
`INCONSISTENT_FINGERPRINT`, `INVALID_NATURAL_KEY`, `UNKNOWN_REFERENCE`, `DUPLICATE_REFERENCE`, `INVALID_ORDER`,
`JOIN_NOT_AUTHORIZED`, `MULTIPLICATION_RISK`, `UNIQUENESS_NOT_PROVEN`, `JOIN_CONDITION_MISMATCH`, `UNSAFE_FILTER`,
`INCONSISTENT_GRAIN`, `INCONSISTENT_DEFINITION`, `INCONSISTENT_SAFETY`, `INCONSISTENT_OPERATIONS`,
`UNSUPPORTED_TYPE`, `UNSUPPORTED_OPERATOR`, `INVALID_FILTER_VALUE`, `INVALID_TIME_ZONE`,
`COMPLEXITY_LIMIT_EXCEEDED`. `path` señala el campo del documento (p. ej. `$.plan.operations[1].kind`), nunca un valor.

## Otros endpoints

| Endpoint | Acceso |
|---|---|
| `GET /actuator/health` (y `/liveness`, `/readiness`) | público, sin detalles |
| Cualquier otro | denegado |

## Llamadas que Reports hace a Semantic

Siempre con el token de la cuenta de servicio `REPORTS_CLIENT` **del tenant del solicitante**:

| Llamada | Permiso en Semantic |
|---|---|
| `GET /api/v1/data-sources/{id}` | `semantic:catalog:read` |
| `GET /api/v1/data-sources/{id}/logical-plans/{planId}` | `semantic:resolve` |
| `GET /api/v1/data-sources/{id}/logical-plans/{planId}/validity` (antes y después de la consulta) | `semantic:resolve` |

Reports nunca crea planes ni llama a `POST …/logical-plans`.

## A4-B9 — Planes de reporte, Excel y tenencia

Implementado en A4-B9, **pendiente de verificación en Windows**. Mismo servidor, misma validación de tokens y mismos
problemas `application/problem+json` que R1. El tenant sale **solo** del token. Una ejecución solo es visible para su
propietario (mismo tenant, misma fuente y mismo sujeto); cualquier otra se responde igual que una inexistente (404).

| Aspecto | Valor |
|---|---|
| Audiencia | `nexora-reports` (las personas la piden con el scope opcional `nexora-reports` de `nexora-cli`; ver `nexora-deploy/docs/CONFIGURATION.md` §9) |
| Ejecutar, leer y descargar | `reports:execute` (rol `REPORT_RUNNER`); `HUMAN` o `SERVICE`; `AI` nunca |
| Tenencia | `reports:tenancy:manage` (rol `REPORTS_TENANCY_ADMIN`); **solo `HUMAN`**: el permiso se quita a `SERVICE` y a `AI` |
| Fuente de datos | Solo con `sslmode=verify-full` y un registro de tenencia aprobado por dos personas distintas |

### `POST /api/v1/data-sources/{dataSourceId}/report-plans/{reportPlanId}/runs`

Ejecuta, de forma síncrona, un plan de reporte (`nexora-report-plan/1`) **ya compilado por Semantic** (A3). Sin cuerpo.
Pasos, cada uno fallando cerrado: token → destino de solo lectura configurado, **sin resolver su secreto** (B10-O3)
→ Semantic (fuente activa PostgreSQL; plan de reporte, especificación y planes lógicos) → inicio registrado →
validación independiente (B2) → vigencia antes de leer → traducción (B3) → credenciales de solo lectura, que deben
llevar exactamente al destino del primer paso (B10-O3) → una instantánea REPEATABLE READ de solo lectura con la comprobación de tenencia y del
esquema (B4, B5) → vigencia después de leer → ensamblado y Excel (B6, B7) → registro del resultado y del archivo
(B8).

| Respuesta | Cuándo |
|---|---|
| `201` + `nexora-report-run/1`, `Location: /api/v1/data-sources/{ds}/report-runs/{runId}`, `X-Run-Id` | `COMPLETED`, `PARTIAL` (algún dataset truncado, con aviso) o `REFUSED` (con `refusal.code`) |
| `500 run_failed` + `X-Run-Id` | Error interno (FAILED): queda registrado, sin documento |
| `422 report_plan_invalid` + `X-Run-Id` | REFUSED porque el propio plan de reporte no es válido: su huella es desconocida y el contrato la exige |
| `503 run_storage_unavailable` (+ `X-Run-Id` si ya empezó) | El registro de ejecuciones de Reports no responde; la reconciliación cerrará la ejecución |
| `404 not_found`, `409 data_source_not_configured`/`data_source_not_executable`, `503 semantic_unavailable`, `502 …` | Antes de iniciar la ejecución: no queda ninguna ejecución |
| `400 malformed_request` | **A4-B10-b:** la petición lleva cuerpo (también enviado por fragmentos). Después de 401 y 403, antes de todo lo demás: sin Semantic, sin credenciales, sin ejecución |

Códigos de `refusal.code` (estables; el mensaje lo genera Reports, sin datos de negocio): `plan_stale`,
`plan_changed_during_execution`, `plan_validity_unconfirmed`, `tenant_scope_unverified`, `physical_schema_mismatch`,
`result_too_large`, `value_not_representable`, `too_many_executions` (reintentable), `execution_timeout`,
`execution_failed`, `data_source_unavailable`, `data_source_role_not_read_only`, `data_source_target_not_allowed`,
`contract_invalid`, `plan_not_supported`, `identity_mismatch`, `fingerprint_mismatch`, `budget_exceeded`,
`spec_mismatch`, `report_layout_invalid`, `artifact_quota_exceeded`, `artifact_too_large`, y desde B10-O3
`data_source_not_configured` y `data_source_configuration_changed` (ver «B10-O3»). Toda negativa ocurrida
después de iniciar es un REFUSED (201): el contrato no tiene otro estado.

Cada `report-run/1` y `report-artifact/1` se valida contra el esquema real del contrato y RUN-R1 antes de salir de
Reports; uno que no valide nunca se devuelve.

### `GET /api/v1/data-sources/{dataSourceId}/report-runs/{runId}`

| Respuesta | Cuándo |
|---|---|
| `200` + el mismo `nexora-report-run/1` de la respuesta del POST (reconstruido del registro) | COMPLETED, PARTIAL o REFUSED |
| `409 run_in_progress` | Sin resultado y dentro de su plazo (120 s por defecto) |
| `409 run_failed` / `409 run_interrupted` | FAILED (error interno) / sin resultado antes de su plazo |
| `422 report_plan_invalid` | REFUSED sin huella conocida del plan de reporte |
| `404 not_found` | Cualquier otro caso (otra persona, otro tenant, otra fuente o inexistente) |

### `GET /api/v1/data-sources/{dataSourceId}/report-runs/{runId}/artifact`

`200` con el `.xlsx` (`nexora-excel-report/1`): `Content-Type:
application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`, `Content-Disposition: attachment;
filename="nexora-report-<runId>.xlsx"` (nunca el título), `X-Content-SHA256` (igual al `sha256` del artefacto),
`Cache-Control: no-store`, `X-Content-Type-Options: nosniff`. Los bytes se verifican (tamaño y SHA-256) antes de
enviarlos; después de escribirlos y vaciarlos se registra `ARTIFACT_DELIVERED` (no prueba que el cliente los
recibiera); si la escritura falla, `ARTIFACT_DELIVERY_FAILED`.

| Error | Cuándo |
|---|---|
| `410 artifact_gone` | Vencido (7 días por defecto) o purgado |
| `500 artifact_integrity_failed` | Los bytes guardados no coinciden con sus metadatos: no se entrega nada |
| `429 too_many_downloads` | Más de 4 descargas simultáneas (configurable) |
| `404 not_found` | Sin artefacto para el solicitante (incluye una ejecución REFUSED) |

### Tenencia: `/api/v1/data-sources/{dataSourceId}/tenancy/registrations`

| Operación | Respuesta |
|---|---|
| `POST …/registrations` con `{"model": "DEDICATED_SCHEMAS" \| "DEDICATED_DATABASE", "schemas": ["…"]}` | `201` con la versión `PENDING`: identidad de la base, rol y esquemas **capturados de la propia base** con las credenciales configuradas |
| `POST …/registrations/{registrationId}/approval` | `201` con la decisión. La aprueba **otra persona** del mismo tenant |
| `POST …/registrations/{registrationId}/revocation` | `201` con la decisión. Desde entonces, las ejecuciones contra esa versión se rechazan |
| `GET …/registrations` | `200` con las versiones y su estado (`PENDING`, `ACTIVE`, `SUPERSEDED`, `REVOKED`) |

`schemas` debe ser **exactamente** el conjunto de esquemas que el rol de solo lectura puede usar, `public` incluido si
PUBLIC conserva USAGE sobre él.

| HTTP | `code` | Cuándo |
|---|---|---|
| 400 | `validation_failed` / `malformed_request` | Sin modelo o esquemas, esquemas repetidos o de más de 63 caracteres, cuerpo inválido |
| 403 | `forbidden` / `tenancy_requires_person` | Sin `reports:tenancy:manage` / el principal no es una persona |
| 404 | `not_found` | El registro no es de esa fuente o de ese tenant |
| 409 | `tenancy_same_person` | Quien aprueba es quien registró |
| 409 | `tenancy_not_pending` | Solo se aprueba una versión pendiente más nueva que la activa; una revocada sigue revocada |
| 409 | `data_source_not_configured` | No hay credenciales de solo lectura para la fuente |
| 422 | `tenancy_model_not_supported` | `SHARED_ROWS` |
| 422 | `tenancy_capture_mismatch` | Los esquemas declarados no son los que el rol puede usar, o el rol tiene acceso más allá de sus esquemas |
| 422 | `tls_required` | La conexión no usa `verify-full` o la sesión no está cifrada |
| 422 | `transport_exception_expired` | EX-TLS-1: la excepción temporal de transporte de la fuente ha caducado (registro o aprobación) |
| 409 | `transport_exception_mismatch` | EX-TLS-1: al aprobar, la fuente ya no usa la misma excepción con la que se capturó la versión |

### EX-TLS-1 — Excepción temporal de transporte (solo compilación de desarrollo)

Para fuentes que no ofrecen TLS a los clientes (bloqueo de V-C2), una **compilación separada** de Reports puede
conectarse a **una** fuente concreta con `sslmode=disable`. Con ella **no** están garantizados ni el cifrado de la
conexión ni la identidad del servidor, aunque exista una VPN o `pg_stat_ssl` declare cifrado (detrás de un pooler o
proxy puede describir otro tramo; bajo la excepción se ignora y nunca se presenta como cifrado).

| Control | Regla (incumplirla impide arrancar o rechaza la operación) |
|---|---|
| F1 compilación | Solo `mvn -Pdev-insecure-transport` incluye `META-INF/nexora/insecure-transport.properties` (con el instante de la compilación). Una compilación normal rechaza al arrancar cualquier excepción configurada. Una compilación con la capacidad nunca arranca con el perfil `prod` |
| F2 perfil | Perfil Spring `insecure-transport-dev` |
| Fuente y destino | `nexora.reports.enterprise.sources[i].transport-exception.{id, expires-at, binding}`; `binding` = SHA-256 de `nexora-reports-transport-exception/1` (tenant, fuente, host, puerto, base, usuario, `sslMode=disable`, id, caducidad); host = IPv4 privada literal dentro de `allowed-hosts` |
| F4 caducidad | `expires-at` futura y como máximo 14 días después de la compilación; se comprueba al arrancar, al registrar, al aprobar y en cada ejecución |
| F5 modo | Solo `sslMode=disable` |
| Tenencia | La versión guarda la excepción (`transportException` en la respuesta, con `encrypted=false` y `serverIdentityVerified=false`; parte de la huella). Ejecución y aprobación exigen **la misma** excepción vigente; un registro con `verify-full` nunca autoriza una ejecución sin TLS ni al revés |
| Ejecución | Mismas comprobaciones de identidad, rol, esquemas, relaciones y RLS; REFUSED `tenant_scope_unverified` si el transporte no cuadra o la excepción caducó. Cada ejecución lleva el aviso `transport_not_verified` (sin `datasetId`) en `nexora-report-run/1`; el estado y el Excel (`nexora-excel-report/1`) no cambian |

Implementación: `credentials.domain.TransportPolicy` (única decisión de transporte, usada por
`TenancyRegistrationService` y `TenantScopeProbe`), `TransportException`, `ConfiguredCredentialProvider`
(validación al arrancar), `TransportCapability`, migraciones `V5__record_transport_exceptions.sql` (columnas) y
`V6__enforce_complete_transport_exceptions.sql` (restricción «las tres o ninguna» corregida: con V5 una `CHECK` que
se evalúa a NULL se daba por cumplida). Al leer, `JdbcTenancyRegistry` rechaza como corrupta una fila con la excepción
parcial o mal formada en lugar de leerla como «sin excepción».

### Llamadas que Reports hace a Semantic (A4-B9)

Con el token REPORTS_CLIENT del tenant del solicitante (`semantic:resolve`; la fuente con `semantic:catalog:read`):
`GET /api/v1/data-sources/{ds}`, `GET …/report-plans/{rp}`, `GET …/report-plans/{rp}/spec`,
`GET …/report-plans/{rp}/logical-plans/{planId}` y `GET …/report-plans/{rp}/validity` (antes y después de leer).
Reports no compila especificaciones (`POST …/report-plans` lo hace una cuenta de servicio con REPORTS_CLIENT).

### Limitaciones (P-4)

- Síncrono: la respuesta llega cuando el Excel está guardado (plazo de una ejecución: 120 s).
- Solo el perfil `basic-a`; sin lenguaje natural (A5): la especificación la escribe y compila otra pieza.
- `GET /api/v1/capabilities`: añadido en A4-B10-b (abajo); lo que `nexora-capabilities/1` no puede expresar está en esta lista.
- Sin seguridad por filas en la base de Reports (B8-O2).

## A4-B10-b — Capacidades, cuerpo del POST y endurecimiento

Implementado en A4-B10-b (2026-11-02) y **verificado en Windows** (2026-11-02: 707 pruebas, 0 fallos, 0 errores, 0 omitidas; ver `PROJECT_STATUS.md`).

### `GET /api/v1/capabilities`

| Aspecto | Valor |
|---|---|
| Autorización | Cualquier principal **autenticado** de un tenant: persona, servicio o la identidad técnica de Intelligence (`AI`); sin permiso específico. Nunca anónimo: sin token, con otra audiencia o con un token sin tenant válido, `401` |
| Respuesta | `200` con `nexora-capabilities/1` (`service: reports`), validado contra el esquema real al arrancar (si no valida, Reports no arranca) |
| Efectos | Ninguno: no consulta Semantic, las fuentes ni el registro. Leerlo no concede nada más: una `AI` sigue sin poder ejecutar (`403`) |

Contenido, generado de las constantes y los techos que aplica el validador (`ReportsProfile`, `ReportsCeilings`):

- `contracts`: `nexora-report-plan/1`, `nexora-logical-plan/2`, `nexora-logical-plan/1`, `nexora-report-run/1`,
  `nexora-excel-report/1`: la lista de la v3 §5. `nexora-report-artifact/1` (que Reports emite) y
  `nexora-report-spec/1` (que lee) **no** se anuncian sin una decisión normativa posterior (B10-b.1).
- Perfil `basic-a`: `maxDatasets` 3, `maxSections` 1, `maxJoinsPerDataset` 2, `datasetModes` DETAIL y AGGREGATE,
  `aggregations` COUNT, los nueve operadores de filtro, `itemTypes` `table` y `summaryTable`, sin composiciones,
  `planOperations` SCAN, JOIN, FILTER, AGGREGATE, PROJECT, ORDER y LIMIT, intercalación `C`, consistencia
  `SINGLE_READ_ONLY_TRANSACTION/REPEATABLE_READ`, unicidad `PRIMARY_KEY` y `maxRowsPerPlan` **efectivo** (el techo
  de Reports; hoy 10 000). Coincide con el ejemplo publicado de Semantic.

Dónde se exige cada valor: el esquema de `nexora-report-spec/1` ya limita, para `basic-a`, datasets, joins por dataset,
secciones, tipos de elemento, agregaciones y composiciones (`contract_invalid`); las reglas del perfil limitan los planes
lógicos (`plan_not_supported`); los techos, filas y planes (`budget_exceeded`); el ensamblado vuelve a rechazar tipos de
elemento y composiciones (`report_layout_invalid`).

Limitaciones que el esquema no expresa (P-4): evidencia de tenencia obligatoria (registro aprobado por dos personas y
`verify-full`); un único formato de salida (Excel, `nexora-excel-report/1`); retención de 7 días; ejecución síncrona;
ningún lenguaje natural.

### `POST …/report-plans/{reportPlanId}/runs` sin cuerpo

Una petición con cuerpo (con `Content-Length` mayor que 0 o enviado por fragmentos con contenido) recibe `400
malformed_request` («This operation takes no request body.»). Un `Content-Length: 0` o un cuerpo por fragmentos vacío
es el caso normal. 401 y 403 conservan su precedencia; el 400 llega antes de consultar Semantic, resolver credenciales
o crear una ejecución.

### Rechazo nuevo (D-B10-4)

Un plan lógico con **más de 2 operaciones JOIN** se rechaza: REFUSED `plan_not_supported` (antes se ejecutaba; el
esquema de `nexora-logical-plan/2` no lo limita). Ningún otro rechazo nuevo: el resto de valores anunciados ya se
exigían.

### Vigencia (D-B10-2)

Si Semantic no puede confirmar la vigencia (antes o después de leer), el código es `plan_validity_unconfirmed`. La v3
(R4-8) lo llama `validity_unverifiable`; se mantiene provisionalmente el nombre implementado y la divergencia está
registrada (A5).

### Logs (D-B10-13)

Un fallo inesperado (500 `internal_error` o una ejecución FAILED) se registra con su clase, la de sus causas y su
traza, **nunca con sus mensajes**, que podían contener valores de una fuente, de un documento o de otro servicio.

## B10-O3 / DS-20 — Destino configurado y credenciales resueltas solo antes de leer (2026-11-02)

Autorización: «NEXORA AI — B10-O3 / DS-20, FASE 2 — PROCEDE con la implementación aislada» (2026-11-02), con D-O3-1 a D-O3-5. Sin cambios en los contratos compartidos.

| Momento | Qué se consulta | Respuesta si falla |
|---|---|---|
| Antes de Semantic (paso 2) | `SourceAvailability.configured`: el destino de (tenant, fuente) **sin secreto**, con la política de destinos | `409 data_source_not_configured` o `500 data_source_target_not_allowed`, **igual que antes**: sin Semantic y sin ejecución |
| Solo si el plan va a leerse (tras la vigencia y la traducción) | `CredentialProvider.resolve` y comparación exacta con el destino del paso 2 | La ejecución ya existe: `201` REFUSED con `data_source_not_configured` (la configuración desapareció), `data_source_target_not_allowed` (el destino dejó de estar permitido) o **`data_source_configuration_changed`** (el destino cambió: host, puerto, base, usuario, `sslmode`, tamaño del pool o certificado de la CA). Nada se lee |

- Un plan STALE antes de leer, uno rechazado por la validación o un Semantic que no responde **ya no resuelven las
  credenciales** y no abren ninguna conexión.
- Una contraseña distinta, por sí sola, no es un cambio de destino (no forma parte de `SourceTarget`).
- `data_source_configuration_changed` está mapeado a `409` para que todo código tenga respuesta HTTP, pero solo
  aparece como `refusal.code` de una ejecución iniciada.
- Con el proveedor actual (configuración leída al arrancar e inmutable) los tres rechazos posteriores al inicio no
  pueden producirse; existen para un proveedor que cambie en caliente. R1 (`/executions`) y la tenencia no cambian.

**Verificación en Windows (2026-11-02; anotada en B10-c.1, DS-27).** Respaldado por el log de Maven conservado (`mvn-verify-b10o3.log`, SHA-256 `2e0882bf5dc8adebe18e31d9d1718f6beb9f6f3587aa1fc65f8a61e5382d3f81`): **730 pruebas, 0 fallos, 0 errores, 0 omitidas**, `BUILD SUCCESS`, 01:49 min, PostgreSQL 17.6; respaldado por Surefire (119 archivos, 59 clases, Windows 11, Java 21.0.6) en `Claude outputs/a4-b10o3/windows-2026-11-02/`. `ReportRunSafeguardsIntegrationTest` 18/18 (la prueba de sesiones físicas pasó en 37,039 s, con control positivo), `ReportRunServiceTest` 53/53, `SourceTargetTest` 4/4, `ArchitectureTest` 13/13. B10-O3 y DS-20 **resueltas para el alcance comprobado**. Límites que se mantienen: el TOCTOU se reduce, no desaparece; los cambios de configuración entre el inicio y la lectura solo se prueban con dobles; **B10-O10 sigue abierta**.

## A4-B10-c.1 — Sin cambios en la API (2026-11-02)

B10-c.1 solo añade pruebas: los planes auténticos de Semantic (B10-a) se ejecutan por los endpoints ya descritos (`POST …/report-plans/{id}/runs`, `GET …/report-runs/{id}` y su descarga), con los mismos códigos y estados. Un plan STALE antes de leer responde `201` REFUSED `plan_stale` sin resolver credenciales; uno de otro tenant, `identity_mismatch`. Estado: implementado, pendiente de verificación en Windows. **Actualización (2026-11-02): verificado en Windows (757/757, con FIX1).** Si Semantic describe otra fuente que la pedida, la respuesta es `502 semantic_contract_violation` antes de iniciar la ejecución (sin cambios; ya documentado arriba). Evidencia: `Claude outputs/a4-b10c1/windows-2026-11-02-fix1/`.
