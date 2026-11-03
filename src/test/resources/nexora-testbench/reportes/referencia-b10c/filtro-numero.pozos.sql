-- B10-c.1: SQL de referencia escrito a mano a partir de la especificación auténtica (no copiado del traductor de Reports).
-- El literal de la especificación es «300.000»: el número 300.
SELECT name AS nombre, field AS campo, country AS pais, dbid AS id_pozo
  FROM wellnode_sintetico.wm_well
 WHERE dbid >= 300
 ORDER BY name COLLATE "C" ASC NULLS LAST, dbid ASC
