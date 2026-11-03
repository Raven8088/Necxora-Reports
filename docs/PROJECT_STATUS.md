# Nexora Reports — Estado del proyecto

> Fuente de verdad del estado de Reports. Diseño: [ARCHITECTURE.md](ARCHITECTURE.md). Contrato: [API.md](API.md).

| Campo | Valor |
|---|---|
| Última actualización | 2 nov 2026 (A4-B10-c.2 **verificado en Windows** dentro de su alcance: 792 + 26 pruebas, 0 fallos, BUILD SUCCESS; R4-6 sigue abierto (B11); antes: B10-c.2: corrección mínima JOL/records aplicada (solo pruebas), pendiente de Windows; Windows tras corregir la estimación: 792 con 1 error de JOL; antes: corrección de `ResultSizeEstimate` aplicada (B10-c.2), pendiente de Windows: 792 + 26 pruebas esperadas; C2-1 en Windows antes de corregir: 10/19 fallos; antes: A4-B10-c.2 implementado, pendiente de Windows; R4-6 no completo: subestimación medida localmente; antes: A4-B10-c.1 con FIX1 **verificado en Windows**: 757 pruebas, 0 fallos, BUILD SUCCESS; antes: A4-B10-c.1-FIX1 aplicado, pendiente de Windows: 757 pruebas esperadas; A4-B10-c.1 en Windows: 755/756, 1 fallo, BUILD FAILURE; antes: A4-B10-c.1 implementado, pendiente de Windows: 756 pruebas esperadas; B10-O3 / DS-20 verificado en Windows: 730 pruebas, 0 fallos (DS-27); antes: B10-O3 / DS-20 implementado, pendiente de Windows: 730 pruebas esperadas; A4-B10-b verificado en Windows: 707 pruebas, 0 fallos; antes: implementado, pendiente de Windows; A4-B9 verificado en Windows según el usuario: 643 pruebas, 0 fallos) |
| Versión del artefacto | `0.1.0-SNAPSHOT` |
| Etapa actual | **A4** (propuesta v3), por bloques: **A4-B1** y **A4-B2** cerrados y verificados en Windows (240 pruebas, 1 nov 2026; B2 con la corrección A4-B2-FIX1); **A4-B3** y **A4-B4** cerrados y verificados en Windows (331 pruebas, 1 nov 2026); **A4-B5** cerrado y verificado en Windows (388 pruebas, 1 nov 2026); **A4-B6** cerrado y verificado en Windows (424 pruebas, 1 nov 2026); **A4-B7** (Excel genérico) cerrado y verificado en Windows (485 pruebas, 1 nov 2026, con A4-B7-FIX1) y aceptado en Excel; **A4-B8** (almacenamiento y recuperación de artefactos) cerrado y verificado en Windows (536 pruebas, 1 nov 2026). Base: R1, verificada en Windows (127 pruebas, 29 oct 2026); **A4-B9** (integración, API y seguridad) implementado y verificado en Windows según el usuario (643 pruebas, 2 nov 2026); **A4-B10-b** (capacidades, POST sin cuerpo y endurecimiento) implementado y **verificado en Windows** (707 pruebas, 2 nov 2026); **B10-O3 / DS-20** verificado en Windows (730 pruebas, 2 nov 2026); **A4-B10-c.1** (planes auténticos de Semantic) implementado, pendiente de Windows; **verificado en Windows** (757 pruebas, 2 nov 2026, con FIX1); **A4-B10-c.2** (memoria y temporales del volumen) verificado en Windows dentro de su alcance (792 + 26 pruebas, 2 nov 2026); R4-6 abierto |
| Próxima tarea autorizada | **Ninguna.** A4-B10-c.2 verificado en Windows dentro de su alcance; R4-6 abierto, a reevaluar en B11; B10-d, B11, A5 y A6 necesitan su propia «Procede». Antes: A4-B10-c.2 con la corrección de `ResultSizeEstimate` aplicada, pendiente de verificación integral en Windows; R4-6 no completo hasta entonces. Antes: A4-B10-c.2 implementado (solo pruebas), pendiente de Windows; la corrección de `ResultSizeEstimate` requiere nueva autorización. Antes: A4-B10-c.1 verificado en Windows (757/757, con FIX1); B10-c.2, B10-d, B11 y A5 necesitan su propia «Procede». Antes: A4-B10-c.1 con la corrección FIX1, pendiente de verificación en Windows (antes de FIX1: 755/756). Antes: A4-B10-c.1 implementado, pendiente de verificación en Windows. B10-c.2, B10-d, B11 y A5 necesitan su propia «Procede» (antes: B10-O3 / DS-20 implementado, pendiente de Windows; ya verificado: 730 pruebas) |
| Contrato consumido | `nexora-logical-plan/1` (Semantic S4.2) |

Leyenda: **Implementado** = código presente · **Probado** = cubierto por pruebas automáticas que pasan ·
**Verificado manualmente** = comprobado además fuera de las pruebas · **Pendiente** = no iniciado o incompleto.

## Etapas

| Etapa | Estado |
|---|---|
| R1 — Ejecutor mínimo seguro | **Implementada y probada**; pendiente de revisión y de ejecutar las pruebas en el equipo del propietario con `postgres:17.6` |
| R2 y posteriores (Excel, etc.) | **Bloqueadas** sin autorización |
| R1 — verificación en Windows (29 oct 2026) | `mvn clean verify`: **127 pruebas, 0 fallos, 0 errores, 0 omitidas**, Windows 11, `postgres:17.6` oficial. Ver «Verificación en Windows de R1» |
| A4 — Ejecución de planes de reporte y Excel (propuesta v3) | Por bloques, cada uno con autorización propia. **A4-B1** (Apache POI): verificado en Windows (130/130). **A4-B2** (lector y validador `/2`): 238/239 en la primera ejecución; tras A4-B2-FIX1, **verificado en Windows (240/240)**. **A4-B3** (traducción `/2` → SQL) y **A4-B4** (ejecución en una instantánea): **verificados en Windows (331/331)**. **A4-B5** (verificación de tenencia): **verificado en Windows (388/388)**. **A4-B6** (ensamblado de resultados): **verificado en Windows (424/424)**. **A4-B7** (Excel genérico con POI): **verificado en Windows (485/485)** tras A4-B7-FIX1 y aceptado en Excel. **A4-B8** (registro y artefactos): **verificado en Windows (536/536)**. A4-B9 y siguientes: sin autorizar **A4-B9** (API, seguridad e integración): implementado, pendiente de Windows. |

## Verificación en Windows de R1 (29 oct 2026)

- Fuente: `Claude outputs/fase1/resultados/20261029-233953/` (`resumen.txt`, `mvn-Nexora-Reports.log`), generada por
  `Test-NexoraFase1.ps1` en el equipo del propietario; `target/surefire-reports` coincide (127).
- Entorno: Windows 11, Java 21.0.6, Testcontainers 2.0.5 con la imagen oficial `postgres:17.6`.
- Resultado: 127 pruebas, 0 fallos, 0 errores, 0 omitidas. Son las 124 de la tabla de pruebas más 3 añadidas el
  27 oct 2026 (`RequesterJwtConverterTest` 5 → 6, `ExecutionApiIntegrationTest` 19 → 21), junto con cambios en
  `security/` (`ReportsAuthenticationToken`, `RequesterArgumentResolver`, `RequesterJwtConverter`), `api/`
  (`ExecutionController`, `ApiExceptionHandler`) y `execution/` (`ReportExecutionService`, `ExecutionException`).
  **Esos cambios del 27 oct no estaban documentados en este archivo**; su autorización y su descripción no se han
  localizado.
- Límite: las fechas de los archivos indican que el código no cambió entre el 27 y el 29 oct, pero el registro no
  incluye huellas del código.
- Sigue pendiente `validation/e2e/Invoke-R1Validation.ps1` en el equipo del propietario.

## A4-B1 — Apache POI (1 nov 2026)

Autorización: «PROCEDE A4-B1». Detalle y revisión de vulnerabilidades: [ARCHITECTURE.md](ARCHITECTURE.md) §14.

| Archivo | Cambio |
|---|---|
| `pom.xml` | Propiedad `poi.version` 5.4.0 y dependencia `poi-ooxml` |
| `src/test/java/com/nexora/reports/architecture/ArchitectureTest.java` | Nueva regla `onlyTheExcelAdapterUsesApachePoi` (4 → 5) |
| `src/test/java/com/nexora/reports/render/xlsx/PoiDependencyTest.java` | Nueva: versión resuelta 5.4.0; escritura SXSSF, lectura XSSF y liberación de temporales (2) |

Esperado en `mvn clean verify`: **130 pruebas** (127 + 3), 0 fallos, 0 omitidas. **Sin ejecutar todavía**: Maven
Central no es accesible desde el entorno de Claude. Ningún cambio en `src/main`: el comportamiento de R1 no cambia.

**Verificación en Windows (1 nov 2026, 13:30 hora local).** `mvn -B clean verify`: **BUILD SUCCESS, 130 pruebas,
0 fallos, 0 errores, 0 omitidas**; Java 21.0.6; Testcontainers con `postgres:17.6`; JAR generado. Registro:
`Claude outputs/a4-b1/mvn-verify.log`. Árbol de dependencias: `Claude outputs/a4-b1/dependency-tree.txt`.

