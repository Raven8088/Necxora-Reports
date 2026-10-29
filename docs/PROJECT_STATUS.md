# Nexora Reports — Estado del proyecto

> Fuente de verdad del estado de Reports. Diseño: [ARCHITECTURE.md](ARCHITECTURE.md). Contrato: [API.md](API.md).

| Campo | Valor |
|---|---|
| Última actualización | 20 oct 2026 |
| Versión del artefacto | `0.1.0-SNAPSHOT` |
| Etapa actual | **R1** — ejecutor seguro PostgreSQL: implementada y probada; **pendiente de revisión** |
| Próxima tarea autorizada | **Ninguna.** R2 y posteriores requieren autorización expresa |
| Contrato consumido | `nexora-logical-plan/1` (Semantic S4.2) |

Leyenda: **Implementado** = código presente · **Probado** = cubierto por pruebas automáticas que pasan ·
**Verificado manualmente** = comprobado además fuera de las pruebas · **Pendiente** = no iniciado o incompleto.

## Etapas

| Etapa | Estado |
|---|---|
| R1 — Ejecutor mínimo seguro | **Implementada y probada**; pendiente de revisión y de ejecutar las pruebas en el equipo del propietario con `postgres:17.6` |
| R2 y posteriores (Excel, etc.) | **Bloqueadas** sin autorización |

## Funcionalidades

| Funcionalidad | Implementado | Probado | Verificado manualmente |
|---|---|---|---|
| `POST /api/v1/data-sources/{id}/plans/{planId}/executions` | Sí | Sí | Arranque del JAR, 401 sin token |
| JWT: emisor obligatorio, audiencia `nexora-reports`, `tenant`/`principal_type` obligatorios | Sí | Sí | — |
| Permiso `reports:execute` (rol `REPORT_RUNNER`); IA sin permiso | Sí | Sí | — |
| Cuenta de servicio `REPORTS_CLIENT` por tenant, verificación del claim `tenant` del token de servicio | Sí | Sí (OIDC local) | No contra Keycloak real |
| Recuperación de fuente, plan y vigencia desde Semantic | Sí | Sí (Semantic simulado con documentos reales) | No contra Semantic en ejecución |
| Lectura estricta del contrato y validación completa | Sí | Sí | — |
| Política de vigencia (antes y después de la consulta) | Sí | Sí | — |
| Traducción SQL parametrizada (SCAN, JOIN, WHERE, GROUP BY, COUNT/SUM/AVG, HAVING, buckets temporales) | Sí | Sí | — |
| Ejecución de solo lectura con verificación del esquema vivo y de la PK de los JOIN | Sí | Sí (PostgreSQL real) | — |
| Límites de filas, tiempo, bloqueos y concurrencia | Sí | Sí | — |
| Política de destinos (SSRF) y credenciales por configuración | Sí | Sí | — |
| Auditoría append-only por tenant | Sí | Sí | Privilegios del rol verificados en arranque manual |
| Docker local (auditoría + base sintética) | Sí | — | Script de inicialización ejecutado con una imagen sustituta |

## Pruebas y última verificación

| Clase | Tipo | Pruebas |
|---|---|---|
| `NaturalKeyTest` | Unitaria | 5 |
| `PlanDocumentReaderTest` | Unitaria (documentos reales de Semantic) | 13 |
| `PlanValidatorTest` | Unitaria | 27 |
| `QueryTranslatorTest` | Unitaria | 8 |
| `ValidityPolicyTest` | Unitaria | 4 |
| `ReportExecutionServiceTest` | Unitaria (puertos simulados) | 12 |
| `ConfiguredCredentialProviderTest` | Unitaria | 4 |
| `RequesterJwtConverterTest` | Unitaria | 5 |
| `ArchitectureTest` | Arquitectura (ArchUnit) | 4 |
| `PostgresReadOnlyExecutorIntegrationTest` | Integración (PostgreSQL, Testcontainers) | 23 |
| `ExecutionApiIntegrationTest` | Integración (Spring Boot completo, MockMvc, OIDC local, Semantic simulado, Flyway, PostgreSQL) | 19 |
| **Total** | | **124** |

