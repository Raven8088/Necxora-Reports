# Nexora Reports — Arquitectura (R1)

> Estado y pruebas: [PROJECT_STATUS.md](PROJECT_STATUS.md). Contrato HTTP: [API.md](API.md).
> Este documento describe **solo lo implementado en R1**. Lo futuro aparece marcado como tal.

## 1. Responsabilidad

Reports convierte un plan lógico **autorizado por Nexora Semantic** en **una** consulta PostgreSQL parametrizada,
la ejecuta con una identidad de **solo lectura** y devuelve el resultado tabular en JSON.

- No interpreta lenguaje natural (Intelligence) ni decide JOINs, rutas o cardinalidades (Semantic).
- No acepta SQL de nadie, ni un plan enviado por el cliente: el plan se **recupera por id** desde Semantic.
- No modifica el plan para hacerlo ejecutable: lo ejecuta tal cual o lo rechaza.

```
Cliente ──JWT(nexora-reports)──▶ Reports ──JWT de servicio del tenant (REPORTS_CLIENT)──▶ Semantic
                                   │                                                     (plan + vigencia)
                                   └──rol de solo lectura──▶ PostgreSQL empresarial
                                   └──rol de aplicación────▶ PostgreSQL de Reports (auditoría)
```

## 2. Contrato `nexora-logical-plan/1` (inspeccionado en Semantic)

Se leyó el código de Semantic (`LogicalPlan`, `LogicalPlanner`, `LogicalPlanController`, `LogicalPlanDtos`,
`PlanValidity`, `PlanValidityChecker`, `FilterValue`, `NaturalKey`, `TypeCategory`, `DefinitionValidator`,
`JwtPrincipalConverter`, `Role`) **sin modificarlo**, y se generaron documentos reales con su propio código (ver
`src/test/resources/semantic/README.md`).

**Sobre** (`GET …/logical-plans/{planId}`): `id`, `dataSourceId`, `formatVersion`, `fingerprint`, `createdBy`,
`createdAt` y `plan` (el documento tal como se persistió en `jsonb`). Semantic serializa con
`default-property-inclusion: non_null`: los campos nulos **no aparecen**.

**Documento** (`plan`):

| Parte | Contenido | Uso en Reports |
|---|---|---|
| `formatVersion`, `tenantId`, `dataSourceId`, `metadataSnapshotId`, `resolutionFingerprint`, `fingerprint` | Identidad | Versión exacta, tenant y fuente deben coincidir; huellas opacas |
| `request` | Solicitud normalizada | Se lee (estricto), no se usa para ejecutar |
| `base` (`objectId`, `naturalKey`) | Tabla o vista cuyas filas se agregan | `FROM` (alias `t0`) |
| `inputGrain` (`objectId`, `primaryKeyColumns`, `rowIdentity`) | Una fila por fila del objeto base | Coherencia |
| `outputGrain[]` (`dimensionId`, `key`, `objectId`, `columnObjectId`, `type` CATEGORICAL/TEMPORAL, `valueType`, `granularity` DAY/WEEK/MONTH/QUARTER/YEAR, `timeZone`) | Claves de agrupación | `SELECT` + `GROUP BY` |
| `joins[]` (`order`, `relationshipId`, `relationshipVersion`, `fromObjectId`, `toObjectId`, `navigation` FORWARD/REVERSE, `joinType` INNER/LEFT, `conditions[{fromColumnId,toColumnId}]`, `joinedSideUniqueness`, `multiplicationRisk`, `rowPreservation`, `optional`) | Árbol de JOIN con raíz en la base | `INNER/LEFT JOIN … ON a = b AND …` |
| `preAggregationFilters[]` (`order`, `definitionId`, `definitionKind`, `operator`, `valueType`, `objectId`, `columnObjectId`, `values[]`) | Filtros de filas (dimensiones) | `WHERE` |
| `aggregations[]` (`order`, `metricId`, `key`, `aggregation` COUNT/SUM/AVG, `columnObjectId` — nulo en COUNT de filas) | Métricas | `SELECT` |
| `postAggregationFilters[]` (`objectId` nulo, `definitionKind` METRIC) | Filtros de grupos | `HAVING` |
| `operations[]` (`SCAN`, `JOIN`…, `FILTER_ROWS`, `GROUP_AND_AGGREGATE`, `FILTER_GROUPS`) | Orden lógico | Debe ser **exactamente** el implícito |
| `safety` | `SINGLE_OBJECT` o `JOINS_TO_PRIMARY_KEY_ONLY` | Coherencia con `joins` |
| `definitions[]`, `relationships[]` (pares con claves naturales, `allowedJoinTypes`, evidencias y riesgos por sentido), `physicalObjects[]` (`type`, `naturalKey`, `dataType`, `nullable`, `fingerprint`) | Copias autocontenidas | Única fuente de identificadores físicos |

