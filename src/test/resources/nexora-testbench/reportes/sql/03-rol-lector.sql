-- Banco sintético de reportes (A0) — ROL DE SOLO LECTURA PARA NEXORA.
-- Control independiente de Nexora (condición 3 de seguridad): permisos de SELECT POR COLUMNA,
-- sin columnas sensibles y sin ninguna tabla de autenticación. La contraseña NO está aquí: la fija
-- docker/initdb/00-crear-banco.sh desde la variable BENCH_READER_PASSWORD (archivo .env, nunca versionado).
--
-- Solo crea y concede. No contiene DROP ni REVOKE de objetos ajenos.

BEGIN;

CREATE ROLE nexora_bench_lector LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION;
ALTER ROLE nexora_bench_lector SET default_transaction_read_only = on;

GRANT CONNECT ON DATABASE nexora_bench_reportes TO nexora_bench_lector;
GRANT USAGE ON SCHEMA wellnode_sintetico, wellnode_sintetico_volumen TO nexora_bench_lector;

-- Columnas permitidas (todo lo no listado queda inaccesible).
GRANT SELECT ("dbid", "WellID", uid, name, namelegal, field, country, state, county, region, timezone, operator,
              statuswell, dtimspud) ON wellnode_sintetico.wm_well TO nexora_bench_lector;
GRANT SELECT (id, name, parentid, code, is_active, id_profile_units, id_operator)
    ON wellnode_sintetico.etp_company TO nexora_bench_lector;
GRANT SELECT (id, name) ON wellnode_sintetico.bkw_operators TO nexora_bench_lector;
GRANT SELECT (id, first_name, last_name, username, id_company, id_rol, is_active)
    ON wellnode_sintetico.users TO nexora_bench_lector;
GRANT SELECT (idsubscription, uidwell, defaultuidwellbore, namewell, enabled, id_company)
    ON wellnode_sintetico.etp_subscription TO nexora_bench_lector;
GRANT SELECT ON wellnode_sintetico.etp_groups, wellnode_sintetico.etp_group_user, wellnode_sintetico.etp_group_well,
               wellnode_sintetico.bkw_cat_activity, wellnode_sintetico.bkw_aliases_activity,
               wellnode_sintetico.bkw_electronic_logs, wellnode_sintetico_volumen.wm_well
    TO nexora_bench_lector;
-- Sin acceso: wellnode_sintetico.auth_user, bench_marker, y las columnas sensibles
-- (users.email, users.uuid, users.preferences, etp_company.contact, etp_company.logo, bkw_operators.logo,
--  etp_subscription.host, etp_subscription.username, etp_subscription.password).

COMMIT;