**Última verificación** (20 oct 2026): `mvn clean verify` ⇒ **BUILD SUCCESS, 124 pruebas, 0 fallos, 0 errores,
0 omitidas.**

Condiciones de esa verificación (para no sobrestimarla):

- Se ejecutó en el entorno de trabajo de Claude, sin acceso a Maven Central ni a Docker Hub. Las dependencias salieron
  de la caché Maven local del propietario (las mismas versiones que usa Semantic).
- Testcontainers usó `-Dnexora.test.postgres-image=nexora-local/postgres:16`, una imagen construida localmente con
  PostgreSQL **16.15**, porque `postgres:17.6` no se podía descargar. La configuración por defecto del proyecto usa
  `postgres:17.6`. **Falta ejecutar `mvn clean verify` en el equipo del propietario con la imagen por defecto.**
- Se comprobó que las pruebas detectan errores: al cambiar deliberadamente `NE` por `IS DISTINCT FROM`, fallaron
  2 pruebas (traducción y semántica real); el cambio se revirtió.
- Arranque manual del JAR con `application.yml` y variables de entorno: health `UP`, Flyway V1 aplicada,
  `nexora_reports_app` con solo `SELECT` e `INSERT`, 401 en problema JSON sin token.

Cobertura de los puntos obligatorios:

| Punto | Dónde |
|---|---|
| Lectura estricta del contrato | `PlanDocumentReaderTest` |
| Traducción SQL, parámetros tipados, identificadores con comillas y caracteres especiales | `QueryTranslatorTest`, `NaturalKeyTest`, `identifiersWithQuotesAndSpacesAreQuotedCorrectly` |
| COUNT, SUM y AVG; WHERE y HAVING | `PostgresReadOnlyExecutorIntegrationTest` |
| JOIN simple y compuesto; LEFT e INNER | `compositeCrossSchemaInnerJoin…`, `leftJoinKeepsUnmatchedRows…`, `executesJoinsThroughTheApi` |
| Ambigüedad o inconsistencias del plan | `PlanValidatorTest` (19 pruebas de rechazo) |
| Dimensiones temporales (WEEK, zonas, `timestamp` con y sin zona, `date`) | `isoWeeksStartOnMondayInUtc`, `timestampWithTimeZoneIsConverted…`, `timestampWithoutTimeZoneAndDateColumns…`, `instantFilters…` |
| NULL, `IN`, `NE`, agregaciones sin filas | `neAndInFollowThreeValuedLogic`, `isNotNullAndANullGroupOfItsOwn`, `countOfAColumnIgnoresNulls`, `aggregationsWithoutRows`, `sumOfOnlyNullValuesIsNull…` |
| Rechazo de STALE y de operaciones desconocidas | `stalePlansAreRefused`, `anUnknownOperationIsRejected…`, `ValidityPolicyTest` |
| Aislamiento por tenant | `anotherTenantNeverReaches…`, `aPlanWhoseDocumentBelongsToAnotherTenantIsRefused`, `ConfiguredCredentialProviderTest` |
| Falta de permisos y tokens inválidos | `theExecutePermissionIsRequired…`, `aValidTokenIsRequired`, `RequesterJwtConverterTest` |
| Errores de conexión, timeout, límite de filas | `connectionErrors…`, `aLockedTableHitsTheLockTimeout`, `aSlowStatementHitsTheStatementTimeout`, `theRowLimit…`, `concurrentExecutionsPerSourceAreBounded` |
| Inyección SQL | `injectionAttemptsInValuesAreJustData`, `identifiersAreQuotedExactly…`, `requestsCannotCarrySql…`, `malformedNaturalKeysAreRejected` |
| Esquema físico incompatible | `aSchemaThatNoLongerMatchesThePlan…`, `aJoinWhoseTargetIsNotThePrimaryKey…`, `aLiveSchemaThatDiffers…` |
| Roles que no son de solo lectura | `rolesThatAreNotStrictlyReadOnlyAreRefused` |