Otros hechos del contrato que condicionan la ejecución:

- **Claves naturales:** cada componente va entre comillas dobles con las comillas internas duplicadas
  (`"plant"."measurements"."value"`). *Discrepancia:* la tabla §3.1 de `ARCHITECTURE.md` de Semantic las muestra sin
  comillas (`sales.orders`); el código (`NaturalKey.java`) y los planes reales usan comillas. Reports sigue el código.
- **Valores de filtro canónicos** (`FilterValue.canonical()`): texto literal; números `stripTrailingZeros().toPlainString()`;
  `true`/`false`; fechas ISO (`2026-01-01`); `timestamp` como `LocalDateTime` ISO (`2026-01-12T02:00`);
  `timestamptz` normalizado a UTC (`2025-12-31T23:00Z`); UUID canónico.
- **Operadores** (D30 de Semantic): `EQ`, `NE`, `GT`, `GTE`, `LT`, `LTE`, `IN` (1–100 valores), `IS_NULL`,
  `IS_NOT_NULL`. Orden solo en numéricos y temporales; `IN` no en booleanos; tras agregar, solo comparaciones numéricas.
- **Zona horaria:** solo en dimensiones TEMPORAL sobre `timestamptz` (por defecto `UTC`); en `date` y `timestamp`
  Semantic la prohíbe.
- **JOIN (D34/D36):** solo hacia un lado único por PK exacta (`NONE_BY_PRIMARY_KEY`); navegación directa LEFT si está
  autorizado, si no INNER; inversa solo INNER; nunca INNER tras LEFT; sin filtros de filas en el lado opcional.
- **Vigencia** (`GET …/validity`): `{planId, status, issues[{code, blocking, kind, referenceId, message}]}`.
- **Permisos:** las tres llamadas a planes exigen `semantic:resolve` y la de fuente `semantic:catalog:read`; el rol
  `REPORTS_CLIENT` concede ambos. Recursos de otro tenant ⇒ 404; fuente archivada ⇒ 409 al crear, `STALE` en vigencia.
- **Errores de Semantic:** RFC 7807 con `code` (`not_found`, `tenant_access_denied`, …) y `correlationId`.

## 3. Estructura (hexagonal, package-by-feature)

```
com.nexora.reports
├── plan
│   ├── domain           LogicalPlan (modelo propio del contrato), StoredPlan, PlanValidity, NaturalKey,
│   │                    TypeCategory, FilterValue, PlanValidator, ValidatedPlan, PlanViolation
│   └── infrastructure   PlanDocumentReader (JSON estricto), SemanticPlanSource, ServiceTokenProvider,
│                        BoundedHttp, SemanticProperties
├── translation.domain   QueryTranslator, SqlQuery, SqlParameter, ResultColumn, SchemaExpectation
├── execution
│   ├── domain           Requester, ExecutionLimits, ValidityPolicy, ReportResult, QueryResult, ExecutionException
│   ├── application      ReportExecutionService + puertos (PlanSource, CredentialProvider, ReadOnlyExecutor, AuditPort)
│   └── infrastructure   PostgresReadOnlyExecutor, SchemaVerifier, ConnectionPools
├── credentials          domain: DataSourceCredentials · infrastructure: ConfiguredCredentialProvider, TargetPolicy
├── audit                domain: ExecutionAuditRecord · infrastructure: JdbcExecutionAuditRepository
├── security             RequesterJwtConverter, Permission (rol REPORT_RUNNER), RequesterArgumentResolver
├── api                  ExecutionController, DTOs, ApiExceptionHandler, Problems, CorrelationIdFilter
└── config               SecurityConfig, ReportsConfiguration (cableado), JsonConfiguration, ExecutionProperties
```

