# Nexora Reports — API (R1)

Una sola operación de negocio: ejecutar un plan lógico **ya creado y autorizado en Nexora Semantic**.

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
