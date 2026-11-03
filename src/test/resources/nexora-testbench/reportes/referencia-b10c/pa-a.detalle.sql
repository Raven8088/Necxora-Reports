-- B10-c.1: SQL de referencia escrito a mano a partir de la especificación auténtica (no copiado del traductor de Reports).
-- Orden de la especificación (país, nombre) y desempate por la clave primaria (dbid), que no se muestra.
SELECT name AS nombre, field AS campo, country AS pais, state AS estado, statuswell AS estatus
  FROM wellnode_sintetico.wm_well
 ORDER BY country COLLATE "C" ASC NULLS LAST, name COLLATE "C" ASC NULLS LAST, dbid ASC
