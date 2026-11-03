-- B10-c.1: SQL de referencia escrito a mano a partir de la especificación auténtica (no copiado del traductor de Reports).
-- Relación aprobada empresa_operadora: etp_company.id_operator -> bkw_operators.id (MANY_TO_ONE), unión LEFT.
SELECT o.name AS operadora, count(*) AS empresas
  FROM wellnode_sintetico.etp_company e
  LEFT JOIN wellnode_sintetico.bkw_operators o ON o.id = e.id_operator
 WHERE e.is_active = TRUE
 GROUP BY o.name
 ORDER BY o.name COLLATE "C" ASC NULLS LAST
