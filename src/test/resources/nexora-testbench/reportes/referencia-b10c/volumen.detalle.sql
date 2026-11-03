-- B10-c.1: SQL de referencia escrito a mano a partir de la especificación auténtica (no copiado del traductor de Reports).
-- maxRows del plan: 10 000 (el resultado se trunca: PARTIAL).
SELECT name AS nombre, field AS campo, country AS pais, state AS estado, statuswell AS estatus
  FROM wellnode_sintetico_volumen.wm_well
 ORDER BY country COLLATE "C" ASC NULLS LAST, name COLLATE "C" ASC NULLS LAST, dbid ASC
 LIMIT 10000
