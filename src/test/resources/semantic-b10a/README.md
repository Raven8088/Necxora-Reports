# Exportación A4-B10-a — planes auténticos de Nexora Semantic

Esta carpeta (`target/a4-b10a-export/`) la escribe la prueba `ReportPlanExportIntegrationTest` durante
`mvn verify` en Nexora-Semantic. Todo documento de `documentos/` y `respuestas/` es el cuerpo exacto que sirvió la
API productiva de Semantic (compilador `POST /api/v1/data-sources/{id}/report-plans` y los `GET` que devuelven lo
almacenado), leído con el token de servicio de Reports. Ningún documento se escribe a mano ni se recalcula su huella.

## Contenido

| Ruta | Qué es |
|---|---|
| `entradas/<caso>.report-spec.json` | Especificación enviada al compilador, con los ids reales de esta ejecución. |
| `documentos/<caso>.report-plan.json` | `nexora-report-plan/1` tal como se sirvió (forma canónica, huella propia). |
| `documentos/<caso>.report-spec.json` | Especificación almacenada (`GET …/spec`); su SHA-256 es el `specFingerprint`. |
| `documentos/<caso>.<dataset>.logical-plan.json` | `nexora-logical-plan/2` de cada dataset. |
| `respuestas/<caso>.validity.json` | `GET …/validity` al final de la prueba (CURRENT, o STALE en `caducado`). |
| `respuestas/caducado.validity-antes.json` | Validez del caso `caducado` antes de desactivar su métrica (CURRENT). |
| `respuestas/capabilities.json` | `GET /api/v1/capabilities`. |
| `respuestas/data-source.json` | `GET /api/v1/data-sources/{id}` del origen usado. |
| `manifest.json` | Ids, casos, hashes, entorno y generador (JSON canónico). |
| `SHA256SUMS` | SHA-256 de cada archivo salvo él mismo (formato `sha256sum`). |

## Casos

| Caso | Contenido |
|---|---|
| `pa-a` | Ejemplo de contrato PA-A (resumen, total, detalle). Sus planes coinciden, normalizados, con los ejemplos. |
| `pa-a-gobernado` | Ejemplo PA-A gobernado: dimensión `pais` y métrica `pozos` aprobadas en el catálogo. |
| `empresas` | Reporte B: otra tabla base, JOIN por la relación aprobada `empresa_operadora`, BOOLEAN, IN, IS_NOT_NULL y orden DESC NULLS FIRST. |
| `filtro-in` | IN sobre texto. |
| `filtro-numero` | GTE con NUMBER `300.000`, normalizado por Semantic a `300` en el plan lógico. |
| `filtro-nulo` | IS_NULL sin valores. |
| `volumen` | PA-A sobre `wellnode_sintetico_volumen.wm_well` (solo metadatos). |
| `caducado` | PA-A gobernado con la métrica `pozos_caducable`; se desactiva tras compilar y su validez pasa a STALE sin invalidar los demás casos. |

## Ids y reproducibilidad

Los ids (inquilino, origen, objetos, relación, definiciones, planes) son aleatorios en cada ejecución. El
`manifest.json` los resuelve: `objects` (clave natural → id), `relationships`, `definitions`, `placeholders` (cada
marcador `@@…@@` de las plantillas de `src/test/resources/b10a/` → id) y, por caso, `reportPlanId` y los `planId`.
Para comparar dos ejecuciones se usa `normalisedSha256` de cada documento: SHA-256 del documento sin su
`fingerprint` y con cada UUID y cada SHA-256 sustituidos por su orden de aparición. El significado funcional de los
casos lo comprueban las aserciones de la prueba en cada ejecución; la igualdad de `normalisedSha256` entre dos
ejecuciones no se ha verificado todavía.

## Regenerar y validar

1. En Nexora-Semantic: `mvn verify` (la prueba forma parte de la suite y no se omite).
2. Integridad: comparar `SHA256SUMS` con los archivos (la propia prueba ya relee la carpeta y verifica cada hash).
3. Contratos (opcional, requiere Python 3 y el paquete `jsonschema`):
   `python src/test/python/validar_documentos_generados.py --contratos "../nexora-contracts" --dir target/a4-b10a-export/documentos`

## Límites

- Solo se compilan metadatos: el banco de pruebas (`nexora_bench_reportes`) no se lee y ninguna fila sale aquí.
- Los metadatos proceden de `src/test/resources/discovery/wellnode-sintetico-con-volumen.json`, fixture generado
  (no es una captura de Discovery).
- `generatedAt` y los ids cambian en cada ejecución; los hashes de `files` y `SHA256SUMS` describen esta ejecución.
- Que existan estos planes no demuestra la ejecución de extremo a extremo en Reports (B10-c) ni el cumplimiento
  completo de PR-2 o PR-3.