- Versiones efectivas: poi, poi-ooxml y poi-ooxml-lite 5.4.0; xmlbeans 5.3.0; commons-compress 1.27.1; commons-lang3 3.20.0; commons-io 2.18.0; commons-codec 1.21.0; commons-collections4 4.4; commons-math3 3.6.1; SparseBitSet 1.3; curvesapi 1.08; log4j-api 2.25.5 (con log4j-to-slf4j 2.25.5; sin log4j-core). Coinciden con la revisión de [ARCHITECTURE.md](ARCHITECTURE.md) §14.
- B1-O1: El compilador avisa de que `PoiDependencyTest` usa una API obsoleta (`uses or overrides a deprecated API`); sin `-Xlint:deprecation` no se sabe cuál. La documentación actual de POI no marca `dispose()` como obsoleto. **Pendiente (B1-O1):** identificarla antes del adaptador Excel, porque la v3 se apoya en `dispose()` para liberar temporales.
- B1-O2: `commons-math3`, `SparseBitSet` y `curvesapi` siguen sin revisión de vulnerabilidades.

## A4-B2 — Lector y validador de `/2` (1 nov 2026)

Autorización: «PROCEDE A4-B2». Diseño: [ARCHITECTURE.md](ARCHITECTURE.md) §15. Ningún archivo existente modificado;
el flujo R1 no cambia. Transferencia al equipo: `Claude outputs/a4-b2/a4-b2-src.zip` (63 archivos; manifiesto `a4-b2-src.sha256`), extraídos con comprobación de huellas y sin sobrescribir nada.

| Archivos nuevos | Contenido |
|---|---|
| `src/main/java/com/nexora/reports/plan2/domain/` (15) | `CanonicalJson`, `ContractSchema`, `ContractSchemas`, `ContractRules`, `BasicAProfile`, `PlanV2Validator`, `PlanV2Parser`, `LogicalPlanV2`, `ReportPlanV1`, `ReportSpecView`, `ValidatedReportPlan`, `ReportsCeilings`, `PlanV2Violation`, `PlanV2Exception`, `Trees` |
| `src/main/java/com/nexora/reports/plan2/infrastructure/` (2) | `StrictJsonTreeReader` (Jackson 3), `ClasspathContractSchemas` |
| `src/main/resources/contracts/` (3) | Esquemas de `report-spec/1`, `logical-plan/2`, `report-plan/1` |
| `src/test/java/com/nexora/reports/plan2/` (6) | `ContractFixtures`, `CanonicalJsonTest` (4), `ContractSchemaTest` (6), `ContractExamplesTest` (36), `PlanV2ValidatorTest` (50), `StrictJsonTreeReaderTest` (13) |
| `src/test/resources/nexora-contracts/` (37) | Copia parcial de contratos (36) y `CONTRACTS.sha256` |

**Pruebas nuevas: 109** (32 son la verificación parametrizada de cada ejemplo del manifiesto). Esperado en
`mvn clean verify`: **239 pruebas**, 0 fallos, 0 omitidas, con las 127 de R1 y las 3 de B1 sin cambios. **Sin ejecutar
todavía**: Maven Central no es accesible desde el entorno de Claude.

Verificación previa en el entorno de Claude (prueba técnica, no sustituye a Windows): el dominio (Java puro) compila
con `javac -Xlint:all` sin avisos; un arnés temporal fuera del proyecto obtuvo los veredictos del manifiesto en los 32
ejemplos y superó 99 casos (los de `PlanV2ValidatorTest` y más); una prueba de mutación confirmó que el arnés detecta
reglas saboteadas. El lector Jackson y las pruebas JUnit solo se comprobaron en sus tipos, contra versiones simuladas.

- B2-O1: los tres esquemas también se copian a `src/main/resources/contracts` (desviación comunicada).
- B2-O2: **resuelta**: `StrictJsonTreeReader` y todas las pruebas JUnit nuevas pasan en Windows.

**Verificación en Windows (1 nov 2026, 14:01 hora local).** `Claude outputs/a4-b2/mvn-verify.log`: 239 pruebas, 238 superadas, **1 fallo**, BUILD
FAILURE. Las 109 nuevas pasan. Falla `ArchitectureTest.domainsDoNotDependOnOuterLayers`: `CanonicalJson` usaba
`java.security.MessageDigest`, y el patrón `..security..` de la regla también abarca `java.security` (B2-O3).

### A4-B2-FIX1 — SHA-256 fuera del dominio (1 nov 2026)

Autorización: «PROCEDE A4-B2-FIX1». `ArchitectureTest` no cambia. Transferencia: `Claude outputs/a4-b2-fix1/a4-b2-fix1-src.zip` (9 archivos; manifiesto `a4-b2-fix1-src.json` con las huellas anterior y posterior), aplicada comprobando que los
archivos modificados seguían como se entregaron.

| Archivo | Cambio |
|---|---|
| `plan2/domain/Sha256.java` (nuevo) | Puerto: SHA-256 de los bytes UTF-8 de un texto, en hexadecimal |
| `plan2/infrastructure/JdkSha256.java` (nuevo) | Implementación con `MessageDigest` del JDK (el código que estaba en `CanonicalJson`, sin cambios) |
| `plan2/domain/CanonicalJson.java` | Sin `java.security`; `sha256` y `fingerprintOf` reciben el puerto |
| `plan2/domain/PlanV2Validator.java` | El constructor recibe el puerto |
| `ContractFixtures`, `CanonicalJsonTest`, `ContractExamplesTest`, `PlanV2ValidatorTest` | Pasan `JdkSha256`; aserciones sin cambios |
| `plan2/infrastructure/JdkSha256Test.java` (nuevo) | Vectores publicados de SHA-256 (1 prueba) |

Esperado en `mvn clean verify`: **240 pruebas** (239 + 1), 0 fallos. Verificado antes en el entorno de Claude: el
dominio no depende de ningún paquete prohibido por las reglas (análisis `jdeps` de los `.class`), y las huellas de
los 33 documentos del contrato son idénticas a las de la implementación anterior.

**Verificación en Windows de A4-B2-FIX1 (1 nov 2026, 14:08 hora local).** `Claude outputs/a4-b2-fix1/mvn-verify.log`: `mvn -B clean verify`: **BUILD SUCCESS, 240 pruebas, 0 fallos, 0 errores, 0 omitidas**; `ArchitectureTest` 5/5; clases nuevas: `CanonicalJsonTest` 4, `ContractSchemaTest` 6, `ContractExamplesTest` 36, `PlanV2ValidatorTest` 50, `StrictJsonTreeReaderTest` 13, `JdkSha256Test` 1; Java 21.0.6; `postgres:17.6`; JAR generado.
**A4-B2 queda cerrado.** Observaciones abiertas: B2-O1 (copia de los esquemas en `src/main/resources/contracts`,
pendiente de aceptación) y B2-O3 (alcance del patrón `..security..` de `ArchitectureTest`, sin cambios).

## A4-B3 — Traducción de planes `/2` a SQL (1 nov 2026)

Autorización: «PROCEDE A4-B3». Diseño: [ARCHITECTURE.md](ARCHITECTURE.md) §16. Transferencia:
`Claude outputs/a4-b3/a4-b3.zip` (12 archivos nuevos; manifiesto `A4-B3-MANIFEST.sha256` dentro del zip), extraída
comprobando cada hash y sin sobrescribir ningún archivo.

- Nuevos en `src/main`: `translation2/domain/PlanV2Translator`, `SqlQueryV2`, `SchemaRequirementsV2`, `SqlTextGuard`.
- Nuevos en `src/test`: `plan2/domain/TestPlans`, `translation2/domain/PlanV2TranslatorTest` (41),
  `translation2/PlanV2TranslationIntegrationTest` (7) y la copia del banco sintético en
  `src/test/resources/nexora-testbench/reportes` (`sql/01-esquema.sql`, `sql/02-datos.sql`, `sql/03-rol-lector.sql`,
  `esperado/pa-a.json`, `BENCH.sha256`).
- Modificado: `architecture/ArchitectureTest.java`, +1 regla `theV2TranslatorDoesNotExecuteAnything` (las 5 existentes
  sin cambios). R1, `plan2` de `src/main`, recursos de contratos y `pom.xml` sin cambios (comprobado por hash).

**Verificado en el entorno de Claude (parcial).** Compilación sin avisos; 41/41 ejecuciones de `PlanV2TranslatorTest`
con un ejecutor propio; `jdeps` de `translation2/domain` sin dependencias prohibidas; SQL ejecutado en PostgreSQL 16.15
local como `nexora_bench_lector`: PA-A igual a `esperado/pa-a.json` (15, 1 y 35 filas), los dos planes de otra forma
iguales al SQL de referencia, truncado correcto. **No ejecutados aquí:** JUnit, Testcontainers ni JDBC (B3-O1).

**Esperado en `mvn clean verify`: 289 pruebas (240 + 49), 0 fallos**; `ArchitectureTest` 6/6. Observaciones: B3-O1
(integración pendiente de Windows) y B3-O2 (finales de línea de la copia del banco).

**Cerrado (1 nov 2026):** verificado en Windows junto con A4-B4, 331/331 (`Claude outputs/a4-b4/mvn-verify.log`; ver la sección A4-B4). `PlanV2TranslatorTest` 41/41 y `PlanV2TranslationIntegrationTest` 7/7 en `postgres:17.6`. B3-O1 resuelta; B3-O2 no se manifestó.

## A4-B4 — Ejecución de planes `/2` en una instantánea (1 nov 2026)