Los planes de prueba son documentos **generados por el código real de Semantic** sobre su catálogo sintético
(`src/test/resources/semantic/README.md`), sin modificar el repositorio de Semantic.

## Contradicciones detectadas

| Tema | Contexto o documentación | Código | Decisión en R1 |
|---|---|---|---|
| Formato de las claves naturales | `ARCHITECTURE.md` de Semantic §3.1: `sales.orders` | `NaturalKey.java`: `"sales"."orders"` | Se sigue el código |
| Sobre `discovery-api/0.1` | El contexto dice que la integración lo usa | `PROJECT_STATUS.md` de Semantic (P6): Discovery no lo conoce | Fuera del alcance de Reports; se informa |
| Configuración de Keycloak para Reports | — | El realm de desarrollo no tiene audiencia `nexora-reports` ni rol `REPORT_RUNNER` | Se documenta como paso manual; no se modificó Semantic |

## Riesgos y limitaciones abiertos

| Id | Riesgo | Mitigación en R1 | Estado |
|---|---|---|---|
| RR1 | Condición de carrera entre vigencia y ejecución | Vigencia antes y después; verificación del esquema vivo en la misma transacción | Reducido, no eliminado |
| RR2 | Semantic valida contra su catálogo importado, no contra la base viva (cambios no reimportados) | Verificación de relaciones, columnas, categorías de tipo y PK de los JOIN | Reducido. No detecta cambios en definiciones de vistas, intercalaciones ni privilegios por columna |
| RR3 | La huella de Semantic no es una firma verificable | Opaca: solo igualdad y auditoría. Usar TLS entre servicios | Abierto (requiere decisión en Semantic) |
| RR4 | La auditoría de Semantic solo ve la cuenta de servicio | Reports audita al usuario final; correlación por plan e instante | Abierto (propagación de identidad, p. ej. *token exchange*) |
| RR5 | Credenciales en configuración y en memoria | Variables de entorno, sin secretos en el repositorio, puerto sustituible | Abierto (gestor de secretos) |
| RR6 | Keycloak sin audiencia ni rol de Reports | Pasos manuales en el README | Abierto (operación) |
| RR7 | Resultados no reproducibles en el tiempo (datos no versionados) | Documentado | Aceptado |
| RR8 | La base de zonas horarias de Java y la de PostgreSQL pueden diferir en zonas raras | Validación IANA en Java; PostgreSQL falla de forma controlada | Aceptado |
| RR9 | `SUM` de columnas `float4/float8` es inexacto (aritmética binaria) | Se devuelve tal cual lo calcula PostgreSQL | Aceptado; documentado |
| RR10 | Orden de texto según la intercalación de la base | Documentado | Aceptado |
| RR11 | Las consultas al catálogo no bloquean: un DDL puede ocurrir entre la verificación y la lectura | `lock_timeout`, error controlado de PostgreSQL | Aceptado |
| RR12 | Relaciones legítimas rechazadas por falta de evidencia UNIQUE (caso `equipment.dbid`) | Heredado de Semantic/Discovery (R18 de Semantic) | Abierto fuera de Reports |
| RR13 | Sin monedas ni unidades: Reports puede sumar importes de monedas distintas si el plan lo pide | Heredado de Semantic (R25) | Abierto fuera de Reports |
| RR14 | Pools de conexión por fuente sin expiración en caliente; un cambio de credenciales exige reiniciar | Documentado | Aceptado en R1 |

## Validación de aceptación (20 oct 2026)

Ejecutada en el entorno de trabajo de Claude, desechable, **no en el equipo del propietario**. Herramienta y pasos:
[validation/README.md](../validation/README.md).