| Responsabilidad pedida | Implementación |
|---|---|
| `PlanSource` | Puerto `execution.application.port.PlanSource` · adaptador `SemanticPlanSource` |
| `PlanValidator` | `plan.domain.PlanValidator` (dominio puro) |
| `QueryTranslator` | `translation.domain.QueryTranslator` (puro, determinista) |
| `CredentialProvider` | Puerto · adaptador `ConfiguredCredentialProvider` |
| `ReadOnlyExecutor` | Puerto · adaptador `PostgresReadOnlyExecutor` |
| `ReportExecutionService` | `execution.application.ReportExecutionService` |
| `AuditPort` | Puerto · adaptador `JdbcExecutionAuditRepository` |

`ArchitectureTest` (ArchUnit) impide que los paquetes `domain` dependan de Spring, JDBC, Jackson, Jakarta o HTTP, que
la aplicación dependa de adaptadores y que el traductor dependa de JDBC o de la ejecución.

## 4. Flujo de una ejecución

1. **Autenticación** (resource server): firma, emisor obligatorio, expiración y audiencia `nexora-reports`.
   `RequesterJwtConverter` exige `sub`, `principal_type` ∈ {HUMAN, SERVICE, AI} y `tenant` UUID canónico.
2. **Autorización:** `@PreAuthorize("hasAuthority('reports:execute')")`. Un principal `AI` nunca recibe el permiso.
3. **Credenciales** de (tenant del token, fuente); si no existen ⇒ 409 sin llamar a Semantic.
4. **Fuente** en Semantic (token de servicio del tenant): mismo id, `postgresql`, `ACTIVE`.
5. **Plan** por id: id, fuente y `tenantId` del documento deben coincidir con la petición.
6. **Validación estricta** (`PlanValidator`, §5).
7. **Vigencia** (`ValidityPolicy`, §7).
8. **Traducción** (`QueryTranslator`, §6).
9. **Ejecución** de solo lectura con verificación del esquema vivo (§8).
10. **Vigencia de nuevo** tras la consulta: si dejó de ser ejecutable, se descartan las filas.
11. **Auditoría** (§9). Sin registro de auditoría no se entrega el resultado.

## 5. Validación estricta

**Lectura (`PlanDocumentReader`)**: propiedades desconocidas, valores de enum desconocidos (p. ej. una operación
`PIVOT`), claves duplicadas, coerciones de tipo (`"1"` por `1`) y contenido sobrante ⇒ `UNREADABLE_DOCUMENT`.
`formatVersion` distinto de `nexora-logical-plan/1` (sobre o documento) ⇒ `UNSUPPORTED_FORMAT_VERSION`.

**Reglas (`PlanValidator`)**, todas re-verificadas aunque Semantic ya las aplique:

- Partes obligatorias presentes; al menos una agregación; huella del documento igual a la del sobre (texto opaco).
- Límites: 8 JOIN, 20 métricas, 20 dimensiones, 50 filtros, 100 valores en `IN`.
- Objetos físicos únicos con claves naturales válidas (§6.1); toda referencia apunta a las copias del plan y cada
  columna pertenece a la relación indicada.
- JOIN: orden 1..n; árbol con raíz en la base; una relación como máximo una vez; snapshot de la relación con la
  misma versión; `FORWARD`/`REVERSE` coherente con origen y destino; condiciones = **exactamente** los pares de la
  relación orientados; tipo autorizado (`REVERSE` solo INNER); nunca INNER tras un lado opcional; `optional` y
  `rowPreservation` coherentes; lado unido `PRIMARY_KEY` y riesgo `NONE_BY_PRIMARY_KEY` tanto en el JOIN como en la
  evidencia de la relación para ese sentido; FK no `LOST`; columnas de cada condición de la misma categoría.
- `safety` coherente con la existencia de JOIN; métricas solo sobre columnas del objeto base (grano D33);
  SUM/AVG solo numéricas.