Autorización: «PROCEDE A4-B4», con D-B4-1…6. Diseño: [ARCHITECTURE.md](ARCHITECTURE.md) §17. «A4-B4» es el bloque 4 de
A4, no la etapa futura «B4» de seguridad por filas.

Transferencia: `Claude outputs/a4-b4/a4-b4.zip` (manifiesto `A4-B4-MANIFEST.json`), aplicada comprobando el hash de
cada archivo nuevo y el hash previo de cada modificado. Después se comprobó por hash que el resto del proyecto (R1,
B1, B2, B3, `pom.xml`) no cambió.

- Nuevos en `src/main` (10): `execution/domain/{RunBudget, OutputValueType, ResultSizeEstimate, DatasetRows,
  SnapshotRead, ExecutionRequestV2}`, `execution/application/port/{PlanV2Executor, TenantScopeVerifier,
  CatalogReader}` y `execution/infrastructure/PlanV2SnapshotReader`.
- Nuevos en `src/test` (4): `support/BenchDatabase`, `execution/infrastructure/ControlledTenantScope`,
  `execution/infrastructure/PlanV2ExecutorIntegrationTest` (31) y `execution/domain/PlanV2ExecutionModelTest` (10).
- Modificados (6):
  - `PostgresReadOnlyExecutor`: implementa también `PlanV2Executor`; cuatro auxiliares pasan de `private` a paquete;
    `execute` sin cambios.
  - `SchemaVerifier`: variante nueva para `/2`; la de R1 sin cambios.
  - `ExecutionException`: +2 códigos.
  - `ApiExceptionHandler`: +2 mapeos (impedimento B4-O2, aprobado).
  - `ArchitectureTest`: +1 regla; las 6 existentes sin cambios.
  - `docs/API.md`.

**Resultados locales (entorno de Claude; no son de Windows).** Compilación sin avisos; `PlanV2ExecutionModelTest`
10/10 y `PlanV2TranslatorTest` 41/41 con un ejecutor propio; `jdeps` sin dependencias prohibidas; 28/28 escenarios de
SQL y catálogo en PostgreSQL 16.15 local (instantánea, R4-5, truncado, tipos, valores no representables, R4-9, rol
con escritura, 55P03 y 57014). **No ejecutados aquí:** JUnit, Testcontainers ni JDBC (B4-O1).

**Esperado en `mvn clean verify`: 331 pruebas (289 de B3 + 42), 0 fallos**; `ArchitectureTest` 7/7. A4-B3 sigue
pendiente de Windows; A4-B4 depende de él.

**Verificación en Windows de A4-B3 y A4-B4 (1 nov 2026, 19:04 hora local).** `Claude outputs/a4-b4/mvn-verify.log` (SHA-256 `4816576b…c420`): `mvn -B clean verify`: **BUILD SUCCESS, 331 pruebas, 0 fallos, 0 errores, 0 omitidas**; 22 clases de prueba; ningún `[ERROR]` ni `[WARNING]`; `ArchitectureTest` 7/7, `PlanV2TranslatorTest` 41/41, `PlanV2TranslationIntegrationTest` 7/7, `PlanV2ExecutionModelTest` 10/10, `PlanV2ExecutorIntegrationTest` 31/31; las de R1 (incluida `PostgresReadOnlyExecutorIntegrationTest` 23/23), B1 y B2 siguen en verde; Java 21.0.6; `postgres:17.6` (Testcontainers); JAR generado.
**A4-B3 y A4-B4 quedan cerrados.** B3-O1 y B4-O1 resueltas; B3-O2 no se manifestó; B4-O3 (sin verificador de tenencia en producción) se resuelve en A4-B5.

## A4-B5 — Verificación de tenencia (1 nov 2026)

Autorización: «PROCEDE A4-B5» (autorización formal, 1 nov 2026 19:10), con D-B5-1…8 y siete precisiones técnicas.
Diseño: [ARCHITECTURE.md](ARCHITECTURE.md) §18. Solo `DEDICATED_DATABASE` y `DEDICATED_SCHEMAS`; `SHARED_ROWS` se
rechaza siempre. No es seguridad por filas.

Transferencia: `Claude outputs/a4-b5/a4-b5.zip` (30 archivos; manifiesto `A4-B5-MANIFEST.json`), aplicada comprobando
el hash de cada archivo nuevo y el hash previo de cada modificado. El resto del proyecto, comprobado por hash, no
cambió.

- Nuevos en `src/main` (17): paquete `tenancy` (dominio, aplicación e infraestructura) y
  `db/migration/V2__create_tenancy_registry.sql`.
- Nuevos en `src/test` (7): `support/TenantDatabase` (TLS real con `keytool`),
  `execution/infrastructure/ExecutorTestAccess`, `tenancy/InMemoryTenancyRegistry`, `TenancyRulesTest` (9),
  `TenancyRegistrationServiceTest` (10), `JdbcTenancyRegistryIntegrationTest` (8) y `TenantScopeIntegrationTest` (29).
- Modificados (6):
  - `ExecutionException`: `TENANT_SCOPE_UNVERIFIED`.
  - `ApiExceptionHandler`: 409 `tenant_scope_unverified`.
  - `docs/API.md`.
  - `DataSourceCredentials`: `sslRootCert` opcional; el constructor de R1 se conserva.
  - `ConnectionPools`: lo aplica solo si existe.
  - `ArchitectureTest`: +1 regla; las 7 existentes sin cambios.

**Resultados locales (entorno de Claude; no son de Windows).**

- Compilación sin avisos.
- 19/19 pruebas unitarias con un ejecutor propio.
- `jdeps` sin dependencias prohibidas; una sola implementación de `TenantScopeVerifier` en producción.
- El código real de la sonda contra PostgreSQL 16.15 local: 33/33 escenarios, con TLS simulado solo en ese arnés.
- La migración V2: 16/16 comprobaciones.
- La verificación local destapó y corrigió B5-O2: `pg_depend` no registra los objetos internos, así que ahora también
  se lee el árbol de consulta de las vistas.
- **No ejecutados aquí:** TLS real, JUnit, Testcontainers, Flyway ni JDBC (B5-O1).

**Esperado en `mvn clean verify`: 388 pruebas (331 + 57), 0 fallos**; `ArchitectureTest` 8/8. Observaciones: B5-O1 a
B5-O4 (A4).

**Verificación en Windows de A4-B5 (1 nov 2026, 19:39 hora local).** `Claude outputs/a4-b5/mvn-verify.log` (SHA-256 `bb57cd0e…8e83`): `mvn -B clean verify`: **BUILD SUCCESS, 388 pruebas, 0 fallos, 0 errores, 0 omitidas**; 26 clases de prueba; ningún `[ERROR]` ni `[WARNING]`; `ArchitectureTest` 8/8, `TenantScopeIntegrationTest` 29/29 (TLS real en `postgres:17.6`), `JdbcTenancyRegistryIntegrationTest` 8/8, `TenancyRegistrationServiceTest` 10/10, `TenancyRulesTest` 9/9; Flyway aplica V1 y V2 («Successfully applied 2 migrations»), tanto en la prueba del registro como en `ExecutionApiIntegrationTest` 21/21 de R1; R1, B1, B2, B3 y B4 siguen en verde; Java 21.0.6; JAR generado.
**A4-B5 queda cerrado.** Siguen vigentes: B5-O2 (revisar el análisis del árbol de consulta al cambiar de versión mayor de PostgreSQL), B5-O3 (`sslRootCert` configurable, en B9) y B5-O4 (garantías no absolutas de identidad).

## A4-B6 — Ensamblado genérico de resultados (1 nov 2026)

Autorización: «PROCEDE A4-B6» (1 nov 2026 19:48), con D-B6-1…8 y diez precisiones. Diseño:
[ARCHITECTURE.md](ARCHITECTURE.md) §19. Transferencia: `Claude outputs/a4-b6/a4-b6.zip` (manifiesto
`A4-B6-MANIFEST.json`), aplicada comprobando cada hash. Solo cambia `ArchitectureTest`; el resto, comprobado por hash,
no cambia.

- Nuevos en `src/main` (21): `result/domain`.
- Nuevos en `src/test` (6): `result/ReportFixtures` (segundo reporte, sellado y validado por B2),
  `result/ResultAssemblyIntegrationTest` (4) y `result/domain/{ResultInputs, CellTextTest (4), SpecPresentationTest
  (4), ResultAssemblerTest (23)}`.
- Modificado: `architecture/ArchitectureTest.java`, +1 regla; las 8 existentes sin cambios.

**Resultados locales (entorno de Claude; no son de Windows).**

- Compilación sin avisos.
- 31/31 pruebas unitarias con un ejecutor propio, sobre PA-A y el segundo reporte.
- `jdeps`: `result` solo depende del JDK y del dominio de B2, B3 y B4.
- Las consultas del segundo reporte, ejecutadas en PostgreSQL 16.15 local, dan los valores que espera la integración.
- **No ejecutados aquí:** JUnit, Testcontainers, JDBC ni ArchUnit (B6-O1).

**Esperado en `mvn clean verify`: 424 pruebas (388 + 36), 0 fallos**; `ArchitectureTest` 9/9. Observaciones: B6-O1 a
B6-O4 (A4).

