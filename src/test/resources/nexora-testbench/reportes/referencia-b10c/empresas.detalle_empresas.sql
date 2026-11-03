-- B10-c.1: SQL de referencia escrito a mano a partir de la especificación auténtica (no copiado del traductor de Reports).
-- Orden de la especificación (matriz DESC NULLS FIRST) y desempate por la clave primaria (id), que no se muestra.
SELECT name AS nombre, code AS codigo, parentid AS matriz
  FROM wellnode_sintetico.etp_company
 WHERE code IN ('GPA', 'SIC-FLU', 'GEA', 'GPA-SER') AND name IS NOT NULL
 ORDER BY parentid DESC NULLS FIRST, id ASC