- Dimensiones: claves de salida únicas (dimensiones y métricas); `valueType` igual a la categoría del tipo de la
  columna; TEMPORAL solo `date`/`timestamp`/`timestamptz`, con granularidad permitida por la definición y zona IANA
  (sin desplazamientos fijos) solo en `timestamptz`.
- Filtros de filas: solo dimensiones, nunca en el lado opcional (`UNSAFE_FILTER`), operador compatible con el tipo,
  aridad correcta, valores parseables y sin duplicados. Filtros de grupos: solo métricas solicitadas, comparaciones
  numéricas.
- Tipos sin semántica SQL exacta en R1: `money`, `timetz` y cualquier tipo `OTHER` ⇒ `UNSUPPORTED_TYPE`.
- `operations` debe ser idéntica a la secuencia implícita (`SCAN`, `JOIN`×n, `FILTER_ROWS`, `GROUP_AND_AGGREGATE`,
  `FILTER_GROUPS`).

## 6. Traducción SQL

```sql
SELECT CAST(date_trunc('month', "t0"."measured_at" AT TIME ZONE CAST(? AS text)) AS date),
       "t0"."note", SUM("t0"."value"), COUNT(*)
FROM "plant"."measurements" AS "t0"
WHERE "t0"."note" IN (CAST(? AS text), CAST(? AS text))
  AND "t0"."measured_at" >= CAST(? AS timestamptz)
GROUP BY 1, 2
HAVING SUM("t0"."value") > CAST(? AS numeric)
ORDER BY 1 NULLS LAST, 2 NULLS LAST
LIMIT CAST(? AS bigint)
```

### 6.1 Identificadores

Las claves naturales se analizan por componentes con una gramática estricta (identificador entre comillas, comillas
internas duplicadas, separador `.`); no se «reparan». Se rechazan identificadores vacíos, con NUL o de más de 63 bytes
(PostgreSQL los truncaría y podría resolver otro objeto). Al generar SQL cada componente se vuelve a entrecomillar.
Los alias son fijos (`"t0"`, `"t1"`…) y las columnas del resultado se leen por posición: las claves de las
definiciones nunca entran en el SQL. Una guarda final rechaza cualquier `;`, comentario o literal fuera de
identificadores (salvo las unidades fijas de `date_trunc`).

### 6.2 Valores

Todos los valores son parámetros JDBC tipados con `CAST(? AS <tipo>)` explícito (`text`, `numeric`, `boolean`,
`date`, `timestamp`, `timestamptz`, `time`, `uuid`, `bigint`). Nada se concatena.

### 6.3 Semántica definida en R1

| Tema | Decisión |
|---|---|
| NULL en filtros | Lógica de tres valores de SQL: `EQ`, `NE`, `GT`…, `IN` nunca coinciden con NULL. `NE 'x'` **excluye** las filas NULL (usar `IS_NULL` para incluirlas) |
| NULL en agrupación | Un valor NULL forma su propio grupo y se ordena al final |
| `COUNT` | Sin columna: filas. Con columna: valores no nulos |
| `SUM`, `AVG` | Ignoran NULL. Si todos son NULL, el resultado es NULL |
| Sin filas | Sin claves de agrupación: una fila (`COUNT` = 0, `SUM`/`AVG` = NULL). Con claves: ninguna fila. Sin `COALESCE` |
| `HAVING` sobre NULL | Un grupo cuyo agregado es NULL no cumple ninguna comparación |
| Buckets temporales | Se devuelven como `date` del primer día del intervalo |
| `WEEK` | Semana ISO que empieza el lunes (`date_trunc('week')`) |
| `timestamptz` | Se convierte a la zona IANA de la dimensión (`AT TIME ZONE`) **antes** de truncar; los filtros comparan instantes UTC |
| `timestamp` sin zona y `date` | Se truncan tal como están almacenados (hora de pared), sin conversión |
| Sesión | `TimeZone=UTC`, para que nada dependa de la configuración del servidor |
| Texto | Comparación de PostgreSQL: `bpchar` frente a `text` ignora espacios finales; el orden de filas usa la intercalación de la base |
| Precisión | Decimales exactos; `AVG` devuelve la escala de PostgreSQL (p. ej. `75.2500000000000000`) |
| DISTINCT | Nunca. La seguridad de los JOIN viene de la PK (validada y re-verificada en vivo) |