**Verificación en Windows de A4-B6 (1 nov 2026, 20:03 hora local).** `Claude outputs/a4-b6/mvn-verify.log` (SHA-256 `6c18f5bb…d855`): `mvn -B clean verify`: **BUILD SUCCESS, 424 pruebas, 0 fallos, 0 errores, 0 omitidas**; 30 clases de prueba; ningún `[ERROR]` ni `[WARNING]`; `ResultAssemblerTest` 23/23, `CellTextTest` 4/4, `SpecPresentationTest` 4/4, `ResultAssemblyIntegrationTest` 4/4 (B2→B3→B4→B6 en `postgres:17.6`), `ArchitectureTest` 9/9; R1, B1, B2, B3, B4 y B5 siguen en verde; Java 21.0.6; JAR generado.
**A4-B6 queda cerrado.** Siguen vigentes: B6-O2 (B7 debe mantenerse dentro de la reserva de celdas o revisar la fórmula), B6-O3 (B7 traduce los códigos `Unavailable`; B9 aporta nombre de fuente y columnas excluidas) y B6-O4 (celdas comprobadas tras la lectura; coincidencia de nombre con el `ReportResult` de R1).

## A4-B7 — Excel genérico con Apache POI (1 nov 2026)

Autorización: «PROCEDE A4-B7» (2026-11-01), con D-B7-1…8 y doce precisiones. Diseño: [ARCHITECTURE.md](ARCHITECTURE.md) §20. Transferencia: `Claude outputs/a4-b7/a4-b7.zip` (30 archivos: 29 nuevos y 1 modificado; manifiesto `manifest.json`; SHA-256 del zip `00719223…aa9e3`), aplicada comprobando cada hash (los nuevos no existían; `ArchitectureTest` tenía el hash previo esperado). Los otros 248 archivos, comprobados por hash, no cambian.

- Nuevos en `src/main` (18): puerto `ReportRenderer`; `result/domain/{RenderedArtifact, RenderLimits, PresentationException}`; `render/layout` (11); `render/xlsx` (3).
- Nuevos en `src/test` (11): `render/{RenderFixtures, LayoutAccess}`, `render/layout/{LabelTest (6), SheetNamesTest (7), ValueFormatTest (10), LayoutBuilderTest (13)}`, `render/xlsx/{Workbooks, XlsxWriterTest (5), XlsxRendererTest (13), XlsxSecurityTest (3), XlsxAcceptanceIntegrationTest (3)}`.
- Modificado: `architecture/ArchitectureTest.java`, +1 regla (`thePresentationKnowsOnlyTheGenericResult`); las 9 existentes sin cambios. `PoiDependencyTest` y `ResultInputs` sin cambios.

**Resultados locales (entorno de Claude; no son de Windows).**

- Compilación con `-Xlint:all -Werror` contra los JAR reales de POI 5.4.0 (solo lectura, D-B7-8), sin avisos.
- 57/57 pruebas unitarias con un ejecutor propio (todas salvo la integración y ArchUnit).
- Aceptación local con PostgreSQL 16.15 (B2→B3→`psql`→B6→B7) y verificación independiente con openpyxl y SQL de referencia: 0 fallos. LibreOffice abre y exporta ambos archivos (tras B7-O1).
- `jdeps`: `render.layout` no usa POI; `render` no depende de plan, traducción, tenencia, credenciales, adaptadores de ejecución, API ni Spring.
- **No ejecutados aquí:** JUnit, Testcontainers, JDBC, ArchUnit ni Excel (B7-O2).

**Esperado en `mvn -B clean verify`: 485 pruebas (424 + 61), 0 fallos**; `ArchitectureTest` 10/10. Archivos de aceptación tras la ejecución: `Nexora-Reports\target\acceptance-xlsx\PA-A.xlsx` y `empresas.xlsx` (el siguiente `mvn clean` los borra). Observaciones: B7-O1 a B7-O5; B1-O1 resuelta; B6-O2 resuelta en B7 (A4). `docs/API.md` no cambia: B7 no añade endpoints ni códigos de error (los códigos `artifact_too_large`, `too_many_executions` y `render_failed` de `report-run/1` los expondrá B9).

**A4-B7 no está cerrado** hasta la verificación en Windows.

**Primera ejecución en Windows (1 nov 2026, 20:50 hora local): BUILD FAILURE** en `testCompile` (`Claude outputs/a4-b7/mvn-verify.log`, SHA-256 `0db5c2fa…d3e`): `XlsxWriterTest.java:[88,13] reference to assertThat is ambiguous`. **Corrección A4-B7-FIX1:** `XlsxWriterTest.java`, línea 88: `assertThat(sheet.getRow(2)).isNull()` → `assertThat((Object) sheet.getRow(2)).isNull()`, con un comentario. `XSSFRow` es a la vez `Iterable<Cell>` y `Comparable<XSSFRow>`, así que el compilador no puede elegir entre `assertThat(Iterable<? extends E>)` y `assertThat(T extends Comparable<? super T>)` de AssertJ. La conversión a `Object` selecciona la aserción de objeto; la comprobación sigue siendo la misma (la fila 2, vacía en la maqueta, no existe) y sigue fallando si la fila existe (comprobado). Único archivo modificado: `XlsxWriterTest.java` (`95cdf983…6db`). Esperado al repetir `mvn -B clean verify`: 485 pruebas, 0 fallos (B7-O6).

**Verificación en Windows de A4-B7 (1 nov 2026, 20:57 hora local).** `Claude outputs/a4-b7/mvn-verify-fix1.log` (SHA-256 `3b7debca…5a6c`): `mvn -B clean verify`: **BUILD SUCCESS, 485 pruebas, 0 fallos, 0 errores, 0 omitidas**; 38 clases de prueba; ningún `[ERROR]` ni `[WARNING]`; `ArchitectureTest` 10/10, `LabelTest` 6/6, `SheetNamesTest` 7/7, `ValueFormatTest` 10/10, `LayoutBuilderTest` 13/13, `XlsxWriterTest` 5/5, `XlsxRendererTest` 13/13, `XlsxSecurityTest` 3/3, `XlsxAcceptanceIntegrationTest` 3/3 (B2→B3→B4→B6→B7 en `postgres:17.6`), `PoiDependencyTest` 2/2; R1 y B1…B6 siguen en verde; Java 21.0.6; JAR generado (20:57:41 hora local).
**Aceptación funcional en Excel:** el usuario abrió en Microsoft Excel `PA-A.xlsx` y `empresas.xlsx` generados por esa ejecución: ambos se abren sin avisos de reparación ni contenido ilegible; estructura, resultados y ausencia de fórmulas comprobados. Archivos (en `Nexora-Reports/target/acceptance-xlsx`, copiados por Claude a `Claude outputs/a4-b7/acceptance-windows/` porque `mvn clean` los borra): `PA-A.xlsx` 8 251 bytes, SHA-256 `61cb1825…9425`; `empresas.xlsx` 7 036 bytes, `bea12a49…7dee`.
**A4-B7 queda cerrado** (incluida A4-B7-FIX1; el fallo de compilación de la primera ejecución queda resuelto). Siguen vigentes: B1-O2, B6-O3 (parte de B9), B6-O4, B7-O3 (temporales de POI y registro en Spring, B9), B7-O4 (frase de filtros sin etiqueta) y B7-O5 (`mvn clean` borra los archivos de aceptación).

## A4-B8 — Almacenamiento, registro y recuperación de artefactos (1 nov 2026)

Autorización: «PROCEDE A4-B8» (2026-11-01), con D-B8-1…8, los cuatro ajustes y la condición adicional. Diseño: [ARCHITECTURE.md](ARCHITECTURE.md) §21. Transferencia: `Claude outputs/a4-b8/a4-b8.zip` (36 archivos: 35 nuevos y 1 modificado; manifiesto `manifest.json`; SHA-256 del zip `c4fabc5c…2703`), aplicada comprobando cada hash (los nuevos no existían; `ArchitectureTest` tenía el hash de B7). Los otros 277 archivos, comprobados por hash, no cambian.

- Nuevos en `src/main` (27): migración `V3__create_report_runs.sql`; puertos `RunStore`, `RunReader`, `ArtifactReader`, `RunMaintenance`; `run/domain` (15); `run/infrastructure` (7).
- Nuevos en `src/test` (8): `support/RunRegistryDatabase`; `run/RunRegistryAcceptanceIntegrationTest` (2); `run/domain/{RunOutcomeTest (8), RunDomainTest (7)}`; `run/infrastructure/{RunJsonTest (3), RunSqlTest (4), ArtifactStagingTest (6), JdbcRunStoreIntegrationTest (20)}`.
- Modificado: `architecture/ArchitectureTest.java`, +1 regla (`theRunRegistryKnowsOnlyTheResultAndItsPorts`); las 10 existentes sin cambios. Ningún componente anterior se modifica.

**Resultados locales (entorno de Claude; no son de Windows).**

- Compilación con `-Xlint:all -Werror`, sin avisos.
- 28/28 pruebas unitarias con un ejecutor propio.
- Migraciones V1→V3 en PostgreSQL 16.15: 23/23 escenarios de base de datos como rol de aplicación (y triggers efectivos también para el superusuario); las 15 sentencias SQL con sus parámetros: 27/27 comprobaciones, incluidas la carrera entre reconciliación y final tardío y el bloqueo por tenant.
- `jdeps`: `run.domain` solo depende del JDK y de `result.domain`; `run.infrastructure`, además, de los puertos, `java.sql`, `javax.sql` y `java.nio.file`.
- **No ejecutados aquí:** adaptadores JDBC en Java, integración con Testcontainers, extremo a extremo y ArchUnit (B8-O1).

