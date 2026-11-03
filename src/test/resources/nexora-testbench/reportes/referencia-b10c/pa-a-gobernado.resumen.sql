-- B10-c.1: SQL de referencia escrito a mano a partir de la especificación auténtica (no copiado del traductor de Reports).
-- Dimensión gobernada «pais» = wm_well.country; métrica gobernada «pozos» = COUNT de su concepto (filas de wm_well).
SELECT country AS pais, field AS campo, count(*) AS pozos
  FROM wellnode_sintetico.wm_well
 GROUP BY country, field
 ORDER BY country COLLATE "C" ASC NULLS LAST, field COLLATE "C" ASC NULLS LAST