Lo que el contrato no permite ejecutar exactamente se rechaza (422), nunca se aproxima.

## 7. Vigencia

| Estado de Semantic | R1 |
|---|---|
| `CURRENT` sin incidencias | Se ejecuta |
| `CURRENT_WITH_CHANGES` con **todas** las incidencias `blocking: false` | Se ejecuta con aviso `PLAN_CURRENT_WITH_CHANGES` en el resultado y en la auditoría |
| `STALE` | 409 `plan_stale` |
| Sin respuesta, error, otro `planId`, incidencias sin clasificar, `CURRENT` con incidencias, `CURRENT_WITH_CHANGES` sin incidencias o con alguna bloqueante | 503 `plan_validity_unconfirmed` |

La vigencia se consulta **antes y después** de la consulta. Si después es `STALE`, el resultado se descarta (409
`plan_changed_during_execution`).

## 8. Ejecución de solo lectura

Defensas independientes:

1. **Rol PostgreSQL** solo con `SELECT` (configuración de la base). Reports lo comprueba en cada ejecución: rechaza
   superusuarios y roles con `INSERT`, `UPDATE`, `DELETE` o `TRUNCATE` sobre cualquier relación leída
   (`data_source_role_not_read_only`).
2. **Sesión**: `default_transaction_read_only=on`, `statement_timeout`, `lock_timeout`,
   `idle_in_transaction_session_timeout` y `TimeZone=UTC` como opciones de conexión; modo JDBC `readOnlyMode=always`.
3. **Transacción** `REPEATABLE READ` de solo lectura, con `statement_timeout`, `lock_timeout` y zona reafirmados
   (`set_config(…, true)`); se comprueba `transaction_read_only = on`; siempre termina en `ROLLBACK`.
4. **Esquema vivo** (`SchemaVerifier`, consultas parametrizadas al catálogo en la misma transacción): existen las
   relaciones y columnas, cada columna es de tipo base con la misma categoría que el plan, y cada relación unida tiene
   como PK **exactamente** las columnas de la condición. Cualquier diferencia ⇒ 409 `physical_schema_mismatch`, sin
   corregir nada.
5. **Una sola sentencia** preparada (protocolo extendido de PostgreSQL), `fetchSize`, `setMaxRows`, `LIMIT maxRows+1`
   para detectar truncamiento, `setQueryTimeout` y presupuesto de duración comprobado al leer filas.
6. **Concurrencia**: semáforo global (`max-concurrent-executions`) y otro por fuente (`max-concurrent`, también tamaño
   del pool); sin hueco libre en `acquire-timeout` ⇒ 429.
7. **Destinos**: host y puerto solo de configuración, y solo si están en `allowed-hosts`/`allowed-ports`
   (coincidencia exacta). Las direcciones de enlace local (`169.254.*`, metadatos de nube) se rechazan siempre. Las
   conexiones se construyen con propiedades separadas de `PGSimpleDataSource`, nunca concatenando una URL JDBC, de modo
   que ningún valor puede añadir opciones del driver.

Errores: `08*`, `28*`, `3D000` y similares ⇒ `data_source_unavailable`; `57014`/`55P03` ⇒ `execution_timeout`;
`42P01`/`42703` ⇒ `physical_schema_mismatch`; `42501` ⇒ `data_source_unavailable`. El SQL y los valores nunca salen
del proceso.

## 9. Seguridad e identidad

- **Tenant** solo del JWT validado. Se comprueba que coincidan el tenant del solicitante, el de las credenciales
  (clave del proveedor), el de la fuente y el plan (Semantic responde 404 a otros tenants), el `tenantId` del documento
  y el claim `tenant` del token de servicio.
- **Cuentas de servicio**: una por tenant (`client_credentials`). Antes de usar su token, Reports verifica firma,
  emisor, expiración, audiencia `nexora-semantic`, `principal_type=SERVICE`, rol `REPORTS_CLIENT` y `tenant` igual al
  del solicitante. Los tokens se guardan en caché por tenant y nunca se reutilizan para otro.