**Esperado en `mvn -B clean verify`: 536 pruebas (485 + 51), 0 fallos**; `ArchitectureTest` 11/11. Observaciones: B8-O1 a B8-O6 (A4), incluido el riesgo residual sin RLS (B8-O2). `docs/API.md` no cambia: B8 no añade endpoints ni códigos de error.

**A4-B8 no está cerrado** hasta la verificación en Windows.

**Verificación en Windows de A4-B8 (1 nov 2026, 21:43 hora local).** `Claude outputs/a4-b8/mvn-verify.log` (SHA-256 `083a7b0f…6a97`): `mvn -B clean verify`: **BUILD SUCCESS, 536 pruebas, 0 fallos, 0 errores, 0 omitidas**; 45 clases de prueba; ningún `[ERROR]` ni `[WARNING]`; `ArchitectureTest` 11/11, `JdbcRunStoreIntegrationTest` 20/20, `RunRegistryAcceptanceIntegrationTest` 2/2 (B2→B3→B4→B6→B7→B8), `RunOutcomeTest` 8/8, `RunDomainTest` 7/7, `RunJsonTest` 3/3, `RunSqlTest` 4/4, `ArtifactStagingTest` 6/6; Flyway aplicó V1→V3 en `postgres:17.6` («Successfully applied 3 migrations… now at version v3»); R1 y B1…B7 siguen en verde (`ExecutionApiIntegrationTest` 21/21); JAR generado (21:43:35 hora local).
**A4-B8 queda cerrado.** B8-O1 resuelta. Siguen vigentes: B8-O2 (riesgo residual sin RLS y sus medidas), B8-O3 (descargas simultáneas, B9), B8-O4 (estados internos FAILED/INTERRUPTED, exposición en B9), B8-O5 (preparación y programación, B9) y B8-O6 (`report-run/1` en JSON, B9).

## A4-B9 — Integración, API y seguridad (1 nov 2026)

Autorización: «Procede con la implementación de Nexora AI A4-B9» (2026-11-01), con D-B9-1…12, el scope opcional
`nexora-reports`, el rol `REPORTS_TENANCY_ADMIN`, el ajuste mínimo de `ContractSchema` y la migración V4. Diseño:
[ARCHITECTURE.md](ARCHITECTURE.md) §22. API: [API.md](API.md) «A4-B9». Transferencia: `Claude outputs/a4-b9/a4-b9.zip`
(58 archivos: 44 nuevos y 14 modificados; manifiesto `manifest.json`; SHA-256 del zip `f1135f85…babd`), aplicada con
`apply_b9.py` (todas las huellas comprobadas antes y después) y `unchanged.sha256` (286 archivos sin cambios).

- Nuevos en `src/main`: `api/{ReportRunController, TenancyController, TenancyDtos}`;
  `config/{ReportRunConfiguration, ReportRunProperties, StorageProperties, RunHousekeeping}`;
  `execution/application/{ReportRunService, ReportRunDocuments}`;
  `execution/application/port/{ReportPlanSource, TenantScopes, StagingArea, ArtifactDeliveries}`;
  `execution/domain/RunException`; `plan2/domain/{ReportPlanValidity, SemanticDataSource}`;
  `plan2/infrastructure/{SemanticReportPlanSource, RunContractSchemas}`; `render/xlsx/PoiTemporaryFiles`;
  `run/infrastructure/{JdbcArtifactDeliveries, StagingDirectory}`; `tenancy/infrastructure/RegisteredTenantScopes`;
  `contracts/report-run/1/` (2 esquemas); `db/migration/V4__record_artifact_deliveries.sql`.
- Nuevos en `src/test`: `api/{ReportRunApiIntegrationTest (10), ReportRunErrorMappingTest (20)}`;
  `architecture/NoClientSpecificNamesTest (1)`; `config/ReportRunSettingsTest (7)`;
  `execution/application/{ReportRunServiceTest (39), ReportRunDocumentsTest (17), InMemoryRunRegistry}`;
  `run/infrastructure/JdbcArtifactDeliveriesIntegrationTest (7)`; `support/TlsBench`; 7 ejemplos de
  `report-run/1`, sus 2 esquemas y `RUN-CONTRACTS.sha256`.
- Modificados (14): `ApiExceptionHandler`, `ReportsConfiguration` (el bean del ejecutor se expone con su tipo, para
  compartirlo con `/2`), `ConfiguredCredentialProvider` y `EnterpriseSourcesProperties` (`ssl-root-cert`),
  `ExecutionException` (10 códigos), `ContractSchema` (`format` como anotación), `Permission` y
  `RequesterJwtConverter` (permiso de tenencia solo para personas), `application.yml`; pruebas: `ArchitectureTest`
  (+2 reglas), `ConfiguredCredentialProviderTest` (+1), `ContractSchemaTest` (+1; el caso de palabra clave no
  admitida usa ahora `contentEncoding`), `RequesterJwtConverterTest` (+2), `XlsxWriterTest` (cuenta los temporales de
  POI en el directorio que Reports fije). R1 no cambia de comportamiento.

**Resultados locales (entorno de Claude; no son de Windows).**

- Código principal compilado contra sustitutos mínimos de Spring, Servlet, Jackson y slf4j y los JAR reales de POI.
- Unitarias con un ejecutor propio: `ReportRunServiceTest` 39/39, `ReportRunDocumentsTest` 17/17,
  `ReportRunErrorMappingTest` 20/20, `ReportRunSettingsTest` 7/7, `NoClientSpecificNamesTest` 1/1,
  `ContractSchemaTest` 7/7, `ConfiguredCredentialProviderTest` 5/5, `RequesterJwtConverterTest` 8/8. Regresión sin
  contenedores de B1…B8 en verde, salvo `StrictJsonTreeReaderTest`, que aquí corre con un lector sustituto sin
  Jackson (conocido desde B2).
- PostgreSQL 16.15: V1→V4 aplicadas; restricción `report_run_event_kind_check` sustituida; 18/18 comprobaciones de
  los eventos (nuevos y anteriores aceptados; desconocidos, detalle inválido y otro tenant rechazados; sin UPDATE ni
  DELETE). El banco con `public` cerrado da exactamente los dos esquemas que registra la prueba de la API.
- **No ejecutados aquí** (B9-O1): `ReportRunApiIntegrationTest` (Spring, JWT real, TLS real `verify-full`, tenencia por
  dos personas, Excel descargado y comprobado), `JdbcArtifactDeliveriesIntegrationTest`, ArchUnit y el arranque.

**Esperado en `mvn -B clean verify`: 643 pruebas (536 + 107), 0 fallos**; `ArchitectureTest` 13/13; Flyway hasta V4.

**A4-B9 no está cerrado**: se revisa con el usuario el resultado de Windows.

**Bloqueos de implementación:** ninguno. **Requisitos para desplegar y probar con Wellnode** (no bloquean el código):
TLS verificable en la base y su CA montada en Reports (`ssl-root-cert`); rol lector dedicado, no superusuario, con
USAGE exactamente en los esquemas registrados (incluido `public` si PUBLIC lo conserva); registro de tenencia aprobado
por dos personas; Semantic con A2, A3 y X-8 desplegados y una política de exposición APPROVED que cubra la tabla; los
cambios de Keycloak y el volumen de almacenamiento de `nexora-deploy/docs/CONFIGURATION.md` §9.

## A4-B10-b — Capacidades, cuerpo del POST y endurecimiento (2026-11-02)

Autorización: «PROCEDE exclusivamente con A4-B10-b» (2026-11-02), con D-B10-2, D-B10-3, D-B10-4, D-B10-5, D-B10-12 y D-B10-13. Diseño: [ARCHITECTURE.md](ARCHITECTURE.md) §23. API: [API.md](API.md) «A4-B10-b».
Transferencia: `Claude outputs/a4-b10b/a4-b10b.zip` (27 archivos: 18 nuevos y 9 modificados; manifiesto
`manifest.json`), aplicada con `apply_b10b.py` (todas las huellas comprobadas antes y después) y `unchanged.sha256`
(330 archivos sin cambios).

**Punto de partida:** A4-B9, verificado en Windows según el usuario (2026-11-02): `mvn -B clean verify` BUILD SUCCESS,
643 pruebas, 0 fallos, 0 errores, 0 omitidas; `ArchitectureTest` 13/13; Flyway hasta V4.

- Nuevos en `src/main`: `api/{CapabilitiesController, RequestBodies, RequestBodyNotAllowedException}`;
  `execution/domain/RedactedFailure`; `plan2/domain/{ReportsProfile, ReportsCapabilities}`;
  `contracts/capabilities/1/nexora-capabilities-1.schema.json` (copia del contrato).
- Modificados en `src/main` (7): `ApiExceptionHandler` (400 `malformed_request`; fallos inesperados sin mensaje),
  `ReportRunController` (POST sin cuerpo), `ReportRunConfiguration` (beans `ReportsCeilings` y `ReportsCapabilities`),
  `ReportRunService` (fallo interno sin mensaje en el log), `BasicAProfile` (constantes de `ReportsProfile`; como mucho
  2 JOIN por plan), `RunContractSchemas` (esquema de capacidades), `Permission` (`REQUIRE_TENANT_PRINCIPAL`).
