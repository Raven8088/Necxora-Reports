-- B10-c.1: SQL de referencia escrito a mano a partir de la especificación auténtica (no copiado del traductor de Reports).
SELECT name AS nombre, field AS campo, country AS pais, dbid AS id_pozo
  FROM wellnode_sintetico.wm_well
 WHERE field IS NULL
 ORDER BY name COLLATE "C" ASC NULLS LAST, dbid ASC