- **IA**: sin permiso de ejecución (no hay política de delegación).
- **Huella**: opaca; solo se compara por igualdad (documento frente a sobre) y se registra en la auditoría.
- **Peticiones**: JSON estricto (propiedades desconocidas, coerciones y claves duplicadas rechazadas).
- **Respuestas de error**: códigos estables y textos fijos; `X-Request-Id` generado por el servidor.

## 10. Auditoría

Tabla `report_execution_audit` (migración V1, solo inserción y lectura; *triggers* impiden `UPDATE`, `DELETE` y
`TRUNCATE`): id de ejecución, tenant, fuente, plan, sujeto y tipo del solicitante, huella opaca, estado de vigencia,
resultado (`SUCCEEDED`/`REJECTED`/`FAILED`), código de error, filas, truncamiento, duración e instante. **No** guarda
valores de filtros, SQL, filas ni credenciales. Todas las lecturas se filtran por tenant; R1 no expone la auditoría
por API.

Reports audita al **usuario final**; Semantic, en cambio, solo ve la cuenta de servicio (riesgo 7 del contexto). La
correlación entre ambas auditorías es por `planId` e instante.

## 11. Consistencia y cambios de esquema

- Semantic evalúa la vigencia contra **su catálogo importado**, no contra la base viva. Reports reduce el riesgo con
  la verificación del esquema vivo (§8.4) dentro de la transacción de la consulta y con la doble consulta de vigencia.
- La ventana no se cierra del todo: un DDL puede ocurrir entre la verificación del catálogo y la lectura (las
  consultas al catálogo no bloquean), y un cambio en Semantic posterior a la segunda consulta no se detecta.
- Un plan inmutable **no garantiza reproducir resultados históricos**: los datos empresariales cambian y no están
  versionados. Dos ejecuciones del mismo plan pueden devolver resultados distintos.

## 12. Decisiones de R1

| Id | Decisión |
|---|---|
| R1-D1 | Modelo propio del contrato en Reports (sin depender del código de Semantic); lectura estricta que falla ante cualquier cosa desconocida |
| R1-D2 | Validación completa en Reports aunque Semantic ya la haga (defensa en profundidad) |
| R1-D3 | Endpoint `POST /api/v1/data-sources/{dataSourceId}/plans/{planId}/executions`; respuesta síncrona; sin persistir resultados |
| R1-D4 | Rol propio `REPORT_RUNNER` → `reports:execute`; los roles de Semantic no conceden nada en Reports |
| R1-D5 | Audiencia propia `nexora-reports`: un token emitido para Semantic no sirve en Reports |
| R1-D6 | Una cuenta de servicio `REPORTS_CLIENT` por tenant; verificación del claim `tenant` del token de servicio |
| R1-D7 | `CURRENT_WITH_CHANGES` solo si todas las incidencias son no bloqueantes; vigencia antes y después |
| R1-D8 | NULL con lógica de tres valores de SQL (incluido `NE`) y sin `COALESCE` |
| R1-D9 | Buckets temporales devueltos como fecha de inicio; `WEEK` ISO (lunes); conversión de zona solo en `timestamptz` |
| R1-D10 | Decimales como texto exacto en JSON; `COUNT` como número |
| R1-D11 | `money`, `timetz` y tipos `OTHER` no soportados; zonas con desplazamiento fijo rechazadas |
| R1-D12 | Verificación del esquema vivo, incluida la PK de cada lado unido, antes de consultar |
| R1-D13 | Credenciales por configuración (variables de entorno) tras el puerto `CredentialProvider`, sustituible por un gestor de secretos |
| R1-D14 | Auditoría en base propia, solo inserción; sin auditoría no hay resultado |
| R1-D15 | Orden determinista por claves de agrupación para que el truncamiento sea estable |

## 13. Fuera de R1

No implementado: exportación a Excel o PDF, IA o lenguaje natural, predicciones, acciones correctivas, otros motores,
conexión con Wellnode, gestor de secretos, propagación de la identidad del usuario a Semantic, API de consulta de la
auditoría, paginación o ejecución asíncrona, y cualquier cambio en Discovery o Semantic.