- Pruebas nuevas: `plan2/domain/CapabilitiesConformanceTest` (31), `result/domain/CapabilitiesItemConformanceTest` (5),
  `api/RequestBodiesTest` (3), `execution/domain/RedactedFailureTest` (3), `api/ReportRunSafeguardsIntegrationTest`
  (17), `api/ReportRunLimitsIntegrationTest` (5); soporte `support/{RunApiSetup, RunProbes}`; modificados
  `support/TestPlatform` (respuestas en secuencia) y `support/TenantDatabase` (conexión de superusuario); recursos:
  esquema y ejemplo de `capabilities/1` y `CAPABILITIES-CONTRACT.sha256`.
- Pruebas T-1…T-8 de la auditoría: B10-b cubre **T-8**; T-5 solo en su parte independiente de Semantic. Adicionales
  (D-B10-12): T-9, T-10 y T-11.

**Resultados locales (entorno de Claude; no son de Windows).**

- Código principal compilado (`javac -Xlint:all`, sin avisos en los archivos de B10-b) contra sustitutos mínimos de
  Spring, Servlet, Jackson y slf4j y los JAR reales de POI; dependencias de paquetes comprobadas con `jdeps` frente a
  las reglas de `ArchitectureTest`.
- Todas las pruebas compiladas contra sustitutos de JUnit, AssertJ, Spring Test y Testcontainers. Con un ejecutor
  propio (incluidos los casos `@MethodSource`): **434/434** en 38 clases unitarias, entre ellas
  `CapabilitiesConformanceTest` 31/31, `CapabilitiesItemConformanceTest` 5/5, `RequestBodiesTest` 3/3,
  `RedactedFailureTest` 3/3, `PlanV2ValidatorTest` 50/50, `ContractExamplesTest` 36/36, `ReportRunServiceTest` 39/39.
  `StrictJsonTreeReaderTest` no cuenta: aquí corre con un lector sustituto sin Jackson (conocido desde B2).
- Los documentos de prueba de las clases de integración (PA-A realojado, con centinelas, truncado, 2 y 3 JOIN, dos
  secciones, presupuestos rebajados) se validaron y ensamblaron aquí con el validador y el ensamblador reales.
- **No ejecutados aquí** (B10-O1): `ReportRunSafeguardsIntegrationTest`, `ReportRunLimitsIntegrationTest`,
  `ArchitectureTest` y el arranque de Spring.

**Esperado en `mvn -B clean verify`: 707 pruebas (643 + 64), 0 fallos**; `ArchitectureTest` 13/13; Flyway hasta V4.

**A4-B10-b no está cerrado**: se revisa con el usuario el resultado de Windows.

**Verificación en Windows (2026-11-02).** Respaldado por Surefire (copia íntegra en `Claude outputs/a4-b10b/windows-2026-11-02/`, `SHA256SUMS.txt` `8d905fb14bae63b020563fc0db3ca6d7524d7f420b11078edc7ceb9b74dda89a`): 58 clases, **707 pruebas, 0 fallos, 0 errores, 0 omitidas**, Windows 11, Java 21.0.6; `ReportRunSafeguardsIntegrationTest` 17/17, `ReportRunLimitsIntegrationTest` 5/5, `CapabilitiesConformanceTest` 31/31, `CapabilitiesItemConformanceTest` 5/5, `RequestBodiesTest` 3/3, `RedactedFailureTest` 3/3, `ArchitectureTest` 13/13. Respaldado solo por la salida de Maven compartida por el usuario (no conservada): `BUILD SUCCESS`, 01:14 min, Docker Desktop y PostgreSQL 17.6 con Testcontainers. **A4-B10-b queda verificado técnicamente**; no es el cierre formal de A4. Siguen abiertas B10-O3/DS-20 (orientación: opción B, sin implementar), DS-19, DS-21 y B10-O4; informativas B10-O5 a B10-O8; B10-O9 es el incumplimiento de la prohibición de ejecutar Git (ver `Documentacion/Maestros/anexos/A4_Etapa_A4.md`).

## B10-O3 / DS-20 — Destino sin secreto y credenciales antes de leer (2026-11-02)

Autorización: «NEXORA AI — B10-O3 / DS-20, FASE 2 — PROCEDE con la implementación aislada» (2026-11-02), con D-O3-1 a D-O3-5. Diseño: [ARCHITECTURE.md](ARCHITECTURE.md) §24. API: [API.md](API.md) «B10-O3».
Transferencia: `Claude outputs/a4-b10o3/a4-b10o3.zip`, aplicada con `apply_b10o3.py` y `unchanged.sha256`.

- Nuevos en `src/main`: `credentials/domain/SourceTarget`, `execution/application/port/SourceAvailability`.
- Modificados en `src/main` (7): `DataSourceCredentials` (`target()`), `ConfiguredCredentialProvider` (implementa
  `SourceAvailability`), `ReportRunService` (paso 2 sin secreto; credenciales solo antes de leer; comparación de
  destino), `ExecutionException` (`DATA_SOURCE_CONFIGURATION_CHANGED`), `ApiExceptionHandler` (su mapeo, 409),
  `ReportsConfiguration` (un solo bean para los dos puertos), `ReportRunConfiguration` (inyección del puerto).
- Pruebas: nuevas `credentials/domain/SourceTargetTest` (4) y `support/BenchSessions`; ampliadas
  `ReportRunServiceTest` (+14), `ConfiguredCredentialProviderTest` (+1), `ReportRunErrorMappingTest` (+3) y
  `ReportRunSafeguardsIntegrationTest` (+1, `t9OnlyARunThatReadsOpensAPhysicalConnectionToTheBench`; la prueba STALE
  ahora exige 0 credenciales resueltas); `support/RunProbes` cuenta `configured` y `resolve` por separado.

**Resultados locales (entorno de Claude; no son de Windows).** Código principal y pruebas compilados con
`javac -Xlint:all` contra sustitutos (sin avisos nuevos); ejecutor propio: **456/456** en 39 clases unitarias
(434 anteriores + 22), entre ellas `ReportRunServiceTest` 53/53, `SourceTargetTest` 4/4,
`ConfiguredCredentialProviderTest` 6/6, `ReportRunErrorMappingTest` 23/23. Dos mutaciones manuales de
`ReportRunService` (sin comparar el destino; resolver en el paso 2) hacen fallar 8 y 10 pruebas. **No ejecutados
aquí:** las pruebas de integración (incluida la de sesiones físicas), `ArchitectureTest` y el arranque de Spring.

**Esperado en `mvn -B clean verify`: 730 pruebas (707 + 23), 0 fallos.** La prueba de sesiones físicas espera
estabilidad del contador (≥ 3 × 12 s).

**Limitaciones:** los cambios de configuración entre el inicio y la lectura solo se prueban con dobles; la
contraseña no forma parte del destino; B10-O10 (pools reutilizados tras una rotación, relacionado con RR14) queda
abierto.

**Verificación en Windows (2026-11-02; anotada en B10-c.1, DS-27).** Respaldado por el log de Maven conservado (`mvn-verify-b10o3.log`, SHA-256 `2e0882bf5dc8adebe18e31d9d1718f6beb9f6f3587aa1fc65f8a61e5382d3f81`): **730 pruebas, 0 fallos, 0 errores, 0 omitidas**, `BUILD SUCCESS`, 01:49 min, PostgreSQL 17.6; respaldado por Surefire (119 archivos, 59 clases, Windows 11, Java 21.0.6) en `Claude outputs/a4-b10o3/windows-2026-11-02/`. `ReportRunSafeguardsIntegrationTest` 18/18 (la prueba de sesiones físicas pasó en 37,039 s, con control positivo), `ReportRunServiceTest` 53/53, `SourceTargetTest` 4/4, `ArchitectureTest` 13/13. B10-O3 y DS-20 **resueltas para el alcance comprobado**. Límites que se mantienen: el TOCTOU se reduce, no desaparece; los cambios de configuración entre el inicio y la lectura solo se prueban con dobles; **B10-O10 sigue abierta**.

## A4-B10-c.1 — Reports ejecuta los planes auténticos de Semantic (2026-11-02)

Autorización: «NEXORA AI — A4-B10-c.1 FASE 2 — IMPLEMENTACIÓN AUTORIZADA» (2026-11-02), conforme a la propuesta técnica de FASE 1. Diseño: [ARCHITECTURE.md](ARCHITECTURE.md)
§25. Sin cambios en la API ni en `src/main`.

Estado: **implementado, pendiente de verificación en Windows.** **Actualización (2026-11-02): verificado en Windows, 757/757 con FIX1; ver más abajo.** Evidencia de **nivel B**: Reports ejecuta, por su API
y su motor genérico, los planes auténticos que Semantic exportó en B10-a, con el transporte de Semantic **simulado**
(`TestPlatform` sirve los bytes archivados). No es una integración viva entre Semantic y Reports, ni prueba el
lenguaje natural, ni satisface por completo PR-2 y PR-3.

- Recursos: `src/test/resources/semantic-b10a/` (54 archivos, copia byte a byte de
  `Claude outputs/a4-b10a/windows-b10a-2026-11-02/`), fijados por `semantic-b10a.sha256` (`manifest.json`
  `6e49f545…23ea`, `SHA256SUMS` `c5526b07…1b1b`); `.gitattributes` los declara `-text`. Ningún documento se regeneró
  ni se volvió a sellar.
- Referencias independientes: `nexora-testbench/reportes/referencia-b10c/` (14 SQL escritos a mano desde la
  especificación auténtica, `casos.json`, `README.md`). No se copió el SQL de Reports.
