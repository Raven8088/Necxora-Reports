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

## 14. A4 — Dependencia Apache POI (bloque A4-B1, 1 nov 2026)

Primer bloque de A4 (propuesta v3, decisión D-A4-1). Solo añade la biblioteca y sus salvaguardas; el adaptador Excel
llega en un bloque posterior. El flujo R1 (`nexora-logical-plan/1`) no cambia.

**Cambio.** `pom.xml`: propiedad `poi.version = 5.4.0` y dependencia `org.apache.poi:poi-ooxml:${poi.version}` (ámbito
de compilación). La escritura será siempre con `SXSSFWorkbook` (ventana de filas en memoria y volcado a temporales).

**Salvaguardas.**

- `ArchitectureTest.onlyTheExcelAdapterUsesApachePoi`: ninguna clase fuera de `..render..` puede depender de
  `org.apache.poi..` ni `org.apache.xmlbeans..` (R4-24: el motor no conoce el formato).
- `render/xlsx/PoiDependencyTest` (pruebas): la versión resuelta es exactamente 5.4.0 (detecta una sustitución por la
  gestión de dependencias de Spring Boot) y SXSSF vuelca filas a temporales, produce un `.xlsx` que XSSF vuelve a leer
  y libera sus temporales (`dispose()`). *(A4-B7: `dispose()` está obsoleto en POI 5.4.0 —era la causa de B1-O1—; el adaptador usa `close()`, que también borra los temporales. Ver §20.)*

**Revisión de vulnerabilidades (1 nov 2026, fuentes públicas).** Versiones transitivas según la propuesta v3 §4 bis
(resueltas entonces desde la caché local con Spring Boot 4.1.1). **La resolución real debe confirmarse en Windows con
`mvn dependency:tree`**: la gestión de dependencias de Spring Boot puede fijar versiones distintas de las que declara POI.

| Artefacto | Versión (v3) | Vulnerabilidades conocidas | ¿Afecta? | Fuente |
|---|---|---|---|---|
| `poi`, `poi-ooxml`, `poi-ooxml-lite` | 5.4.0 | CVE-2025-31672 (entradas zip duplicadas en OOXML): afecta a versiones **anteriores** a 5.4.0 | No | db.gcve.eu (fkie_cve-2025-31672); poi.apache.org/changes.html (sin CVE listados para 5.4.0 y posteriores) |
| `xmlbeans` | 5.3.0 | XXE, versiones < 3.0.0 | No | security.snyk.io (xmlbeans) |
| `commons-compress` | 1.27.1 | CVE-2024-25710 y CVE-2024-26308: 1.3 a 1.25.0, corregido en 1.26.0 | No | advisories.gitlab.com (CVE-2024-25710) |
| `commons-io` | 2.18.0 | CVE-2024-47554: 2.0 a < 2.14.0 | No | herodevs.com (CVE-2024-47554) |
| `commons-lang3` | 3.20.0 | CVE-2025-48924: 3.0 a < 3.18.0 | No | advisories.gitlab.com (CVE-2025-48924) |
| `commons-codec` | 1.21.0 | Exposición de información, < 1.14 | No | security.snyk.io (commons-codec) |
| `commons-collections4` | 4.4 | Deserialización, [4.0, 4.1) | No | security.snyk.io (commons-collections4) |
| `log4j-api` | 2.25.5 | CVE-2025-68161 afecta a **`log4j-core`** 2.0-beta9 a 2.25.2, no a `log4j-api`; Reports no incluye `log4j-core` (usa Logback) | No | app.opencve.io (CVE-2025-68161) |
| `commons-math3` 3.6.1, `SparseBitSet` 1.3, `curvesapi` 1.08 | — | **No verificado**: no se pudo consultar una base de vulnerabilidades para ellos en esta revisión | Pendiente | — |

Resultado: ninguna dependencia revisada está afectada por una vulnerabilidad publicada. Quedan sin verificar
`commons-math3`, `SparseBitSet` y `curvesapi`, y la confirmación de las versiones realmente resueltas.

**Confirmación (1 nov 2026, Windows).** `Claude outputs/a4-b1/dependency-tree.txt` confirma que las versiones resueltas son exactamente las de la tabla
anterior y que no se incluye `log4j-core` (`log4j-api` se redirige a SLF4J con `log4j-to-slf4j` 2.25.5). Siguen sin
verificar `commons-math3`, `SparseBitSet` y `curvesapi`.

**Versiones posteriores de POI.** Existen 5.4.1 (abr 2025), 5.5.0 (nov 2025), 5.5.1 (nov 2025) y 6.0.0 (22 oct 2026),
según poi.apache.org/changes.html. Se mantiene 5.4.0 porque es la versión aprobada (D-A4-1); cambiarla requiere una
decisión expresa.

## 15. A4 — Lector y validador de planes `/2` (bloque A4-B2, 1 nov 2026)

Validación independiente en Reports de los documentos de un plan de reporte (propuesta v3 §2), antes de traducir o de
abrir una conexión. Reports no confía en Semantic. Todo vive en `com.nexora.reports.plan2`, sin tocar el flujo R1.

**Capas.**

- `plan2/infrastructure/StrictJsonTreeReader`: Jackson 3 hacia un árbol simple (mapas, listas, texto, booleanos,
  `Long`/`BigInteger`, null). Rechaza claves duplicadas, contenido sobrante, números no enteros y raíces que no sean
  objetos: dos lectores nunca pueden ver valores distintos detrás de la misma huella.
- `plan2/infrastructure/ClasspathContractSchemas`: carga los tres esquemas de `src/main/resources/contracts`.
- `plan2/domain` (Java puro, sin Spring, JDBC ni Jackson):
  - `CanonicalJson`: los mismos bytes que Semantic y las herramientas del contrato (claves por punto de código,
    escapes de Python); `fingerprintOf` excluye el campo `fingerprint`; la especificación se identifica por el SHA-256
    del documento completo.
  - `Sha256` (puerto): el SHA-256 lo aporta la infraestructura (`plan2/infrastructure/JdkSha256`, `MessageDigest` del
    JDK), de modo que el dominio no depende de ningún proveedor de seguridad (A4-B2-FIX1; `ArchitectureTest` prohíbe
    `..security..` en el dominio).
  - `ContractSchema`: validador del subconjunto de JSON Schema 2020-12 que usan los contratos; **rechaza cargar** un
    esquema con cualquier otra palabra clave (falla cerrado ante un contrato futuro).
  - `ContractRules`: SPEC-R1…R6, PLAN-R1…R5 y RPLAN-R1, traducción fiel de `nexora-contracts/tools/validar_contratos.py`.
  - `BasicAProfile`: lo que Reports ejecuta en `basic-a` además del contrato (abajo).
  - `PlanV2Validator`: orquesta las tres etapas y devuelve `ValidatedReportPlan` o lanza `PlanV2Exception`.
  - `PlanV2Parser` y los modelos inmutables `ReportPlanV1`, `ReportSpecView` y `LogicalPlanV2` (operaciones como tipo
    cerrado: `Scan`, `Join`, `Filter`, `Aggregate`, `Project`, `Order`, `Limit`).

**Etapas** (la primera que falla lanza la excepción con todas sus infracciones):

1. Plan de reporte: esquema, RPLAN-R1, perfil `basic-a`, tenant y fuente de la petición, presupuesto frente a los
   techos de Reports (`ReportsCeilings`: 3 planes, 10 000 filas por plan, 30 000 en total, 64 columnas, 30 s y 60 s),
   huella.
2. Especificación: esquema completo, SPEC-R1…R6, perfil `basic-a` y estado `READY`, SHA-256 igual al `specFingerprint`
   del plan de reporte, mismos datasets.
3. Planes lógicos: exactamente los del plan de reporte; esquema, PLAN-R1…R5, perfil; tenant, fuente, `datasetId` y
   `specFingerprint`; huella recalculada igual a la declarada y al `planFingerprint`; `LIMIT` dentro del presupuesto;
   columnas dentro del techo; una sola instantánea de metadatos.

**Perfil `basic-a` en Reports.** Sin `parameters`; SCAN solo de TABLE o VIEW; columnas con categoría distinta de TIME
y OTHER; cada columna referenciada pertenece al objeto de su alias; JOIN solo con una relación del plan, APPROVED, con
unicidad `PRIMARY_KEY` declarada en la relación y en la unión, tipo permitido, condiciones solo entre columnas que
coinciden exactamente con las parejas de la relación según la dirección, y el lado unido es **exactamente la PK del
objeto unido** (así se admite el `REVERSE` 1:1 que emite Semantic y nunca una unión que multiplique filas); FILTER con
el número de valores del operador, un tipo de literal por categoría, sin rangos sobre TEXT, UUID o BOOLEAN y literales
en la forma canónica de Semantic; AGGREGATE solo COUNT; sin GROUP_FILTER; en ORDER, `COLLATE "C"` en todas las claves
de texto y en ninguna otra; tipo y rol de cada salida coherentes con su columna (X-7).

**Errores.** `PlanV2Violation(rule, kind, path)`: la regla (`SCHEMA`, `SPEC-R*`, `PLAN-R*`, `RPLAN-R1`, `BASIC-A`,
`IDENTITY`, `FINGERPRINT`, `BUDGET`, `UNREADABLE`), una categoría que el servicio de ejecución (B9) convertirá en motivo
de rechazo, y la ubicación. **Nunca copia valores** de los documentos.

**Pendiente para bloques siguientes.** La vista de presentación de la especificación (B6), la traducción a SQL (B3),
la conexión de `ReportsCeilings` con la configuración y `SemanticReportPlanSource` (B9).

## 16. A4 — Traducción de planes `/2` a SQL (bloque A4-B3, 1 nov 2026)

Traducción pura y determinista de un dataset validado por B2 (`ValidatedReportPlan.Dataset`) a una única consulta
PostgreSQL parametrizada. Vive en `com.nexora.reports.translation2.domain`; no ejecuta nada ni lee catálogos, y no
conoce ninguna tabla, columna ni concepto de negocio: todo sale del plan (PR-1, PR-2). R1 (`translation`) no cambia.

**Componentes.**

- `PlanV2Translator`: `translate(Dataset)` y `translate(LogicalPlanV2, maxRows)`.
- `SqlQueryV2(datasetId, sql, parameters, outputs, maxRows, schema)`: `outputs` en el orden de salida del plan, con
  posición, tipo, rol y si está oculta (`row_key`); `toString` no muestra valores.
- `SchemaRequirementsV2`: relaciones, columnas con su categoría y claves primarias que la consulta da por supuestas
  (PK de cada lado unido y de la base cuando el desempate es `UNIQUE_KEY`), para la comprobación de esquema de A4-B4 (corregido en A4-B4; antes decía «B5»).