| Componente | Versión usada |
|---|---|
| PostgreSQL | **17.6**, binarios `theseus-rs/postgresql-binaries` 17.6.0 compilados desde las fuentes oficiales (no es la imagen Docker oficial, que no se podía descargar) |
| Keycloak | **26.4.0**, distribución oficial (la versión del `docker-compose` de Semantic), con el realm `nexora-realm.json` de Semantic sin modificar |
| Semantic | JAR compilado desde el código del repositorio, sin cambios, perfil `dev`, Flyway V1–V8 |
| Discovery | JAR de `Nexora Discovery/target` del propietario, sin cambios, con argumentos de destino permitido |
| Reports | `0.1.0-SNAPSHOT` de este repositorio |
| Datos | Base sintética `synthetic_erp` (`docker/postgres/init`) |

Resultados:

- `mvn clean verify` con la imagen por defecto `postgres:17.6` (construida localmente con esos binarios 17.6):
  **BUILD SUCCESS, 124 pruebas, 0 fallos, 0 errores, 0 omitidas.**
- `Invoke-R1Validation.ps1` (ejecución `20261021014012`): **24/24 comprobaciones PASS**:
  - 401 sin token y con la audiencia de Semantic; 403 sin `REPORT_RUNNER` y para un principal `AI` con el rol;
  - tenant del JWT igual al de la auditoría;
  - la cuenta `REPORTS_CLIENT` lee el plan y su vigencia; el plan pertenece al tenant y a la fuente;
  - otro tenant: 409 sobre una fuente ajena y 404 sobre un plan ajeno;
  - 6 planes reales generados por Semantic con resultados idénticos a los esperados (agregaciones, WHERE/HAVING,
    JOIN compuesto INNER, LEFT, NE, IS_NOT_NULL, MONTH, WEEK);
  - 400 ante SQL, planes, tenants o conexiones en el cuerpo; SQL en la *query string* ignorado; el rol lector no
    puede escribir;
  - truncamiento con `maxRows`; 504 por `lock_timeout` (≈2 s) con recuperación posterior;
  - 409 ante un plan `STALE` real (relación desactivada en Semantic);
  - auditoría completa por tenant, sin datos de negocio y append-only.
- Sin secretos ni datos de negocio en el log de Reports.
- `-RevertKeycloakSetup` dejó el realm con sus objetos originales.

Observaciones (no bloqueantes, sin corregir porque no se autorizaron cambios):

| Id | Observación |
|---|---|
| OV1 | Un `POST` sin cuerpo pero con `Content-Type` distinto de JSON (p. ej. `Invoke-WebRequest`, que añade `application/x-www-form-urlencoded`) recibe `415`. `curl -X POST` o un cuerpo `{}` funcionan. Conviene documentarlo o aceptar cuerpos vacíos con cualquier tipo |
| OV2 | Hibernate Validator avisa (`HV000271`) de que `@Valid List<…>` está obsoleto en `SemanticProperties` y `EnterpriseSourcesProperties`; la validación funciona. Cambio cosmético recomendado: `List<@Valid …>` |
| OV3 | El emisor depende del host: Keycloak, Semantic y Reports deben usar el mismo (`localhost`) |
| OV4 | El realm de desarrollo necesita el rol `REPORT_RUNNER` y la audiencia `nexora-reports` (ya documentado; RR6) |

## Pendiente antes de aceptar R1

1. Repetir `mvn clean verify` y `validation/e2e/Invoke-R1Validation.ps1` en el equipo del propietario con Docker
   (imagen oficial `postgres:17.6` y su Keycloak), tras autorizar los objetos de validación de Keycloak.
2. Decidir sobre OV1 y OV2.
3. Revisión independiente del código y de las decisiones R1-D1…R1-D15.

## Qué falta para empezar R2

- Autorización expresa y alcance de R2 (exportación a Excel u otro).
- Cerrar los pendientes anteriores.
- Decidir si el Excel se genera a partir del mismo resultado (límite de filas actual) o requiere otro límite o
  ejecución asíncrona.
- Decisiones abiertas que afectan a la presentación: monedas y unidades (R25 de Semantic), etiquetas legibles de las
  columnas (hoy se usan las claves de las definiciones) y formato de fechas por zona.
- Opcional, según prioridades: gestor de secretos, propagación de identidad a Semantic, API de consulta de auditoría.