- Soporte de pruebas: `support/AuthenticPlans`, `support/AuthenticWorkbook`, `support/BenchReference`;
  `render/xlsx/Workbooks` pasa a público (solo pruebas).
- Pruebas nuevas: `plan2/AuthenticPlansIntegrityTest` (5), `plan2/domain/SemanticCapabilitiesCompatibilityTest` (4),
  `api/AuthenticPlanRunIntegrationTest` (16, siete casos parametrizados), `api/AuthenticPlanStaleIntegrationTest` (1).
  Evidencia generada en `target/b10c1-evidence/`.

**Resultados locales (entorno de Claude; no son de Windows).** Compilación con `javac -Xlint:all` contra sustitutos,
sin avisos nuevos; ejecutor propio **465/465** pruebas unitarias (456 + 9 nuevas). Arnés fuera del repositorio con el
validador, traductor, ensamblador y renderer reales de Reports sobre PostgreSQL 16 local: los siete casos CURRENT
coinciden celda a celda con el SQL de referencia (7/7). **No ejecutados aquí:** las pruebas de integración (Spring,
Testcontainers, TLS) ni `ArchitectureTest`.

**Esperado en `mvn -B clean verify`: 756 pruebas (730 + 26), 0 fallos.**

**Ejecución en Windows (2026-11-02, antes de FIX1; resultado histórico, se conserva).** Log de Maven conservado en
`Nexora-Reports/mvn-verify-b10c1.log` (SHA-256 `259aa9c4ac3e7b5788a1c46f013cbadb622767d28434c05f24bfe19d3190cb50`):
`Tests run: 756, Failures: 1, Errors: 0, Skipped: 0`, **BUILD FAILURE**, 02:24 min, terminado
2026-11-02T09:29:04-05:00, `postgres:17.6`. `AuthenticPlanRunIntegrationTest` 15/16 (los siete casos CURRENT y el
resto de escenarios pasaron; evidencia en `target/b10c1-evidence/`, `SHA256SUMS`
`354cefc35dd40acdce3c77d230e45f7f53b8ba820e6886601b1552fbdb3b54dd`); `AuthenticPlanStaleIntegrationTest` 1/1.
Único fallo: `anotherTenantNeitherReachesTheSourceNorUsesTheseDocuments` (línea 311): se esperaba 201 y Reports
respondió `502 semantic_contract_violation` («data source id differs»). Causa (B10c1-O6): la prueba servía para la
fuente de otro tenant el descriptor auténtico de la fuente de la exportación; Reports rechaza esa respuesta incoherente
en el paso 3, antes de iniciar la ejecución, como documenta API.md. Error de preparación del escenario y de su
expectativa, no del código de producción ni de los documentos auténticos. La parte de invisibilidad (404) no llegó a
ejecutarse.

**A4-B10-c.1-FIX1 («NEXORA AI — B10-c.1 — Corrección del fallo de aislamiento», opción A (2026-11-02)).** Solo pruebas:
- A1, `aSemanticDescribingAnotherSourceIsAContractViolationAndNothingStarts` (nueva): el descriptor auténtico de otra
  fuente da `502 semantic_contract_violation`, sin ejecución creada, sin credenciales, sin lecturas y sin ninguna
  consulta a Semantic posterior al descriptor.
- A2, `anotherTenantNeitherReachesTheSourceNorUsesTheseDocuments` (corregida): un descriptor **sintético**, creado en la
  prueba, describe la fuente propia de `OTHER_TENANT`; después se sirven byte a byte los documentos auténticos de
  `pa-a` (`AuthenticPlans.serveDocuments`, nuevo). Se espera `201` REFUSED `identity_mismatch`, sin credenciales ni
  lecturas, y 404 de ejecuciones y artefactos entre tenants y propietarios.
- Ningún documento auténtico, huella ni pin cambia; `src/main`, contratos y migraciones intactos.

Resultados locales de FIX1 (entorno de Claude, no Windows): las dos clases compilan sin avisos; 465/465 unitarias;
comprobación aparte: el validador real rechaza `pa-a` bajo `OTHER_TENANT/OTHER_SOURCE` con `IDENTITY_MISMATCH` y el
descriptor sintético es ejecutable y coherente. Las pruebas de integración no se ejecutan aquí.

**Esperado tras FIX1 en `mvn -B clean verify`: 757 pruebas (756 + 1), 0 fallos**; `AuthenticPlanRunIntegrationTest`
17/17.

**Verificación en Windows (2026-11-02, con FIX1; «NEXORA AI — A4-B10-c.1 — Cierre documental» (2026-11-02)).** Respaldado por el log de Maven conservado (`mvn-verify-b10c1-fix1.log`, SHA-256 `3bdc9620eca4d9378e5ced65a1ab1ed96aeb0d248f88e4ad26225bea733a9f7c`, igual en `Nexora-Reports/` y en la copia archivada): **757 pruebas, 0 fallos, 0 errores, 0 omitidas**, `BUILD SUCCESS`, 02:26 min, terminado 2026-11-02T09:48:02-05:00, `postgres:17.6`, Java 21.0.6. Respaldado por Surefire (63 clases, 757/0/0/0, Windows 11): `AuthenticPlanRunIntegrationTest` 17/17 (incluidas A1 y A2 de FIX1), `AuthenticPlanStaleIntegrationTest` 1/1 (STALE `plan_stale` sin credenciales, lecturas ni sesiones físicas; el control `pa-a` terminó COMPLETED), `AuthenticPlansIntegrityTest` 5/5, `SemanticCapabilitiesCompatibilityTest` 4/4, `ArchitectureTest` 13/13, `NoClientSpecificNamesTest` 1/1. Evidencia en `Claude outputs/a4-b10c1/windows-2026-11-02-fix1/` (`SHA256SUMS.txt` `104612234f2fb3a85caf94676138e023d7d2e65e5e4d25c04dda919b77520c13`, `RESUMEN.md`, `resumen-por-clase.csv`): siete `.xlsx` y sus `report-run` (huellas coherentes; seis COMPLETED y `volumen` PARTIAL con `dataset_truncated`), `resumen.json` y `b10c1-evidence/SHA256SUMS` íntegro. El código ejecutado es el transferido (84 huellas «after» de B10-c.1 y FIX1; `src/main` sin cambios). **A4-B10-c.1 queda verificado en Windows dentro de su alcance**: evidencia de nivel B (planes auténticos de B10-a con el transporte de Semantic simulado). No es integración viva Semantic–Reports, ni lenguaje natural, ni cumplimiento íntegro de PR-2 y PR-3. Siguen abiertas B10-O10, DS-21, B10-O4, B10c1-O1 y B10c1-O4. El resultado anterior (755/756, BUILD FAILURE) se conserva arriba.

Observaciones: B10c1-O1 (la cabecera de un filtro NUMBER muestra el literal de la pregunta, `300.000`; el plan lleva
el canónico `300`); el control de `AuthenticPlanStaleIntegrationTest` puede ser COMPLETED o REFUSED
`tenant_scope_unverified` según el orden de clases (en ambos se abre conexión); las limitaciones de procedencia de
B10-a (B10a-O2, B10a-O3) se mantienen. B10-O10 sigue abierta. Detalle en
`Documentacion/Maestros/anexos/A4_Etapa_A4.md`.

## A4-B10-c.2 — Memoria y temporales del volumen (R4-6) (2026-11-02)

Autorización: «NEXORA AI — A4-B10-c.2 — Autorización de implementación» (2026-11-02), con D-C2-1, D-C2-1b, D-C2-2 y D-C2-3. Diseño: [ARCHITECTURE.md](ARCHITECTURE.md) §26. Solo pruebas, configuración de
pruebas (`pom.xml`: `jol-core` 0.17 en ámbito `test`), evidencia y documentación; **`src/main` sin cambios**.

Estado: **implementado, pendiente de verificación en Windows. R4-6 NO está completo**: las mediciones locales indican
que `ResultSizeEstimate` subestima el tamaño retenido (ver abajo). Conforme a la autorización, no se ha modificado
producción ni relajado ninguna aserción; la corrección propuesta espera una nueva autorización.

- Nuevas: `support/MemoryMeasurements` (JOL, regla de objetos compartidos, sondas informativas, evidencia),
  `support/TemporarySpace` (instantáneas y muestreo), `execution/domain/ResultSizeEstimateLayoutTest` (C2-1, 19),
  `execution/infrastructure/VolumeMemoryIntegrationTest` (C2-2, 1), `api/VolumeTemporaries` (soporte),
  `api/VolumeTemporariesIntegrationTest` (C2-3 éxito, 1), `api/VolumeTemporariesFailureIntegrationTest` (C2-3 fallo,
  1). Evidencia: `target/b10c2-evidence/{memoria-tipos.json, memoria-volumen.json, temporales.json, SHA256SUMS}`.
- D-C2-1b (`--add-opens` en Surefire): **no aplicado**. Ninguna ejecución real de JOL lo ha justificado todavía; si
  la primera ejecución en Windows lo exige, se añadirán solo los paquetes que nombre el error.

