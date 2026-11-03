# Referencias de B10-c.1 (planes auténticos de B10-a)

`casos.json` enumera los siete casos CURRENT exportados por Nexora Semantic en B10-a (copia íntegra en
`src/test/resources/semantic-b10a/`) con el estado que debe dar su ejecución en Reports y, por dataset, el SQL de
referencia y el número de filas. `caducado` (STALE) no se lee: lo usan las pruebas de vigencia.

Cada `<caso>.<dataset>.sql` se escribió **a mano** a partir de la especificación auténtica servida por Semantic
(`documentos/<caso>.report-spec.json`): columnas visibles en su orden, filtros, agrupación, orden de la especificación
(intercalación `C` en texto, `NULLS` según la especificación) y, en los detalles, desempate por la clave primaria, que
no se muestra. **No se copió el SQL que genera Reports.** Las pruebas lo ejecutan con el rol de solo lectura del
banco, por separado de Reports, y comparan celda a celda el Excel descargado.

Comprobación independiente hecha al escribirlos (2026-11-02, PostgreSQL 16 local con `01-esquema.sql`,
`02-datos.sql` y `03-rol-lector.sql` del banco): número de filas de `casos.json`; los tres de PA-A coinciden con
`esperado/pa-a.json`; el volumen coincide con su bloque `volumen` (12 000 filas; primeros nombres VOL-00005,
VOL-00010, VOL-00015).