- `SqlTextGuard`: última barrera sobre el texto final; fuera de identificadores entrecomillados rechaza `;`, `'`, `$`,
  `\`, `--`, `/*`, NUL e identificadores sin cerrar.
- Reutiliza de R1, sin modificarlos, `translation.domain.SqlParameter` (tipos enlazados igual que en el ejecutor de R1)
  y `plan.domain.NaturalKey` (comillas y límite de 63 bytes).

**Forma de la consulta.**

```sql
SELECT * FROM (
  SELECT <salidas en orden, AS "c1".."cN">
  FROM "esquema"."relación" AS "t0"
  LEFT|INNER JOIN "esquema"."relación" AS "t1" ON "t0"."a" = "t1"."b" [AND ...]   -- en el orden del plan
 WHERE <predicados unidos con AND>
 GROUP BY <columnas de grupo>                                                    -- AGGREGATE con grupos
) AS "q"
ORDER BY "q"."cK" [COLLATE "C"] ASC|DESC NULLS LAST|FIRST, ...
LIMIT CAST(? AS bigint)                                                          -- maxRows + 1
```

El orden se aplica fuera porque PostgreSQL no admite `COLLATE` sobre un nombre de salida; el desempate del plan ya
está entre las claves, así que el orden es total. FILTER: `EQ`, `NE`, `GT`, `GTE`, `LT`, `LTE`, `IN`, `IS_NULL`,
`IS_NOT_NULL`, con NULL en lógica de tres valores como en R1. AGGREGATE: `COUNT(*)`.

**Seguridad.** Los identificadores solo vienen de las claves naturales del plan, entrecomillados con las comillas
internas duplicadas; vacíos, con NUL o de más de 63 bytes se rechazan (`PlanV2Exception`, regla `IDENTIFIER`), igual
que una columna cuya clave natural nombra otra relación. Alias, ids de salida, etiquetas y demás textos del plan nunca
llegan al SQL. Todo valor es un parámetro `CAST(? AS tipo)` tipado por la categoría de su columna: NUMERIC →
`BigDecimal`, TEXT → `String`, UUID → `UUID`, BOOLEAN → `Boolean`, DATE → `LocalDate`, TIMESTAMP → `LocalDateTime`,
TIMESTAMP_TZ → `OffsetDateTime`. `ArchitectureTest.theV2TranslatorDoesNotExecuteAnything` prohíbe a `translation2`
depender de `java.sql`, `javax.sql` y `..execution..` (el patrón `..translation..` de R1 no abarca `translation2`).

**Pruebas.** `PlanV2TranslatorTest` (SQL exacto de los tres datasets de PA-A y de dos planes de otra forma sobre otras
tablas del banco; plan gobernado; esquema inventado; cada operador y cada categoría; LIMIT; determinismo;
identificadores; inyección; guardia). `PlanV2TranslationIntegrationTest`: contenedor propio (`postgres:17.6` o
`nexora.test.postgres-image`) con la copia del banco sintético (`src/test/resources/nexora-testbench/reportes`,
verificada contra `BENCH.sha256`), consultas ejecutadas con el rol de solo lectura del banco: PA-A igual a
`esperado/pa-a.json` y los otros planes iguales a SQL de referencia escrito a mano.

**Pendiente para bloques siguientes.** Ejecución con el ejecutor de solo lectura y comprobación de
`SchemaRequirementsV2`: A4-B4 (§17; corregido en A4-B4, antes decía «B4/B5 según la propuesta»), sin cambios en este bloque.

## 17. A4 — Ejecución de planes `/2` en una instantánea (bloque A4-B4, 1 nov 2026)

> «A4-B4» es el bloque 4 de la etapa A4. No es la etapa futura «B4» de seguridad por filas (D-7), que sigue fuera de
> alcance.

Ejecuta todos los datasets de un plan de reporte validado (B2) y traducido (B3) en **una conexión y una transacción
REPEATABLE READ de solo lectura**, que siempre termina con rollback. Termina con filas tipadas en memoria y la
conexión ya devuelta: no sabe nada de Excel (regla `ArchitectureTest.theExecutionLayerDoesNotKnowThePresentation`).

**Piezas.**

- Puerto `execution/application/port/PlanV2Executor.executeAll(credenciales, ExecutionRequestV2, RunBudget,
  TenantScopeVerifier)`. Lo implementa `PostgresReadOnlyExecutor`, que es también el ejecutor de R1. Ambos caminos
  comparten pools por (tenant, fuente) y huecos de concurrencia global y por fuente, es decir, un único presupuesto de
  concurrencia (P-1+ f). El método `execute` de R1 y su puerto `ReadOnlyExecutor` no cambian.
- `execution/infrastructure/PlanV2SnapshotReader`: la lectura en sí.
- `execution/domain` (puro):
  - `ExecutionRequestV2`: tenant, fuente y consultas en el orden del plan. `of(ValidatedReportPlan, consultas)`
    comprueba que cada consulta corresponde a su dataset y a su `maxRows`, que el último parámetro es `maxRows + 1`, que
    las posiciones de salida son consecutivas y que los `valueType` son del contrato.
  - `RunBudget.of(presupuesto del plan, ExecutionLimits, bytes)`: el mínimo de lo que pide el plan y lo que permite el
    servidor.
  - `OutputValueType`, `ResultSizeEstimate`, `DatasetRows` y `SnapshotRead` (instantánea y filas por dataset).
- Puertos `TenantScopeVerifier` y `CatalogReader`: el punto **obligatorio** donde A4-B5 comprobará la tenencia. No hay
  implementación permisiva en producción; las pruebas usan `ControlledTenantScope`, que está en las fuentes de prueba.

**Pasos** (todos fallan cerrado):

1. Antes de conectar: las credenciales deben ser del tenant y la fuente de la petición (`IllegalArgumentException`);
   comprobación de tenencia no nula. Se toma un hueco global y otro por fuente para toda la ejecución.
2. Transacción REPEATABLE READ de solo lectura; `statement_timeout`, `lock_timeout` y `TimeZone=UTC` con `set_config`
   local. Comprobación de sesión de R1 (solo lectura, no superusuario) y, además, aislamiento efectivo
   `repeatable read`.
3. `TenantScopeVerifier.verify`, con un `CatalogReader` sobre la misma conexión y transacción: sus consultas solo
   llevan parámetros y pasan por `SqlTextGuard`.
4. `SchemaVerifier.verify(conexión, SchemaRequirementsV2)` para cada consulta: relaciones y su tipo, sin privilegios de
   escritura, categoría de cada columna, PK exactas y, solo en `/2`, `has_column_privilege(…, 'SELECT')` (D-B4-4).
   Cualquier diferencia da `PHYSICAL_SCHEMA_MISMATCH` **antes de leer datos**.
5. `pg_current_snapshot()::text`, registrado en `SnapshotRead`.
6. Las consultas en orden, cada una en una sentencia preparada:
   - `statement_timeout` = mín(presupuesto, tiempo que queda de la ejecución), de modo que el plazo total también lo
     aplica el servidor;
   - `setMaxRows(maxRows + 1)` y tamaño de lote, además del `LIMIT` del SQL;
   - forma del resultado igual a `"c1"…"cN"`;
   - valores tipados (`Long`, `BigDecimal`, `String`, `Boolean`, `LocalDate`, `LocalDateTime`, `OffsetDateTime` en
     UTC), nunca a través de texto;
   - la fila de más se descarta y marca `truncated`;
   - total de filas y memoria estimada de todos los datasets controlados durante la lectura (`RESULT_TOO_LARGE`);
   - NaN, ±Infinity, fechas `infinity` y decimales en INTEGER se rechazan (`VALUE_NOT_REPRESENTABLE`).
7. Rollback siempre. La conexión se devuelve antes de que el resultado salga de la clase.

**Errores.** Los de R1 (`PHYSICAL_SCHEMA_MISMATCH`, `DATA_SOURCE_ROLE_NOT_READ_ONLY`, `EXECUTION_TIMEOUT`,
`TOO_MANY_EXECUTIONS`, `DATA_SOURCE_UNAVAILABLE`, `EXECUTION_FAILED`) y dos nuevos: `RESULT_TOO_LARGE` y
`VALUE_NOT_REPRESENTABLE`, que en la API son 409 `result_too_large` y 409 `value_not_representable` (`docs/API.md`;
R1 no los emite). Los mensajes nunca contienen SQL, valores ni datos de conexión.

**Fuera de A4-B4.** Identidad del servidor y de la base, alcance de esquemas del rol, privilegios efectivos fuera de
lo registrado, vistas y funciones privilegiadas y registro del modelo de tenencia: A4-B5. Estado PARTIAL, vigencia
antes y después, API y auditoría: B9. Ensamblado de `ReportResult`: bloque de resultado. A4-B4 no aísla empresas
dentro de un tenant (v3 §4 bis.8).

**Pruebas.**

- `PlanV2ExecutorIntegrationTest`, con PostgreSQL real (`support/BenchDatabase`: contenedor propio, banco cargado
  desde su copia verificada, esquema auxiliar `b4_aux` para los casos destructivos, D-B4-6):
  - PA-A tipado en una instantánea;
  - R4-5 (fila confirmada entre dos consultas);
  - truncado con 12 000 filas;
  - límites de filas totales y de memoria;
  - `lock_timeout`, `statement_timeout` y plazo total;
  - huecos de concurrencia;
  - R4-9 (cinco cambios de esquema, sin leer datos);
  - rol con escritura;
  - los 7 tipos;
  - valores no representables;
  - comprobación de tenencia que rechaza, que falta o con credenciales de otro tenant;
  - conexiones devueltas sin transacción abierta.
- `PlanV2ExecutionModelTest`: el modelo puro.

## 18. A4 — Verificación de tenencia (bloque A4-B5, 1 nov 2026)

Implementa la comprobación de tenencia obligatoria que A4-B4 dejó como punto de enganche (`TenantScopeVerifier`).
**Solo admite `DEDICATED_DATABASE` y `DEDICATED_SCHEMAS`.** `SHARED_ROWS` se rechaza siempre: no se puede registrar,
la base no lo almacena y la sonda lo rechaza aunque un registro diga estar activo. Lo que se demuestra es que se lee
exactamente la base y los esquemas registrados, en el servidor fijado, con un rol que no alcanza nada más. **No es
aislamiento por filas** (etapa futura «B4», D-7) ni separa empresas dentro de un tenant.

**Registro (`tenancy`, P-1b).**

- `tenancy/domain`: `TenancyModel`, `DatabaseIdentity`, `TenancyRegistration` (versión inmutable con su huella
  SHA-256), `TenancyDecision` (aprobación o revocación), `TenancyStatus` (derivado: `PENDING`, `ACTIVE`,
  `SUPERSEDED`, `REVOKED`) y `TenancyRules`.
- `tenancy/application/TenancyRegistrationService`, con los puertos `TenancyRegistry` y `TenancyCapture`:
  - **alta**: una persona declara el modelo y los esquemas; la identidad y los esquemas se **capturan de la propia
    base** con las credenciales de solo lectura, que exigen `verify-full` y una sesión cifrada; la captura debe
    coincidir exactamente con lo declarado; la versión queda pendiente;
  - **aprobación**: otra persona `HUMAN` del mismo tenant aprueba una versión pendiente más nueva que la vigente;
  - **revocación**: cualquier persona del tenant.
  Una migración o restauración legítima hace fallar las comprobaciones hasta que se registra y aprueba una versión
  nueva; las anteriores se conservan y el `dataSourceId` no cambia.
- `tenancy/infrastructure/JdbcTenancyRegistry` sobre la migración `V2__create_tenancy_registry.sql`: dos tablas que
  solo admiten añadir filas (disparadores contra UPDATE, DELETE y TRUNCATE, también para el propietario); restricciones
  que rechazan `SHARED_ROWS`, versiones repetidas y decisiones repetidas; un disparador que impide que apruebe quien
  registró; el rol de la aplicación solo tiene SELECT e INSERT.

**Comprobación en cada ejecución (`TenantScopeProbe.forExecution(registro vigente, petición)`).** Se ejecuta dentro
de la transacción de A4-B4, después de la sesión y antes del esquema y de cualquier dato. Cualquier duda da
`TENANT_SCOPE_UNVERIFIED` (409 `tenant_scope_unverified`):

1. Registro **aprobado y vigente** de un modelo dedicado para el (tenant, fuente) de la petición y de las
   credenciales. Host, puerto, base y usuario de las credenciales iguales a los registrados.
2. `sslmode=verify-full`: el controlador ya validó el certificado y el nombre de host antes de abrir la sesión. Y
   `pg_stat_ssl` confirma que esta sesión está cifrada; esa segunda comprobación **no** valida el certificado.
3. Nombre y OID de la base y `system_identifier` del clúster iguales a los registrados. Si `system_identifier` no se
   puede leer, se rechaza. Identifica el clúster, no la base; con el nombre y el OID detecta otro servidor, otra base
   o una base recreada, pero **no es una garantía absoluta** frente a una copia física del clúster.
4. El rol: el registrado, no superusuario y sin pertenencia, por ninguna cadena y sea cual sea INHERIT, a
   superusuarios ni a `pg_read_all_data`, `pg_write_all_data`, `pg_read_server_files`, `pg_write_server_files` o
   `pg_execute_server_program`.
5. Los esquemas no del sistema con USAGE efectivo (`has_schema_privilege`: directo, heredado y PUBLIC) son
   **exactamente** los registrados, `public` incluido.
6. Cada relación del plan está en esos esquemas. Una tabla, sin RLS y con todas sus herederas y particiones también
   dentro. Una vista o vista materializada, solo si todo lo que alcanza se demuestra dentro del alcance (abajo).
   Cualquier otro tipo de relación se rechaza.

**Vistas (precisión 4).** `pg_depend` solo registra referencias estáticas y **no registra los objetos internos
(«pinned»)**. Por eso se usan las dos fuentes: las dependencias de la regla de reescritura y el árbol de consulta
guardado (`pg_rewrite.ev_action`), que sí nombra funciones, operadores y relaciones internos. Se acepta solo:

- relaciones de los esquemas registrados (las vistas anidadas se recorren);
- tipos de `pg_catalog`;
- colaciones y esquemas de `pg_catalog` o registrados;
- funciones, operadores y conversiones cuya función sea interna (`internal`) de `pg_catalog`, no
  `SECURITY DEFINER` y no de las que ejecutan SQL de texto, leen objetos grandes o cambian ajustes
  (`query_to_xml`, `*_to_xml*`, `ts_stat`, `ts_rewrite`, `set_config`, `lo_*`, `loread`, `lowrite`).

Una función de usuario se rechaza aunque sea `SECURITY INVOKER`, porque su cuerpo no se puede demostrar. Lo que no se
pueda resolver o leer se rechaza. El análisis del árbol cubre los campos de PostgreSQL ≤ 17; una versión mayor nueva
debe revisarse (B5-O2).

**TLS.** `DataSourceCredentials.sslRootCert`, opcional, lo aplica `ConnectionPools` cuando existe. Sin él, R1 se
comporta igual que antes. Todavía no se puede configurar por propiedades: queda para B9 (B5-O3).

**Arquitectura.** `ArchitectureTest.onlyTheTenancyAdapterImplementsTheTenantScopeCheck`: solo
`..tenancy.infrastructure..` implementa `TenantScopeVerifier` en el código de producción. No existe ningún verificador
permisivo; el de las pruebas de A4-B4 vive en las fuentes de prueba. La integración con Spring y los endpoints de
alta y aprobación son de B9.

**Pruebas.**

- `TenantScopeIntegrationTest`: PostgreSQL con **TLS real**, con un certificado autofirmado para el host de Docker
  generado con `keytool` en tiempo de prueba y un segundo certificado ajeno para demostrar que `verify-full` rechaza
  el servidor. Bases y esquemas de dos tenants. Pasa por el ejecutor de A4-B4 y cada rechazo ocurre antes de
  cualquier consulta de datos:
  - **aceptado**: base dedicada, esquemas dedicados de A y B, y vista propia;
  - **rechazado por el registro**: sin registro, pendiente, revocado, `SHARED_ROWS`, registro de otro tenant;
  - **rechazado por las credenciales**: con el rol de B, de otra base del mismo servidor, de otro rol;
  - **rechazado por la identidad**: otro clúster; base recreada, con su ciclo de revalidación (la aprobación por quien
    registró falla y la de otra persona funciona);
  - **rechazado por TLS**: `require`, `disable`, y una autoridad de certificación ajena;
  - **rechazado por privilegios**: USAGE en B directo, vía PUBLIC o heredado, `pg_read_all_data`, `public` no
    registrado;
  - **rechazado por lo que alcanza una relación**: plan de A sobre una tabla de B; vistas que leen B, con
    `SECURITY DEFINER`, con una función de usuario o con `query_to_xml`; herencia hacia B; RLS;
  - registro que declara esquemas de más o se hace sin `verify-full`.
- `JdbcTenancyRegistryIntegrationTest`: Flyway V1 y V2 en una base propia; registro que solo admite añadir filas
  (también para el propietario); restricciones; lecturas limitadas por tenant.
- `TenancyRulesTest` y `TenancyRegistrationServiceTest`: reglas y servicio.

## 19. A4 — Ensamblado genérico de resultados (bloque A4-B6, 1 nov 2026)

`com.nexora.reports.result.domain` (Java puro) convierte una ejecución en un **`ReportResult`**: un modelo tipado,
inmutable e independiente del formato que dice *qué* presentar, nunca *cómo*. No conoce ningún reporte, tabla ni
concepto de negocio: todo sale de la especificación y de los planes `/2`, así que un reporte nuevo no requiere código.
Regla `ArchitectureTest.theResultIsIndependentOfPresentationApiAndPersistence`: no depende de presentación (POI,
Excel), API, infraestructura, configuración, Spring, Jackson ni JDBC.

**Entrada (`AssemblyInput`):**

- el `ValidatedReportPlan` (B2);
- la `SpecPresentation` de **la misma** especificación;
- las `SqlQueryV2` (B3);
- el `SnapshotRead` (B4);
- lo que solo conoce la ejecución: id, horas, versión de Reports y, opcionalmente, el nombre de la fuente y un resumen
  de las columnas excluidas.

**`SpecPresentation.from(árbol, plan, sha256)`** reconstruye título, solicitud, zona, idioma, datasets (títulos,
`select`, filtros), secciones y `provenance`, y **solo si** el SHA-256 canónico del árbol es exactamente el
`specFingerprint` del plan de reporte y la huella que registró B2. Si no: `SPEC_MISMATCH`. Así ningún reporte se
presenta con otra especificación que la validada.

**Modelo:**

- `RunMetadata`: identidades, huellas, `metadataSnapshotId`, consistencia (REPEATABLE_READ, solo lectura, una
  transacción, `pg_current_snapshot()`) y horas.
- `ReportHeader`: título, solicitud, zona (`ZoneId`), idioma, tenant y fuente (`Fact`), generación, versión y filtros
  (`FilterDescription`: dataset, etiqueta, objeto, operador, valores tipados, origen).
- `DatasetResult`: columnas `ResultColumn2` en el orden del plan, con:
  - encabezado = `label` del plan;
  - `valueType`, rol y origen;
  - `hidden`: se conservan para ordenar y no se muestran;
  - objeto físico de origen;
  - definición gobernada (clave y revisión).

  Y las **filas de B4 sin copiar**: solo se rehace la fila cuyo texto se recorta.
- `ReportSection` con sus `ReportItem` en el orden de la especificación: `Table` y `SummaryTable` (con su `Total`
  opcional).
- `Methodology`: relaciones, relaciones aprobadas, términos, definiciones, reglas de orden y ejecución por dataset. Lo
  desconocido es `Fact.Unavailable(código)` y nunca se rellena.
- `ResultWarning`: `dataset_truncated` y `cell_text_truncated`.
- `ResultStatus`: COMPLETED o PARTIAL.

**Reglas (fallan cerrado):**

- **Correspondencia:** datasets, consultas, filas y columnas del mismo plan, en orden; cada valor de su tipo exacto
  (`Long`, `BigDecimal`, `String`, `Boolean`, `LocalDate`, `LocalDateTime`, `OffsetDateTime` UTC). Un fallo aquí es
  un error de programación.
- **`basic-a` y D-A4-7 (`REPORT_LAYOUT_INVALID`):**
  - solo `table` (dataset DETAIL) y `summaryTable` (dataset AGGREGATE); sin composiciones;
  - cada dataset aparece en algún elemento;
  - el total es otro dataset AGGREGATE, sin grupos, con exactamente una fila y sin truncar;
  - cada medida del total se empareja por id con una medida visible del resumen del mismo `valueType`.

  **El total sale solo de su dataset (EX-09), nunca del resumen.**
- **Texto (`CellText`):** ≤ 32 767 unidades UTF-16, con la marca `[…]` dentro del límite y sin partir un par
  sustituto, más un aviso.
- **Celdas (`PresentationBudget`, ≤ 600 000):** cuenta de forma conservadora lo que escribirá la presentación:
  - por elemento, filas + encabezado + título (+ total) × columnas visibles, más la cita del detalle;
  - títulos de sección;
  - bloque de encabezado (2 × (16 + filtros + avisos));
  - metodología (4 × (24 + entradas + 8 por dataset)).

  Si se supera: `BUDGET_EXCEEDED`.

**Errores.** `ResultAssemblyException` (`SPEC_MISMATCH`, `REPORT_LAYOUT_INVALID`, `BUDGET_EXCEEDED`), cada uno con su
código de `refusal` de `nexora-report-run/1`; B9 lo convierte. No hay códigos nuevos en la API.

**Para B7.** El renderizador recorre `ReportResult` sin volver a leer contratos. Le corresponden:

- la distribución en hojas (D-A4-4);
- las etiquetas en el idioma («(sin valor)», «Sí/No», «No disponible» a partir de los códigos, «Total general»);
- la conversión de TIMESTAMP_TZ a la zona del encabezado;
- la protección contra fórmulas (EX-11);
- los formatos;
- mantenerse dentro de la reserva de celdas.

**Pruebas:**

- `ResultAssemblerTest`: PA-A real y un segundo reporte de otra forma (`ReportFixtures.Empresas`, validado por B2),
  con las mismas clases.
- `CellTextTest` y `SpecPresentationTest`.
- `ResultAssemblyIntegrationTest`: B2→B3→B4→B6 en PostgreSQL real, para PA-A, el segundo reporte y un truncado real.

## 20. A4 — Excel genérico con Apache POI (bloque A4-B7, 1 nov 2026)

Autorización: «PROCEDE A4-B7» (2026-11-01), con D-B7-1…8 y doce precisiones. Estado: implementado; **pendiente de verificación en Windows**.

**Puerto.** `execution.application.port.ReportRenderer.render(ReportResult, OutputStream, RenderLimits)` devuelve un
`RenderedArtifact` (`report-artifact/1`: tipo MIME, `nexora-excel-report/1`, hojas, bytes, SHA-256). Los bytes llegan al
destino **solo** cuando el archivo está completo y dentro de los límites; si no, nada se escribe y se lanza
`PresentationException` (`artifact_too_large`, `too_many_executions`, `render_failed`). `RenderLimits`: 20 MiB, ventana
de 200 filas, espera de 5 s por defecto.

**Capas (precisión 2).**

- `render.layout` (Java puro, sin POI). `LayoutBuilder.build(ReportResult)` decide qué celdas hay: hoja «Reporte»
  (título; Título, Solicitud, Fuente, Tenant, Generado con zona, Filtros o «Sin filtros», Periodo «No aplica», Versión,
  Estado Completo/Parcial; avisos; secciones en orden: resumen con título, encabezado, una fila por grupo y «Total
  general» del dataset total emparejado por id de salida; detalle citado como «Ver la hoja «X» (N filas).»), una hoja
  por tabla de detalle (encabezado inmovilizado, autofiltro) y «Metodología» (Ejecución, Origen, Reglas; ≤ 4 columnas).
  Las filas de datos se producen de forma perezosa (`RowStream`) a partir de las filas de B6, sin copiarlas.
- `Label`: todos los textos fijos en es/en/fr (un enum: no puede faltar una traducción), frases de operadores y
  motivos de los datos no disponibles. «Reporte» y «Metodología» son literales en todos los idiomas (EX-01).
- `SheetNames`: ≤ 31 caracteres sin partir pares sustitutos, sin `[]:*?/\`, sin apóstrofo al inicio o al final,
  únicos sin distinguir mayúsculas, con «Reporte», «Metodología» e `History` reservados; vacío → id del dataset.
- `ValueFormat` (EX-06, EX-10, R-3, precisión 5): INTEGER número `#,##0` si |v| ≤ 2^53; NUMBER número con tantos
  decimales como su escala si tiene ≤ 15 dígitos significativos y el `double` vuelve al mismo decimal; BOOLEAN
  «Sí»/«No»; DATE/TIMESTAMP fechas de Excel (1900–9999, al milisegundo); TIMESTAMP_TZ convertido a la zona del
  encabezado, nombrada en la cabecera de la columna. Lo que Excel no puede representar se escribe como **texto exacto**
  y genera **un** aviso con el recuento por columna. Nulos: «(sin valor)» solo en claves de grupo de un AGGREGATE;
  en otro caso, celda vacía, nunca 0.
- `render.xlsx`. `XlsxWriter`: `SXSSFWorkbook` con ventana de filas, cadenas en línea, temporales comprimidos y
  `close()` en try-with-resources (nunca `dispose()`); estilo en caché por (tipo, formato, prefijo); anchos calculados
  de lo escrito, entre 8 y 60 caracteres (los títulos y notas no ensanchan columnas); sin celdas combinadas, vínculos
  ni nombres salvo el del autofiltro; propiedades: creador «Nexora AI» y fecha = `generatedAt`; **sin registros Zip64**
  (B7-O1). `XlsxRenderer`: semáforo (4 por defecto); comprueba que las celdas de la maqueta no superan
  `ReportResult.presentationCells()` (la reserva de B6, sin cambiar sus límites; precisión 7) y que lo escrito coincide;
  rechaza antes de escribir si la cota inferior de tamaño supera el límite y, al escribir, cuenta cada byte con
  `LimitedDigestOutputStream`, que falla al pasar el límite y calcula el SHA-256 (EX-17); escribe en un archivo
  temporal propio y lo copia al destino solo si está completo; lo borra en `finally` (precisión 8).

**Inyección de fórmulas (EX-11, precisión 6).** Toda celda de texto —datos, cabeceras, títulos, solicitud, filtros,
metodología, avisos— pasa por `CellValue.text`, que marca con prefijo de cita (`quotePrefixed`) el texto que empieza por
`=`, `+`, `-`, `@`, tabulador o salto de línea. Nunca se crea una celda de fórmula.

**Regla ArchUnit nueva.** `thePresentationKnowsOnlyTheGenericResult`: `..render..` no depende de plan, plan2,
traducción, tenencia, credenciales, adaptadores de ejecución, API, configuración, auditoría, Spring ni JDBC, y
`..render.layout..` no depende de POI ni XMLBeans.

**Pruebas.** Unitarias de `Label`, `SheetNames`, `ValueFormat` y `LayoutBuilder` (PA-A y el segundo reporte por las
mismas clases, parcial, inglés, precisión, textos recortados, reserva, determinismo); `XlsxWriterTest` (cada tipo de
celda, inmovilización, autofiltro, anchos, propiedades, temporales de POI, lectura secuencial sin Zip64);
`XlsxRendererTest` (contrato EX-01…EX-13 reabriendo con XSSF, SHA y tamaño, determinismo, 10 000 filas con ventana de
50, límite durante la escritura y por estimación sin entregar nada, temporales tras éxito y error, reserva,
concurrencia); `XlsxSecurityTest` (sin fórmulas; textos hostiles en título, solicitud, sección, nombre de hoja, filtro
y datos; paquete sin vínculos externos, macros, conexiones ni nombres ajenos); `XlsxAcceptanceIntegrationTest`
(B2→B3→B4→B6→B7 en `postgres:17.6`; escribe `PA-A.xlsx` y `empresas.xlsx` en `target/acceptance-xlsx` y compara con
`esperado/pa-a.json` y con SQL de referencia).

**Fuera de B7.** Directorio de temporales de Reports, registro del renderizador en Spring, almacenamiento de artefactos
y exposición en la API (B8 y siguientes; B7-O3).

## 21. A4 — Almacenamiento, registro y recuperación de artefactos (bloque A4-B8, 1 nov 2026)

Autorización: «PROCEDE A4-B8» (2026-11-01), con D-B8-1…8, los cuatro ajustes y la condición adicional. Estado: implementado; **pendiente de verificación en Windows**. *Actualización (cierre): verificado en Windows el 1 nov 2026, 536/536, con la migración V3 aplicada por Flyway en `postgres:17.6` (`Claude outputs/a4-b8/mvn-verify.log` (SHA-256 `083a7b0f…6a97`)).*

**Puertos** (`execution.application.port`): `RunStore` (`begin`, `finish`, `finishWithArtifact`), `RunReader`
(`find`), `ArtifactReader` (`open`) y `RunMaintenance` (`purgeExpired`, `closeAbandoned`). Adaptadores JDBC en
`run.infrastructure`, sobre la base de Reports (migración V3). Otro proveedor de almacenamiento sería otro adaptador.

**Tablas (V3).** Todas llevan `tenant_id`; nada se actualiza.

| Tabla | Contenido | Reglas |
|---|---|---|
| `report_run` | Inicio: tenant, fuente, plan de reporte, propietario (HUMAN/SERVICE), `started_at`, `deadline_at` | Solo inserción |
| `report_run_outcome` | Resultado (una vez por ejecución): tipo, huellas, código de rechazo o de fallo, instantánea, `datasets` y `warnings` (`jsonb`) | CHECK por estado; PARTIAL ⇔ algún dataset truncado; FK compuesta a la ejecución |
| `report_artifact` | Metadatos de `report-artifact/1` y `created_at`/`expires_at` | Solo para COMPLETED/PARTIAL (trigger); FK compuesta al resultado; con contenido al confirmar (trigger diferido) |
| `report_artifact_content` | Bytes (`bytea`, 1 B–20 MiB) | Trigger: tamaño y SHA-256 iguales a los del artefacto; sin UPDATE ni TRUNCATE; DELETE solo si venció |
| `report_run_event` | `RUN_STARTED`, `RUN_FINISHED`, `RUN_INTERRUPTED`, `ARTIFACT_STORED`, `ARTIFACT_RETRIEVED`, `ARTIFACT_INTEGRITY_FAILED`, `ARTIFACT_PURGED` | Solo inserción; nunca contenido |

Permisos del rol de la aplicación: SELECT e INSERT; DELETE solo sobre el contenido.

**Estados.** Ejecución: IN_PROGRESS (sin resultado, dentro del plazo), COMPLETED, PARTIAL, REFUSED, FAILED (fallo
interno: `render_failed`, `storage_failed`, `internal_error`), INTERRUPTED (sin resultado tras el plazo; la
reconciliación lo registra). Artefacto: AVAILABLE, EXPIRED (venció, aún no purgado: «410» en B9), PURGED. Todo se
deduce del reloj de la base y de filas insertadas una vez.

**Escritura (condición adicional).**

1. `begin`: inicio y `RUN_STARTED` en su propia transacción.
2. `finishWithArtifact`: en **una** transacción, bloqueo consultivo del tenant, cuota de artefactos vigentes,
   resultado, artefacto, contenido (en streaming, con SHA-256 calculado al leerlo) y eventos.
3. Ante cualquier problema esa transacción se deshace entera y el resultado se registra en otra, mínima:
   cuota → REFUSED `artifact_quota_exceeded`; tamaño → REFUSED `artifact_too_large`; archivo distinto de su
   descripción o error de la base → FAILED `storage_failed`.
4. Un resultado por ejecución: si la reconciliación ya cerró la ejecución, un final tardío no guarda nada y
   devuelve el resultado existente. Si no puede registrarse ni el resultado, queda el inicio y la reconciliación
   la cerrará como INTERRUPTED.

**Lectura.** Ámbito obligatorio tenant + fuente + propietario; cualquier otro caso es `NotFound`, indistinguible de
una ejecución inexistente. Los bytes se verifican (tamaño y SHA-256) antes de entregarlos; si no cuadran no se
entrega nada y se registra `ARTIFACT_INTEGRITY_FAILED`. `ARTIFACT_RETRIEVED` significa que el almacenamiento entregó
los bytes a quien los pidió, no que un cliente los recibiera: la confirmación de una descarga HTTP es de B9.

**Mantenimiento.** `purgeExpired` borra contenido vencido (la base rechaza cualquier otro) y registra
`ARTIFACT_PURGED`; `closeAbandoned` registra INTERRUPTED con `ON CONFLICT DO NOTHING`. Son las únicas sentencias que
actúan sobre todos los tenants y no devuelven contenido. B9 las programa.

**Preparación.** `ArtifactStaging`: directorio propio, nombres UUID aleatorios, la ruta resuelta debe quedar
directamente dentro de la raíz, la raíz no puede ser un enlace, permisos del propietario donde hay POSIX, barrido de
restos sin seguir enlaces.

**Límites (`StorageLimits`).** Retención 7 días (1 h–30 días); cuota 500 MiB vigentes por tenant (máximo 5 GiB);
archivo 20 MiB (no ampliable).

**Riesgo residual sin RLS (D-B8-7, B8-O2).** La base no aísla por sí misma las filas de cada tenant. Medidas: filtro
obligatorio por tenant en todo el SQL (comprobado por `RunSqlTest`, con las dos excepciones de mantenimiento
listadas), claves compuestas, permisos mínimos, encapsulación (solo `run.infrastructure` nombra las tablas; ArchUnit)
y pruebas negativas de acceso cruzado.

**Regla ArchUnit nueva.** `theRunRegistryKnowsOnlyTheResultAndItsPorts`: `..run..` no depende de presentación,
planes, traducción, tenencia, credenciales, adaptadores de ejecución, API, configuración, auditoría, Spring, POI ni
Jackson; fuera de `run.infrastructure`, tampoco de JDBC ni de `java.nio.file`.

**Pruebas.** Unitarias del dominio, del JSON, del SQL (filtro por tenant, sin valores incrustados, encapsulación) y de
la preparación; `JdbcRunStoreIntegrationTest` (20, PostgreSQL 17.6 con el rol de la aplicación): cada estado, un solo
resultado, cuota y tamaño como rechazos, nada parcial ante archivos distintos o errores de la base, reconciliación y
carrera con finales tardíos, concurrencia en el borde de la cuota, acceso cruzado, eventos de entrega, vencido y
purga, contenido corrupto, comprobaciones de la propia base y permisos; `RunRegistryAcceptanceIntegrationTest`
(B2→B3→B4→B6→B7→B8 con PA-A y empresas: mismos bytes y SHA-256, libro que se abre con sus totales).

**Fuera de B8.** Endpoints, autenticación, beans de Spring, programación, exposición de FAILED/INTERRUPTED,
confirmación de entregas, `report-run/1` en JSON y límite de descargas simultáneas (B9).

## 22. A4 — Integración, API y seguridad (bloque A4-B9, 1 nov 2026)

Autorización: «Procede con la implementación de Nexora AI A4-B9» (2026-11-01), con D-B9-1…12, el scope opcional
`nexora-reports` para personas, el rol `REPORTS_TENANCY_ADMIN`, el ajuste mínimo de `ContractSchema` y la migración V4.
Estado: **implementado, pendiente de verificación en Windows**.

### 22.1 Flujo

```
HTTP (ReportRunController, TenancyController)            api: sin JDBC, POI, adaptadores ni configuración
  └─ ReportRunService (execution.application)            solo puertos
       ├─ CredentialProvider ............ ConfiguredCredentialProvider (ssl-root-cert por fuente)
       ├─ ReportPlanSource .............. SemanticReportPlanSource (BoundedHttp + ServiceTokenProvider)
       ├─ PlanV2Validator (B2) → PlanV2Translator (B3)
       ├─ RunStore.begin (B8)
       ├─ PlanV2Executor (B4) ........... PostgresReadOnlyExecutor (el mismo objeto que R1)
       │    └─ TenantScopes ............. RegisteredTenantScopes → TenantScopeProbe (B5)
       ├─ ResultAssembler (B6) → ReportRenderer (B7, XlsxRenderer) → StagingArea (StagingDirectory)
       ├─ RunStore.finish / finishWithArtifact (B8)
       ├─ RunReader / ArtifactReader (B8) → ReportRunDocuments (report-run/1, report-artifact/1)
       └─ ArtifactDeliveries ............ JdbcArtifactDeliveries (V4)
RunHousekeeping (@Scheduled): RunMaintenance.closeAbandoned / purgeExpired, StagingDirectory.sweep
```

1. **Pasos 1–3, sin ejecución registrada.** El solicitante (persona o servicio; nunca IA) sale del token; las
   credenciales de solo lectura de (tenant, fuente); Semantic con la cuenta REPORTS_CLIENT del tenant: la fuente debe
   ser PostgreSQL y ACTIVE, y se leen el plan de reporte, la especificación y los planes lógicos que declara (como
   mucho el techo de planes de Reports). Un fallo aquí es un error HTTP y no deja ejecución (B9-O2).
2. **Paso 4.** `RunStore.begin` confirma el inicio con su plazo (120 s). Desde aquí toda salida registra exactamente un
   resultado.
3. **Pasos 5–11.** Validación de B2 (un fallo es REFUSED con el tipo de la primera violación: `contract_invalid`,
   `plan_not_supported`, `identity_mismatch`, `fingerprint_mismatch`, `budget_exceeded`); vigencia antes de leer
   (`plan_stale`; si no se puede confirmar, `plan_validity_unconfirmed`); traducción; lectura en una instantánea con la
   comprobación de tenencia obligatoria (cualquier `ExecutionException` es REFUSED con su código en minúsculas,
   B9-O3); vigencia después de leer (`plan_changed_during_execution`, las filas se descartan); ensamblado (sus
   rechazos de B6); presentación en un archivo del directorio de preparación (`artifact_too_large`,
   `too_many_executions` o FAILED `render_failed`); `finishWithArtifact` (cuota y tamaño en B8). Un error no previsto
   es FAILED `internal_error`. El archivo preparado se borra siempre.
4. **Respuesta.** Siempre se relee la ejecución del registro y se construye el documento con `ReportRunDocuments`: la
   respuesta del POST y cualquier GET posterior son el mismo documento.

### 22.2 Documentos del contrato

`ReportRunDocuments` construye `nexora-report-run/1` (consistencia `REPEATABLE_READ`, solo lectura, una transacción
y la instantánea cuando hubo lectura; datasets sin la huella de cada plan, que el contrato no admite; avisos y
rechazos con mensajes generados de sus códigos, sin datos de negocio) y `nexora-report-artifact/1`, y los valida con
los esquemas reales (`RunContractSchemas`, copiados byte a byte y comprobados con `RUN-CONTRACTS.sha256`) y con
RUN-R1. `ContractSchema` (B2) trata `format` como anotación, como manda JSON Schema 2020-12; el `pattern` que lo
acompaña es la comprobación. FAILED, INTERRUPTED, una ejecución en curso y un REFUSED sin huella conocida no se
describen con el contrato (B9-O4).

### 22.3 Seguridad

- Tokens: la misma validación de R1 (emisor, firma, audiencia `nexora-reports`, `sub`, `principal_type`, `tenant`).
- `reports:execute` (`REPORT_RUNNER`) para ejecutar, leer y descargar; `reports:tenancy:manage`
  (`REPORTS_TENANCY_ADMIN`) solo para personas (el conversor lo quita a SERVICE e IA y la API lo comprueba otra vez).
- Lecturas filtradas por tenant, fuente y propietario (B8); cualquier otra ejecución es indistinguible de una
  inexistente.
- `verify-full` y `pg_stat_ssl` siguen siendo obligatorios en la ruta `/2` y en el registro de tenencia (D-B9-11).
- Ningún nombre de cliente en `src/main` (`NoClientSpecificNamesTest`).
- ArchUnit: `theApiKnowsNeitherPersistenceNorPresentationNorAdapters` y `theRunServiceUsesPortsOnly`.

### 22.4 Concurrencia, almacenamiento y mantenimiento

- Un solo `PostgresReadOnlyExecutor` para R1 y `/2` (16 ejecuciones en total y por fuente); 4 presentaciones; 4
  descargas (`TOO_MANY_DOWNLOADS`, 429).
- `nexora.reports.storage.directory`: `staging` (B8, barrido al arrancar) y `poifiles` (estrategia global de POI
  fijada al arrancar, B7-O3); `StorageLimits` desde `retention`, `tenant-quota-bytes`, `max-file-bytes`.
- `RunHousekeeping`: reconciliación (1 min), purga (15 min) y barrido (1 h), en lotes de 500 y como máximo 20 lotes
  por disparo; un fallo se registra y se reintenta en el siguiente.
- Migración V4: amplía la lista cerrada de eventos con `ARTIFACT_DELIVERED` y `ARTIFACT_DELIVERY_FAILED`.

### 22.5 Cumplimiento de PR-1, PR-2 y PR-3

PR-1: B9 no guarda conocimiento reutilizable; los planes llegan de Semantic con identidad lógica y un cambio de
esquema se traduce en `plan_stale` o `physical_schema_mismatch`, sin código. PR-2: un endpoint genérico para cualquier
reporte (PA-A y «Empresas» por el mismo camino, R4-26). PR-3: B9 no interpreta lenguaje; los rechazos son explícitos.

## 23. A4 — Capacidades, cuerpo del POST y endurecimiento (bloque A4-B10-b, 2026-11-02)

Autorización: «PROCEDE exclusivamente con A4-B10-b» (2026-11-02), con D-B10-2, D-B10-3, D-B10-4, D-B10-5, D-B10-12 y D-B10-13. Estado: **implementado y verificado en Windows** (2026-11-02, 707/707; no es el cierre formal de A4). B10-a, B10-c, B10-d y B11
no están autorizados.

### 23.1 Capacidades (D-A4-8, D-B10-3, D-B10-4)

```
GET /api/v1/capabilities ─ CapabilitiesController (api) ─ ReportsCapabilities (plan2.domain, Java puro)
                                                            ├─ ReportsProfile (constantes de basic-a)
                                                            └─ ReportsCeilings (bean compartido con PlanV2Validator)
ReportRunConfiguration: genera el descriptor y lo valida con RunContractSchemas.capabilities (esquema real copiado
byte a byte, manifiesto CAPABILITIES-CONTRACT.sha256); si no valida, la aplicación no arranca.
```

- **Fuente única:** `ReportsProfile` reúne lo que acepta `basic-a`; `BasicAProfile` usa sus constantes (operaciones,
  agregaciones, operadores, intercalación, unicidad) y el descriptor las anuncia. Los techos son un bean
  (`ReportsCeilings`) que usan el validador, el servicio de ejecuciones y el descriptor.
- **Lo anunciado se exige:** el esquema de `report-spec/1` limita datasets, joins por dataset, secciones, tipos de
  elemento, agregaciones y composiciones para `basic-a`; `BasicAProfile` limita los planes lógicos y, **nuevo en
  B10-b**, sus operaciones JOIN (como mucho 2), que el esquema de `logical-plan/2` no limitaba. Pruebas de
  conformidad: `CapabilitiesConformanceTest` y `CapabilitiesItemConformanceTest`.
- **Autorización:** `isAuthenticated()` más un `Requester` (tenant del token); sin permiso específico.

### 23.2 POST sin cuerpo (D-B10-5)

`RequestBodies.present`: una longitud declarada decide; sin ella (fragmentos) se lee el primer byte; si no se puede
leer, cuenta como presente (fallo cerrado). Se comprueba en el cuerpo del método del controlador, es decir, después de
la autenticación (filtro) y de `@PreAuthorize`, y antes de llamar al servicio.

### 23.3 Logs (D-B10-13)

`RedactedFailure` (`execution.domain`, Java puro): copia de un fallo con la clase de cada excepción de la cadena y sus
marcos de pila, sin mensajes ni excepciones suprimidas. Se usa donde se registra un fallo de origen desconocido:
`ApiExceptionHandler.unexpected` y el fallo interno de `ReportRunService`. Los mensajes de `ExecutionException` (textos
fijos de Reports y SQLSTATE) no cambian.

### 23.4 Pruebas (sin cambiar el comportamiento de producción)

- `RunProbes` (solo pruebas): envuelve los puertos `CredentialProvider`, `TenantScopes` y `ReportPlanSource` para
  contar credenciales resueltas y lecturas iniciadas e inyectar un fallo inesperado; `TestPlatform` admite respuestas
  de Semantic en secuencia (vigencia antes y después).
- `ReportRunLimitsIntegrationTest` (T-8), `ReportRunSafeguardsIntegrationTest` (T-9, T-11, T-5 independiente de
  Semantic, T-10 por HTTP): mismo banco con TLS `verify-full`, tenencia aprobada por dos personas y Semantic simulado
  con los ejemplos PA-A del contrato realojados en el tenant de cada clase. **No** son planes compilados por Semantic
  para estas pruebas ni una integración viva entre servicios (eso es B10-a/B10-c y la validación con despliegue).

### 23.5 Cumplimiento de PR-1, PR-2 y PR-3

PR-1: nada específico de Wellnode ni de un esquema en `src/main` (`NoClientSpecificNamesTest`); el descriptor sale de
constantes genéricas. PR-2: B10-b no añade aprendizaje empresarial ni reportes nuevos; solo anuncia y endurece el
camino genérico. PR-3: B10-b no interpreta lenguaje natural ni toca Intelligence; no es evidencia de comprensión
semántica.

### 23.6 Verificación en Windows y B10-O3 (2026-11-02)

- Surefire (conservado en `Claude outputs/a4-b10b/windows-2026-11-02/`): 58 clases, 707 pruebas, 0 fallos, 0 errores, 0 omitidas; Windows 11,
  Java 21.0.6. `BUILD SUCCESS` según la salida de Maven compartida por el usuario (el log completo no se conservó).
- **B10-O3 / DS-20 sigue abierta.** Orientación del usuario: el usuario selecciona conceptualmente la **opción B** (separar la comprobación de que la fuente tiene credenciales configuradas de la resolución efectiva del secreto); **no está autorizada su implementación**. Queda pendiente una propuesta técnica que preserve el comportamiento HTTP, D-A4-5, la tenencia, la autorización y la ausencia de conexiones para planes STALE. El código no cambia en esta tarea.

## 24. B10-O3 / DS-20 — Destino sin secreto y credenciales resueltas antes de leer (2026-11-02)

Autorización: «NEXORA AI — B10-O3 / DS-20, FASE 2 — PROCEDE con la implementación aislada» (2026-11-02), con D-O3-1 a D-O3-5. Estado: **implementado, pendiente de verificación en Windows**; DS-20 no se da por
cerrada. **Actualización (2026-11-02): verificado en Windows, 730/730; ver §24.5.**

### 24.1 Diseño (D-O3-1, D-O3-2, D-O3-3)

- Puerto nuevo `execution.application.port.SourceAvailability#configured(tenant, fuente) → Optional<SourceTarget>`,
  separado de `CredentialProvider` (que sigue siendo una interfaz funcional y es el que usan R1 y la tenencia).
- `credentials.domain.SourceTarget`: tenant, fuente, host, puerto, base, usuario, `sslmode`, tamaño del pool y
  certificado de la CA. Ningún secreto ni nada derivado de él. Su igualdad compara todos los componentes de forma
  exacta. `DataSourceCredentials#target()` lo obtiene de unas credenciales.
- `ConfiguredCredentialProvider` implementa los dos puertos con la misma búsqueda y la misma `TargetPolicy`; es un solo
  bean (`ReportsConfiguration`).
- `ReportRunService`: paso 2 con `configured()`; `resolve()` en un paso 7 bis, después de la vigencia y la traducción
  e inmediatamente antes de `executeAll`; si el destino resuelto no es igual al del paso 2, REFUSED
  `data_source_configuration_changed` (código nuevo `ErrorCode.DATA_SOURCE_CONFIGURATION_CHANGED`, `409`).

### 24.2 Límites de la protección frente a TOCTOU

- El destino decidido al iniciar queda fijado: la lectura solo procede con credenciales que lleven exactamente a él, y
  dentro de la transacción `TenantScopeProbe` (sin cambios) vuelve a comparar las credenciales y la identidad real de
  la base con el registro de tenencia aprobado.
- **No elimina todo riesgo.** Entre `resolve()` y la conexión sigue habiendo un intervalo (lo cubre la sonda de
  tenencia, no esta comparación); la contraseña no se compara; y los pools se guardan por (tenant, fuente), no por
  destino ni por contraseña: tras una rotación se reutiliza el pool anterior (**B10-O10**, relacionado con RR14; no se
  corrige en este bloque).
- El proveedor actual lee la configuración al arrancar y no cambia en caliente: los cambios entre el paso 2 y el 7 bis
  solo se han probado con dobles (`ReportRunServiceTest`), no con el proveedor real.

### 24.3 Pruebas (D-O3-4)

- Contadores separados en `RunProbes`: consultas de disponibilidad (`configured`), credenciales resueltas
  (`resolve`) y lecturas iniciadas; el proxy implementa todas las interfaces del bean.
- `BenchSessions`: sesiones físicas al banco contadas por PostgreSQL (`pg_stat_database.sessions`, leído solo cuando
  no cambia durante 12 s, por encima del intervalo de publicación de 10 s; y `pg_stat_activity` desde un instante del
  servidor), observadas desde otra base. La prueba usa fuentes que nadie más usa (sin pool) y un control positivo que
  demuestra que las medidas detectan una conexión.

### 24.4 PR-1, PR-2 y PR-3

PR-1: ningún conocimiento de cliente en `src/main`; el puerto queda independiente del origen de los secretos. PR-2 y
PR-3: sin efecto.

### 24.5 Verificación en Windows (2026-11-02; DS-27)

**Verificación en Windows (2026-11-02; anotada en B10-c.1, DS-27).** Respaldado por el log de Maven conservado (`mvn-verify-b10o3.log`, SHA-256 `2e0882bf5dc8adebe18e31d9d1718f6beb9f6f3587aa1fc65f8a61e5382d3f81`): **730 pruebas, 0 fallos, 0 errores, 0 omitidas**, `BUILD SUCCESS`, 01:49 min, PostgreSQL 17.6; respaldado por Surefire (119 archivos, 59 clases, Windows 11, Java 21.0.6) en `Claude outputs/a4-b10o3/windows-2026-11-02/`. `ReportRunSafeguardsIntegrationTest` 18/18 (la prueba de sesiones físicas pasó en 37,039 s, con control positivo), `ReportRunServiceTest` 53/53, `SourceTargetTest` 4/4, `ArchitectureTest` 13/13. B10-O3 y DS-20 **resueltas para el alcance comprobado**. Límites que se mantienen: el TOCTOU se reduce, no desaparece; los cambios de configuración entre el inicio y la lectura solo se prueban con dobles; **B10-O10 sigue abierta**.

## 25. A4-B10-c.1 — Planes auténticos de Semantic en el motor genérico (2026-11-02)

Autorización: «NEXORA AI — A4-B10-c.1 FASE 2 — IMPLEMENTACIÓN AUTORIZADA» (2026-11-02). Estado: **implementado, pendiente de verificación en Windows** (**actualización 2026-11-02: verificado en Windows, 757/757 con FIX1; §25.5**). Evidencia de nivel B
(transporte de Semantic simulado). Solo cambia código de pruebas; `src/main` queda intacto.

### 25.1 Integridad de los documentos auténticos

`AuthenticPlans.load()` comprueba, antes de usar nada, el pin `semantic-b10a.sha256` (huellas de `manifest.json` y
`SHA256SUMS`), las 53 entradas de `SHA256SUMS` y los 51 archivos del manifiesto (SHA-256 y tamaño). Un byte cambiado o
un pin distinto hacen fallar la carga. Los documentos se sirven a Reports **byte a byte** por el Semantic simulado
(fuente, plan de reporte, especificación, planes lógicos y respuestas de vigencia propias de Semantic). Están ligados
al tenant y a la fuente de la exportación: con otros, el validador real responde `IDENTITY_MISMATCH`.

### 25.2 Referencias independientes

El resultado esperado de cada dataset sale de un SQL escrito a mano desde la especificación (columnas, filtros,
agrupación, orden con intercalación `C` y desempate por la clave primaria), ejecutado con el rol de solo lectura del
banco (`SET ROLE`) fuera del traductor y del ejecutor de Reports. El Excel descargado por la API se compara celda a
celda con esas filas (`AuthenticWorkbook`, guiado por la especificación: hoja «Reporte», una hoja por tabla,
«Metodología» al final). PA-A se compara además con `esperado/pa-a.json` del banco.

### 25.3 Un solo motor

Los siete casos CURRENT recorren el mismo camino de `ReportRunService` (una consulta de disponibilidad, una resolución
de credenciales, una lectura). Ampliar a otro caso es añadir documentos y una fila en `casos.json` con su SQL de
referencia. Una prueba recorre `src/main` y comprueba que ningún identificador, tabla, dataset o caso de la exportación
aparece en el código de producción (E14).

### 25.4 Límites

- No es integración viva Semantic–Reports ni prueba de lenguaje natural; PR-2 y PR-3 no se declaran satisfechos.
- Los metadatos del banco en B10-a son un fixture (B10a-O2); el volumen real (12 000 filas) se lee aquí, con el
  detalle truncado en 10 000 (`PARTIAL`, `dataset_truncated`).
- B10c1-O1: la cabecera de un filtro NUMBER muestra el literal pedido (`300.000`), no el canónico; EX-02 no lo fija.
- Garantías de B10-O3 / DS-20, tenencia, TLS `verify-full` y autorización sin cambios; **B10-O10 abierta**.

### 25.5 Aislamiento (FIX1) y verificación en Windows (2026-11-02)

- Primera ejecución en Windows: 755/756. La prueba de aislamiento servía a otro tenant el descriptor auténtico de la
  fuente de la exportación; Reports lo rechaza en el paso 3 (`502 semantic_contract_violation`, sin ejecución), antes
  de que el validador pueda ver los documentos (B10c1-O6). Era un error del escenario, no del código.
- FIX1 (solo pruebas) separa los dos casos: (A1) descriptor de otra fuente → 502, sin ejecución, credenciales,
  lecturas ni consultas posteriores; (A2) descriptor sintético coherente de la fuente propia del otro tenant y
  documentos auténticos de `pa-a` byte a byte → `201` REFUSED `identity_mismatch`, sin credenciales ni lecturas, y 404
  entre tenants y propietarios.

**Verificación en Windows (2026-11-02, con FIX1; «NEXORA AI — A4-B10-c.1 — Cierre documental» (2026-11-02)).** Respaldado por el log de Maven conservado (`mvn-verify-b10c1-fix1.log`, SHA-256 `3bdc9620eca4d9378e5ced65a1ab1ed96aeb0d248f88e4ad26225bea733a9f7c`, igual en `Nexora-Reports/` y en la copia archivada): **757 pruebas, 0 fallos, 0 errores, 0 omitidas**, `BUILD SUCCESS`, 02:26 min, terminado 2026-11-02T09:48:02-05:00, `postgres:17.6`, Java 21.0.6. Respaldado por Surefire (63 clases, 757/0/0/0, Windows 11): `AuthenticPlanRunIntegrationTest` 17/17 (incluidas A1 y A2 de FIX1), `AuthenticPlanStaleIntegrationTest` 1/1 (STALE `plan_stale` sin credenciales, lecturas ni sesiones físicas; el control `pa-a` terminó COMPLETED), `AuthenticPlansIntegrityTest` 5/5, `SemanticCapabilitiesCompatibilityTest` 4/4, `ArchitectureTest` 13/13, `NoClientSpecificNamesTest` 1/1. Evidencia en `Claude outputs/a4-b10c1/windows-2026-11-02-fix1/` (`SHA256SUMS.txt` `104612234f2fb3a85caf94676138e023d7d2e65e5e4d25c04dda919b77520c13`, `RESUMEN.md`, `resumen-por-clase.csv`): siete `.xlsx` y sus `report-run` (huellas coherentes; seis COMPLETED y `volumen` PARTIAL con `dataset_truncated`), `resumen.json` y `b10c1-evidence/SHA256SUMS` íntegro. El código ejecutado es el transferido (84 huellas «after» de B10-c.1 y FIX1; `src/main` sin cambios). **A4-B10-c.1 queda verificado en Windows dentro de su alcance**: evidencia de nivel B (planes auténticos de B10-a con el transporte de Semantic simulado). No es integración viva Semantic–Reports, ni lenguaje natural, ni cumplimiento íntegro de PR-2 y PR-3. Siguen abiertas B10-O10, DS-21, B10-O4, B10c1-O1 y B10c1-O4. El resultado anterior (755/756, BUILD FAILURE) se conserva arriba.

## 26. A4-B10-c.2 — Memoria y temporales del volumen (R4-6) (2026-11-02)

Autorización: «NEXORA AI — A4-B10-c.2 — Autorización de implementación» (2026-11-02), con D-C2-1, D-C2-1b, D-C2-2 y D-C2-3. Estado: **implementado, pendiente de verificación en Windows; R4-6 no completo**.
Solo código de pruebas; `src/main` sin cambios.

### 26.1 Qué se mide y por qué es comparable

- `E`: la suma de `ResultSizeEstimate.ofRow` sobre las filas leídas; es la misma función que el lector suma al leer
  (`Totals.add`) y controla el presupuesto de 64 MiB.
- Tamaño retenido medido con JOL (`GraphLayout.parseInstance`, disposición real de la JVM que ejecuta), cada objeto
  una vez por identidad; se mide dos veces y debe coincidir. Solo se descuentan instancias que el JDK guarda en caché
  y comparte por valor (`Boolean`, `Long` −128…127, `ZoneOffset`, `LocalTime` en punto), y solo si son idénticas a la
  de la caché, con lo que solo ellas contienen. Se registra también la cifra sin descuento.
- `A` (afirmado, tolerancia 0): cada fila tal como la guarda `DatasetRows` (envoltorio inmodificable, `ArrayList`,
  `Object[]`, valores). `B` (afirmado, tolerancia 0): `A` más la lista que contiene las filas de cada dataset.
- Informativos: `C`, pico transitorio al construir `DatasetRows` (filas leídas y copias conviven; contenedores
  medidos con JOL sobre listas de la misma forma); `D`, el `ReportResult` con las filas que comparte (incluye objetos
  de la JVM alcanzables, como reglas de zona horaria); bytes asignados por el hilo de lectura (incluye basura); pico
  de los pools del heap (suma de picos por pool; incluye otros hilos).
- Una prueba verde **no** demuestra que una ejecución completa use menos de 64 MiB: la estimación solo acota las filas.

### 26.2 Temporales (C2-3)

Ejecución del caso `volumen` por la API en un contexto con su propio directorio de almacenamiento: se comparan los
archivos de `staging/` y `poifiles/` (y del directorio de POI si es otro) antes y después, en éxito y con el límite de
archivo rebajado a 64 KiB (falla mientras escribe: el mínimo de la maquetación es ~25 KB y el archivo ~171 KB). Un hilo
muestrea cada 2 ms el espacio usado: el máximo observado es **una cota inferior** del pico real. El requisito de
128 MiB de disco temporal por presentación (v3 §4 bis.5(b)) **no está implementado** en producción y sigue abierto
(D-C2-2); no se compara con él.

### 26.3 Memoria por ejecución y memoria agregada (D-C2-3)

- Por ejecución: las filas (`B`) más lo que añade el ensamblado (`D − B`) mientras se presenta, más la escritura SXSSF
  (ventana de 200 filas por hoja) y, al descargar, el artefacto entero en memoria (hasta 20 MiB × 4 descargas).
- Agregada: la plaza de lectura (16 global, 4 por fuente) se libera al terminar la lectura, pero las filas siguen en
  memoria durante la vigencia posterior (hasta el tiempo de lectura de Semantic), el ensamblado y la espera de plaza de
  presentación (4). El número de ejecuciones con filas en memoria lo limitan, por tanto, las peticiones simultáneas,
  no las plazas de lectura. **No hay presupuesto agregado**; limitar los hilos del servidor no es una solución
  definitiva. Debe evaluarse un presupuesto agregado antes del piloto (B10c2-O2).
- Heap recomendado: al menos `M_base + N × (k × 64 MiB + R) + 4 × 20 MiB`, donde `N` son las ejecuciones simultáneas
  entre lectura y presentación, `R` lo que añade ensamblado y presentación (`D − B` medido, más SXSSF), `M_base` el
  heap de la aplicación en reposo (**no medido**) y `k` la razón real/estimado de las filas. Con la estimación actual
  `k` no está acotado por la prueba (localmente 1,05 en el volumen y hasta ~1,9 en filas de una columna sin
  referencias comprimidas); con la corrección propuesta sería ≤ 1 en la JVM medida.

### 26.4 Hallazgo y corrección propuesta (no aplicada; requiere autorización)

Las mediciones locales (sustituto de JOL; ver [PROJECT_STATUS.md](PROJECT_STATUS.md), «A4-B10-c.2») muestran que `ResultSizeEstimate` subestima, porque
supone referencias de 8 bytes y omite cabeceras y alineación: no cuenta el envoltorio inmodificable ni la cabecera
del `Object[]` de cada fila, ni la referencia de la fila en la lista del dataset; un `Long` ocupa 24 B y no 16; un
`LocalDateTime` incluye su `LocalDate` y su `LocalTime`; un `BigDecimal` grande, su `BigInteger` y su `int[]`; los
textos cortos pierden por alineación. Corrección mínima propuesta, cota superior para HotSpot de 64 bits con y sin
referencias comprimidas, comprobada localmente con el sustituto en ambos modos (todos los casos de C2-1 dentro):

| Elemento | Hoy | Propuesto |
|---|---|---|
| Fila de n valores | 24 + 8n | 96 + 8n (envoltorio, lista, array y su referencia en la lista del dataset) |
| Dataset | 0 | 96 una vez por dataset (requiere sumarlo en `Totals`, en `PlanV2SnapshotReader`) |
| Texto de n caracteres | 40 + 2n | 64 + 2n |
| `Long` | 16 | 24 |
| `BigDecimal` | 56 + bytes | 48 si el valor sin escala cabe en 63 bits; si no, 128 + 4·⌈bits/32⌉ |
| `LocalDate` | 24 | 24 |
| `LocalDateTime` | 64 | 80 |
| `OffsetDateTime` | 88 | 112 (desplazamiento UTC compartido) |
| `Boolean`, nulo | 0 | 0 |

Consecuencia: con referencias comprimidas la estimación quedaría ~1,5 veces por encima de lo real en filas estrechas
(el presupuesto de 64 MiB admitiría ~43 MiB reales de filas); no se cambia ningún límite. Alternativa: constantes
exactas para referencias comprimidas más una comprobación al arrancar (más código de producción).

### 26.5 Corrección aplicada («NEXORA AI — A4-B10-c.2 — Corrección de estimación de memoria» (2026-11-02))

- `ResultSizeEstimate`: constantes de la tabla de §26.4 **salvo `BigDecimal`**, cuya regla «48 si cabe en 63 bits» era
  errónea: `new BigDecimal(texto)` conserva un `BigInteger` cuando el texto tiene más de 18 caracteres aunque el valor
  quepa en un `long` (`9000000000000000000`, `1.000000000000000000`: 104 B con referencias comprimidas, 128 sin ellas).
  Ahora cuenta siempre `128 + 4·⌈bits/32⌉` (mínimo 132). Suma y producto saturan en `Long.MAX_VALUE`: un desbordamiento
  solo puede producir un rechazo (`result_too_large`), nunca una estimación menor; los operandos negativos se rechazan.
- `PlanV2SnapshotReader.Totals`: suma `ResultSizeEstimate.DATASET` al empezar cada dataset (también vacío) y usa la suma
  con saturación. Los límites no cambian.
- Supuestos (javadoc de `ResultSizeEstimate`): HotSpot de 64 bits con alineación de 8 bytes, con o sin referencias
  comprimidas; otras alineaciones y otras JVM no están cubiertas.
- Efecto: en el volumen B/E pasa de 1,051 a 0,660 (comprimidas) y de 1,440 a 0,904 (sin comprimir); 64 MiB estimados
  equivalen a unos 43 MiB reales de filas estrechas con referencias comprimidas. Con 30 000 filas como máximo, el
  presupuesto solo actúa con filas de más de ~2 237 B estimados de media (filas anchas o con mucho texto).
- Riesgos que siguen abiertos (no cubiertos por la estimación): el pico transitorio al construir `DatasetRows`, los
  búferes del driver y TLS, lo que añaden ensamblado, presentación y descargas, la memoria agregada (B10c2-O2), el
  consumo total del proceso (heap base, metaspace, código, pilas, búferes directos, margen del GC; no medido) y los
  128 MiB de temporales (B10c2-O1).

### 26.6 JOL y records («NEXORA AI — A4-B10-c.2 — corrección mínima JOL/records» (2026-11-02))

- Causa del error de Windows: sin `Instrumentation` (la conexión dinámica no se permitió), JOL 0.17 calcula tamaños con
  `sun.misc.Unsafe.objectFieldOffset`, que desde Java 16 rechaza los campos de records. Solo `D` (con `ReportResult`)
  contiene records.
- `-Djol.magicFieldOffset=true` (Surefire): JOL obtiene entonces los offsets reales de la JVM por otra vía interna;
  depende de detalles del JDK y, si deja de funcionar, lanza un error, no mide mal.
- `JolLayoutCheck` comprueba, clase por clase, que el tamaño que usa JOL es el de la JVM: `Instrumentation` (agente de
  Byte Buddy, ya presente por Mockito) o, en su defecto, la cuenta de asignación por hilo (`ThreadMXBean`, menor de
  5 rondas). No supone que un record y una clase con los mismos campos ocupen lo mismo.
- Las mediciones que fallan quedan en la evidencia con su error y la prueba falla igual.

### 26.7 Verificación final en Windows y estado («PROCEDE con el cierre documental de B10-c.2» (2026-11-02))

**Verificación final en Windows (2026-11-02; «PROCEDE con el cierre documental de B10-c.2» (2026-11-02)).** Respaldado por el log de Maven conservado (`mvn-verify-b10c2-jol.log`, SHA-256 `4622109c2112c29f72bdfadbe75fdda60376443a44d0ca977ca936692ef8cabb`) y Surefire: `mvn -B clean verify` ⇒ ejecución por defecto **792 pruebas, 0 fallos, 0 errores, 0 omitidas** (68 clases) y ejecución sin referencias comprimidas **26/0/0/0** (solo `ResultSizeEstimateLayoutTest`), `BUILD SUCCESS`, 02:44 min, Windows 11, Java 21.0.6. C2-1: 27/27 casos dentro en cada modo (peor razón real/E 1,000, `Long`); C2-2: tres repeticiones idénticas, A/E 0,6499 y B/E 0,6597, presupuesto `E` lee y `E − 1` se rechaza; JOL contrastado con `Instrumentation.getObjectSize` sin diferencias (20, 15 y 48 clases); C2-3: éxito y rechazo con 0 archivos y 0 bytes residuales (máximo observado 340 275 B y 204 122 B, cota inferior). Código ejecutado = transferido (14 huellas; `src/main` `e66afbfb…2084`). Evidencia en `Claude outputs/a4-b10c2/windows-2026-11-02-final/` (`SHA256SUMS.txt` `157adad4451ec9c99a094165b11ca6f097bc611d55e409e69ca54cabfcdc1155`). **A4-B10-c.2 queda verificado en Windows dentro de su alcance.** Historial conservado arriba: C2-1 10/19 antes de corregir la estimación y 791/792 por JOL y records. **R4-6:** «Criterios de medición y verificación de B10-c.2 demostrados satisfactoriamente en Windows. Documentación del heap recomendado parcialmente completada. R4-6 permanece abierto y será reevaluado en B11 conforme a sus criterios originales de aceptación, sin considerar garantizado su cierre». Límites: sin referencias comprimidas, en Windows solo se ejecutó C2-1 (el volumen en ese modo, B/E 0,904, está medido solo en local); la comprobación con `Instrumentation` usa el agente de Byte Buddy de Mockito (si un JDK lo impidiera, pasaría a la cuenta de asignación). Siguen abiertas B10c2-O1 (128 MiB de temporales), B10c2-O2 (memoria agregada), B10c2-O5 (alcance de la estimación) y B10c2-O6 (vía interna de JOL); B10c2-O3 y B10c2-O4 resueltas.

## 27. Presentación de los reportes Excel (EX-19, 2026-11-04)

Autorización: «PROCEDE con la implementación de la mejora de presentación dinámica de los reportes Excel de Nexora AI»
(usuario, 2026-11-04 13:18), alternativa A y decisiones D-XL-1 a D-XL-8. Estado: **implementado; pendiente de
`mvn -B clean verify` en Windows y de comprobación visual en Excel 365, LibreOffice y Google Sheets.**

**Principio (D-XL-1, «misma rejilla»).** La presentación cambia cómo se ve un reporte, nunca lo que contiene: las
hojas, sus nombres y orden, cada celda (coordenada, tipo, valor exacto, formato numérico, prefijo de cita), las filas
inmovilizadas, los autofiltros y la ausencia de celdas combinadas son los de antes. Lo demuestra
`PresentationGridTest` contra referencias escritas por el renderizador **anterior** (`GridBaselines`,
`src/test/resources/com/nexora/reports/render/xlsx/grid/*.grid`, con su SHA-256 fijado en la prueba).

**`render.layout` (sin POI).**

- `ValueKind` (nuevo): categoría del valor de una celda — `TEXT`, `INTEGER`, `DECIMAL`, `DATE`, `DATE_TIME`,
  `BOOLEAN`, `NO_VALUE` —, derivada del tipo genérico de la columna (`ValueFormat.kindOf(OutputValueType)`) o, si
  nadie la indica, de lo que contiene la celda (`ValueKind.of`). `NO_VALUE` es la clave de grupo nula de un AGGREGATE
  (función DIMENSION, EX-10). Nunca se mira el nombre de una columna ni su contenido.
- `LayoutCell` gana `kind` (constructor de tres argumentos compatible). `LayoutBuilder` la fija en encabezados,
  datos y totales (por el tipo de la columna) y en las tablas de la metodología.
- `StyleKind.HEADER_VALUE` (nuevo): valor de un campo del encabezado del informe; **no ensancha columnas** (corrige
  que una solicitud larga llevara la columna B de «Reporte», compartida con las tablas de resumen, a 60 caracteres).
  El estado «Parcial» se presenta como `WARNING`. Las filas de las tablas de la metodología pasan de `VALUE` a `DATA`
  (mismo valor y formato; ahora con bordes y alineación de tabla).
- `SheetLayout.Kind` (`REPORT`, `DETAIL`, `METHODOLOGY`) y `WorkbookLayout.pageFooter` (`Label.PAGE_FOOTER` en
  es/en/fr). Constructores anteriores conservados.

**`render.xlsx`.**

- `PresentationTheme` (nuevo, D-XL-3): colores principal, de texto de encabezado, de acento y de banda (`RRGGBB`),
  fuente (Calibri, Arial o Aptos), tamaño (9–14) y bandas (D-XL-2, desactivadas por defecto). Validado al crearse:
  contraste WCAG ≥ 4,5:1 del texto de encabezado sobre el principal y del negro sobre acento y banda. Por defecto:
  `1F3864`, `FFFFFF`, `D9E1F2`, `F2F2F2`, Calibri 11, sin bandas. Los colores de avisos, etiquetas y notas son fijos
  y cumplen el mismo contraste.
- `XlsxStyles` (nuevo): un estilo por (tipo de celda, categoría, formato, prefijo de cita, banda), en caché sin crear
  objetos por celda; < 60 estilos en los reportes de prueba e independiente del número de filas. Fuentes en caché; la
  fuente por defecto del libro es la del tema. Alineación en tablas: texto a la izquierda, números a la derecha,
  fechas y booleanos centrados; el encabezado como su columna.
- `XlsxWriter`: anchos por tipo (números según su texto con formato en lugar de 16 fijos; fechas según su formato;
  texto por puntos de código), entre 8 y 60; un encabezado de tabla de más de 24 caracteres se ajusta (hasta 3 líneas,
  con la altura de fila calculada) y, con autofiltro, reserva 2 caracteres para su botón. «Reporte» y «Metodología»
  sin cuadrícula (D-XL-8). Impresión (D-XL-5): horizontal con más de 6 columnas, pie «Página &P de &N» (con `&`
  escapado y sin caracteres de control), y en las hojas de detalle ajuste a una página de ancho y fila 1 repetida
  (`_xlnm.Print_Titles`). «Reporte» y «Metodología» no se ajustan al ancho: sus textos largos continúan sobre celdas
  vacías y reducirían toda la página. Propiedad de título = título del informe, sin caracteres de control, ≤ 255
  (D-XL-6); la solicitud no se copia.
- `XlsxRenderer(maxConcurrent, temporaryDirectory, theme)`; los constructores anteriores usan el tema de Nexora.

**Configuración.** `config.PresentationProperties` (`nexora.reports.presentation.*`, `application.yml`): `primary-color`,
`header-text-color`, `accent-color`, `band-color`, `font-name`, `font-size`, `banding`, con variables
`REPORTS_PRESENTATION_*`. Vacío = tema de Nexora; un valor no válido impide arrancar. Un tema por tenant queda para
una etapa posterior. `compose.yaml` de `nexora-deploy` no se cambió: el contenedor usa los valores por defecto.

**Pruebas (28 nuevas).** `PresentationGridTest` (2: identidad de la rejilla en 10 fixtures —PA-A en español, inglés,
parcial, con valor como texto, con textos recortados y con 300 filas; «Empresas»; un reporte sencillo y uno compuesto
escritos directamente; el libro de `XlsxWriterTest`— y huellas de las referencias), `PresentationStyleTest` (14),
`PresentationThemeTest` (4), `PresentationLayoutTest` (5), `PresentationPropertiesTest` (2) y una en
`XlsxSecurityTest` (título de impresión de una hoja con nombre hostil, pie escapado, sin fórmulas, formato
condicional, validación ni vínculos). Ajustes justificados de pruebas existentes: `XlsxWriterTest` (ancho de una
columna numérica: 18 → 10, por el nuevo cálculo) y `XlsxSecurityTest` (nombres permitidos: `_xlnm._FilterDatabase`
y `_xlnm.Print_Titles`, con el contenido de este limitado a `'hoja'!$1:$1`).

**Rendimiento (local, mismo JVM, medianas de 15/15/7 renderizaciones tras calentamiento, 3 rondas).** Tiempo: dentro
del ruido de medición (10 000 filas: 166–179 ms antes y 170–188 ms después; 50 000 filas: 665–728 ms antes y
655–720 ms después). Memoria asignada por renderización: −22 % (58,5 → 45,5 MB con 10 000 filas; 285,1 → 216,9 MB
con 50 000). Tamaño: +0,34 % (10 000 filas) y +0,07 % (50 000); PA-A pequeño +741 bytes (+9 %), coste fijo de la
hoja de estilos.

**Límites.** Las celdas nulas no se escriben (EX-10, D-XL-1): no tienen borde y la cuadrícula de una tabla muestra
huecos. Al imprimir «Reporte», un texto largo del encabezado puede continuar en páginas adicionales. La altura de una fila de encabezado ajustada se
calcula al escribir (líneas × altura de línea del tema), sin depender del programa que abre el archivo.