**Resultados locales (entorno de Claude; no son de Windows ni de JOL).** Las pruebas nuevas compilan con
`javac -Xlint:all` sin avisos (contra sustitutos); las 465 unitarias anteriores siguen 465/465. JOL no se puede
descargar aquí: C2-1 se ejecutó con un **sustituto local** que mide el tamaño de cada objeto con
`java.lang.instrument.Instrumentation#getObjectSize` (el de la propia JVM) y recorre el grafo por identidad; no es
JOL. Con él, en Java 21.0.12 (Linux x64):
- referencias comprimidas (configuración por defecto): C2-1 **9/19**; subestiman `Long` (24 B frente a 16), `BigDecimal`
  grande (120 frente a 74), `LocalDateTime` (72 frente a 64), `OffsetDateTime` (96 frente a 88), textos de 1 carácter
  (48 frente a 42), texto UTF-16 de 9 (64 frente a 58), filas de 1 y 6 columnas (128 frente a 90; 272 frente a 260) y
  el dataset 6×1000 (A 272 000 y B 276 064 frente a 260 000);
- sin referencias comprimidas: subestiman además textos Latin-1 de 9 caracteres y filas de 64 columnas (dataset
  6×1000: B 376 088 frente a 260 000);
- volumen reconstruido con los valores del Excel de B10-c.1 (la clave oculta `dbid` supuesta): **A/E = 1,035,
  B/E = 1,051** (E = 2 573 324 B).

**Esperado en Windows** (`mvn -B clean verify`): 779 pruebas (757 + 22); si JOL confirma lo anterior, fallarán ~10 de
C2-1 y la de C2-2 (**BUILD FAILURE esperado**; la evidencia se escribe antes de las aserciones). C2-3 debe pasar.
Corrección propuesta (no aplicada): [ARCHITECTURE.md](ARCHITECTURE.md) §26.4.

**Windows, C2-1 antes de la corrección (2026-11-02; resultado conservado).** `mvn -B -Dtest=ResultSizeEstimateLayoutTest test`: 19 pruebas, **10 fallos**, 0 errores, 0 omitidas, BUILD FAILURE (log `mvn-b10c2-c21.log` `86019408…9fa9`, Surefire `f6112f57…e014`, `memoria-tipos.json` `8927d6ae…98b2`, en `Claude outputs/a4-b10c2/windows-c21/`). HotSpot 21.0.6, Windows 11, G1, referencias comprimidas, alineación 8, heap máximo 16,9 GB; JOL 0.17 funcionó sin `--add-opens` (avisa de que no pudo enganchar `Instrumentation`). Las 19 cifras coinciden byte a byte con las del sustituto local en el mismo modo.

**Corrección de la estimación («NEXORA AI — A4-B10-c.2 — Corrección de estimación de memoria» (2026-11-02)).** Producción cambia solo en `execution/domain/ResultSizeEstimate`
(constantes: fila 96 + 8n, dataset 96, texto 64 + 2n, `Long` 24, `BigDecimal` 128 + 4·⌈bits/32⌉ siempre, `LocalDate` 24,
`LocalDateTime` 80, `OffsetDateTime` 112; suma y producto con saturación en `Long.MAX_VALUE` y operandos negativos
rechazados) y `execution/infrastructure/PlanV2SnapshotReader` (`Totals` suma el coste de cada dataset y usa la suma con
saturación). Límites sin cambios (64 MiB, 10 000 filas por plan, 30 000 totales). La regla «48 B si cabe en 63 bits»
propuesta antes para `BigDecimal` era errónea (un texto de más de 18 caracteres conserva su `BigInteger`) y se sustituyó.
Pruebas: C2-1 pasa de 19 a 26 (dos `BigDecimal` de más de 18 caracteres, decimal negativo pequeño, texto vacío, el mayor
numérico de PostgreSQL, un texto de un millón de caracteres, dataset vacío); `ResultSizeEstimateTest` nueva (6:
saturación, negativos, extremos, tipos); C2-2 compara con la misma `E` que suma producción y comprueba que un
presupuesto de `E` lee y uno de `E − 1` se rechaza; `pom.xml`: segunda ejecución de Surefire solo para C2-1 sin
referencias comprimidas (informes en `target/surefire-reports-sin-referencias-comprimidas/`).

Resultados locales tras la corrección (sustituto de JOL, no Windows): 465 unitarias anteriores + 6 + 26 = **497/497**
con referencias comprimidas y C2-1 **26/26** sin ellas; volumen reconstruido B/E = 0,660 (comprimidas) y 0,904 (sin
comprimir). **Esperado en Windows:** ejecución por defecto **792** pruebas (757 + 35) y la segunda ejecución **26**,
0 fallos. R4-6 no se declara completo hasta esa verificación. La estimación acota solo las filas retenidas al leer: no
es el consumo del proceso.

**Windows tras la corrección de la estimación (2026-11-02; resultado conservado).** `mvn -B clean verify` ⇒ ejecución por defecto **792 pruebas, 0 fallos, 1 error**, 0 omitidas, BUILD FAILURE (log `mvn-verify-b10c2-correccion.log` `af930044…cde2`). Pasaron C2-1 (26/26, sus 27 casos con las mismas cifras que el JOL real en local), `ResultSizeEstimateTest` (6/6) y C2-3 (éxito y fallo sin archivos residuales; máximo observado 296 822 B y 204 120 B, cota inferior). Error: `VolumeMemoryIntegrationTest`, al medir `D` (con `ReportResult`): JOL 0.17 sin `Instrumentation` usa `Unsafe.objectFieldOffset`, que Java 16+ rechaza en los campos de un record (`ReportResult.run`); `A` y `B` no contienen records. La segunda ejecución de Surefire no llegó a correr y `memoria-volumen.json` no se escribió.

**Corrección mínima JOL/records («NEXORA AI — A4-B10-c.2 — corrección mínima JOL/records» (2026-11-02)), solo pruebas.** `pom.xml`: `jol.magicFieldOffset=true` en las
`systemPropertyVariables` de Surefire (las dos ejecuciones). Nueva `support/JolLayoutCheck`: antes de sumar, cada clase
del grafo medido (records incluidos; arrays con varias longitudes) se compara con el tamaño que da la JVM:
`Instrumentation.getObjectSize` obtenida del agente de Byte Buddy que Mockito ya carga (por reflexión, sin dependencia
nueva) y, si no está disponible, los bytes que la JVM cuenta al asignar 1 024 objetos de la clase (el menor de 5
rondas); cualquier diferencia o la imposibilidad de comprobar hacen fallar la medición. Los errores de medición se
escriben en la evidencia (C2-1 y C2-2) y se vuelven a lanzar. No cambian producción, estimador, límites ni criterios.
Local con el JOL 0.17 real (mismo SHA-1 que el de Windows): 497/497 y C2-1 26/26 sin referencias comprimidas; volumen
A/E 0,650, B/E 0,660, D 2 721 336 B (comprimidas) y A/E 0,885, B/E 0,904, D 3 729 576 B (sin comprimir), iguales con
ambas referencias; 43 clases comprobadas en el volumen (17 records). **Esperado en Windows:** 792 + 26, 0 fallos y
0 errores. B10-c.2 y R4-6 no se declaran completos hasta esa verificación.

**Verificación final en Windows (2026-11-02; «PROCEDE con el cierre documental de B10-c.2» (2026-11-02)).** Respaldado por el log de Maven conservado (`mvn-verify-b10c2-jol.log`, SHA-256 `4622109c2112c29f72bdfadbe75fdda60376443a44d0ca977ca936692ef8cabb`) y Surefire: `mvn -B clean verify` ⇒ ejecución por defecto **792 pruebas, 0 fallos, 0 errores, 0 omitidas** (68 clases) y ejecución sin referencias comprimidas **26/0/0/0** (solo `ResultSizeEstimateLayoutTest`), `BUILD SUCCESS`, 02:44 min, Windows 11, Java 21.0.6. C2-1: 27/27 casos dentro en cada modo (peor razón real/E 1,000, `Long`); C2-2: tres repeticiones idénticas, A/E 0,6499 y B/E 0,6597, presupuesto `E` lee y `E − 1` se rechaza; JOL contrastado con `Instrumentation.getObjectSize` sin diferencias (20, 15 y 48 clases); C2-3: éxito y rechazo con 0 archivos y 0 bytes residuales (máximo observado 340 275 B y 204 122 B, cota inferior). Código ejecutado = transferido (14 huellas; `src/main` `e66afbfb…2084`). Evidencia en `Claude outputs/a4-b10c2/windows-2026-11-02-final/` (`SHA256SUMS.txt` `157adad4451ec9c99a094165b11ca6f097bc611d55e409e69ca54cabfcdc1155`). **A4-B10-c.2 queda verificado en Windows dentro de su alcance.** Historial conservado arriba: C2-1 10/19 antes de corregir la estimación y 791/792 por JOL y records. **R4-6:** «Criterios de medición y verificación de B10-c.2 demostrados satisfactoriamente en Windows. Documentación del heap recomendado parcialmente completada. R4-6 permanece abierto y será reevaluado en B11 conforme a sus criterios originales de aceptación, sin considerar garantizado su cierre». Límites: sin referencias comprimidas, en Windows solo se ejecutó C2-1 (el volumen en ese modo, B/E 0,904, está medido solo en local); la comprobación con `Instrumentation` usa el agente de Byte Buddy de Mockito (si un JDK lo impidiera, pasaría a la cuenta de asignación). Siguen abiertas B10c2-O1 (128 MiB de temporales), B10c2-O2 (memoria agregada), B10c2-O5 (alcance de la estimación) y B10c2-O6 (vía interna de JOL); B10c2-O3 y B10c2-O4 resueltas.

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
