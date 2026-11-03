-- GENERADO por datos/semilla.py. NO EDITAR A MANO.
-- Datos FICTICIOS del banco de reportes (A0). Ningún dato procede de Wellnode.
-- Requiere 01-esquema.sql. Se ejecuta una única vez, al crear el volumen del banco.

BEGIN;
INSERT INTO wellnode_sintetico.bkw_operators (id, name) VALUES
    (1, 'Operadora Ficticia Uno'),
    (2, 'Operadora Ficticia Dos');
INSERT INTO wellnode_sintetico.etp_company (id, name, code, parentid, is_active, id_operator, contact) VALUES
    (10, 'Grupo Petrolero Atlas', 'GPA', NULL, TRUE, 1, 'contacto-gpa@ejemplo.invalid'),
    (11, 'Atlas Perforación', 'GPA-PER', 10, TRUE, 1, NULL),
    (12, 'Atlas Servicios de Pozo', 'GPA-SER', 10, TRUE, 1, NULL),
    (20, 'Servicios Integrales Caribe', 'SIC', NULL, TRUE, 2, NULL),
    (21, 'Caribe Fluidos', 'SIC-FLU', 20, FALSE, 2, NULL),
    (30, 'Geotecnia Andina', 'GEA', NULL, TRUE, NULL, NULL);
INSERT INTO wellnode_sintetico.users (id, first_name, last_name, username, id_company, id_rol, is_active) VALUES
    (1001, 'Ana', 'García', 'agarcia', 10, 1, TRUE),
    (1002, 'Luis', 'Martínez', 'lmartinez', 10, 2, TRUE),
    (1003, 'María', 'López', 'mlopez', 11, 2, TRUE),
    (1004, 'Jorge', 'Hernández', 'jhernandez', 11, 3, FALSE),
    (1005, 'Carmen', 'Torres', 'ctorres', 11, 3, TRUE),
    (1006, 'Raúl', 'Ramírez', 'rramirez', 12, 3, TRUE),
    (1007, 'Sofía', 'Flores', 'sflores', 12, 3, FALSE),
    (1008, 'Diego', 'Vargas', 'dvargas', 20, 1, TRUE),
    (1009, 'Elena', 'Castro', 'ecastro', 20, 2, TRUE),
    (1010, 'Pablo', 'Ortega', 'portega', 21, 3, TRUE),
    (1011, 'Lucía', 'Rojas', 'lrojas', 21, 3, FALSE),
    (1012, 'Héctor', 'Navarro', 'hnavarro', 30, 1, TRUE),
    (1013, 'Paula', 'Mendoza', 'pmendoza', NULL, 3, TRUE);
INSERT INTO wellnode_sintetico.wm_well ("dbid", "WellID", uid, name, field, country, state, statuswell, operator) VALUES
    (101, 'W-101', 'uid-w-101', 'Tamarindo-1', 'Campo Norte', 'México', 'Tabasco', 'activo', 'Operadora Ficticia Uno'),
    (102, 'W-102', 'uid-w-102', 'Tamarindo-2', 'Campo Norte', 'México', 'Tabasco', 'perforando', 'Operadora Ficticia Uno'),
    (103, 'W-103', 'uid-w-103', 'Pitayo-1', 'Campo Norte', 'México', 'Tabasco', 'activo', 'Operadora Ficticia Uno'),
    (104, 'W-104', 'uid-w-104', 'Pitayo-1', 'Campo Sur', 'México', 'Veracruz', 'suspendido', 'Operadora Ficticia Uno'),
    (105, 'W-105', 'uid-w-105', 'Ceiba-7', 'Campo Sur', 'México', 'Veracruz', 'activo', 'Operadora Ficticia Dos'),
    (106, 'W-106', 'uid-w-106', 'Ébano-2', 'Campo Sur', 'México', 'Veracruz', 'abandonado', 'Operadora Ficticia Dos'),
    (107, 'W-107', 'uid-w-107', 'alfa-3', 'Campo Sur', 'México', NULL, 'activo', 'Operadora Ficticia Dos'),
    (108, 'W-108', 'uid-w-108', 'Mangle-4', 'Litoral', 'México', 'Campeche', 'perforando', 'Operadora Ficticia Uno'),
    (109, 'W-109', 'uid-w-109', 'Mangle-5', 'Litoral', 'México', 'Campeche', NULL, 'Operadora Ficticia Uno'),
    (110, 'W-110', 'uid-w-110', '=Cuenca-9', 'Litoral', 'México', 'Campeche', 'activo', 'Operadora Ficticia Uno'),
    (111, 'W-111', 'uid-w-111', 'Zapote-1', NULL, 'México', 'Tabasco', 'activo', NULL),
    (112, 'W-112', 'uid-w-112', 'Álamo-1', NULL, 'México', 'Tabasco', 'suspendido', NULL),
    (201, 'W-201', 'uid-w-201', 'Ceibo-1', 'Selva Alta', 'Perú', 'Loreto', 'activo', 'Operadora Ficticia Dos'),
    (202, 'W-202', 'uid-w-202', 'Ceibo-2', 'Selva Alta', 'Perú', 'Loreto', 'activo', 'Operadora Ficticia Dos'),
    (203, 'W-203', 'uid-w-203', 'Ceibo-3', 'Selva Alta', 'Perú', 'Loreto', 'perforando', 'Operadora Ficticia Dos'),
    (204, 'W-204', 'uid-w-204', 'Quinua-1', 'Costa', 'Perú', 'Piura', 'abandonado', 'Operadora Ficticia Dos'),
    (205, 'W-205', 'uid-w-205', 'Quinua-2', 'Costa', 'Perú', 'Piura', 'activo', 'Operadora Ficticia Dos'),
    (206, 'W-206', 'uid-w-206', 'Ñandú-1', 'Costa', 'Perú', NULL, 'activo', 'Operadora Ficticia Dos'),
    (301, 'W-301', 'uid-w-301', 'Llano-1', 'Llanos', 'Colombia', 'Meta', 'activo', 'Operadora Ficticia Uno'),
    (302, 'W-302', 'uid-w-302', 'Llano-2', 'Llanos', 'Colombia', 'Meta', 'activo', 'Operadora Ficticia Uno'),
    (303, 'W-303', 'uid-w-303', 'Llano-3', 'Llanos', 'Colombia', 'Meta', 'suspendido', 'Operadora Ficticia Uno'),
    (304, 'W-304', 'uid-w-304', 'Llano-10', 'Llanos', 'Colombia', 'Casanare', 'activo', 'Operadora Ficticia Uno'),
    (305, 'W-305', 'uid-w-305', 'Magdalena-1', 'Valle Medio', 'Colombia', 'Santander', 'perforando', 'Operadora Ficticia Uno'),
    (306, 'W-306', 'uid-w-306', 'Magdalena-2', 'Valle Medio', 'Colombia', 'Santander', 'activo', 'Operadora Ficticia Uno'),
    (307, 'W-307', 'uid-w-307', 'Guadua-1', NULL, 'Colombia', 'Boyacá', 'abandonado', NULL),
    (308, 'W-308', 'uid-w-308', 'guadua-2', 'Valle Medio', 'Colombia', 'Boyacá', 'activo', 'Operadora Ficticia Uno'),
    (401, 'W-401', 'uid-w-401', 'Pampa-1', 'Neuquén', 'Argentina', 'Neuquén', 'activo', 'Operadora Ficticia Dos'),
    (501, 'W-501', 'uid-w-501', 'Oriente-1', 'Oriente', 'Ecuador', 'Orellana', 'activo', 'Operadora Ficticia Uno'),
    (502, 'W-502', 'uid-w-502', 'Oriente-2', 'Oriente', 'Ecuador', 'Orellana', 'activo', 'Operadora Ficticia Uno'),
    (503, 'W-503', 'uid-w-503', 'Oriente-3', 'Oriente', 'Ecuador', 'Sucumbíos', 'perforando', 'Operadora Ficticia Uno'),
    (504, 'W-504', 'uid-w-504', 'Cuyabeno-1', 'Amazonía', 'Ecuador', 'Sucumbíos', 'suspendido', 'Operadora Ficticia Uno'),
    (505, 'W-505', 'uid-w-505', 'Cuyabeno-2', 'Amazonía', 'Ecuador', 'Sucumbíos', 'activo', 'Operadora Ficticia Uno'),
    (601, 'W-601', 'uid-w-601', 'Sin-Pais-1', 'Campo Norte', NULL, NULL, 'activo', NULL),
    (602, 'W-602', 'uid-w-602', 'Sin-Pais-2', 'Desconocido', NULL, NULL, NULL, NULL),
    (603, 'W-603', 'uid-w-603', 'Sin-Pais-3', NULL, NULL, NULL, 'abandonado', NULL);
INSERT INTO wellnode_sintetico.etp_subscription (idsubscription, uidwell, defaultuidwellbore, namewell, id_company) VALUES
    (1, 'uid-w-101', 'uid-wb-101', 'Tamarindo-1', 10),
    (2, 'uid-w-102', 'uid-wb-102', 'Tamarindo-2', 11),
    (3, 'uid-w-201', 'uid-wb-201', 'Ceibo-1', 20),
    (4, 'uid-w-301', 'uid-wb-301', 'Llano-1', 12),
    (5, 'uid-w-501', 'uid-wb-501', 'Oriente-1', 30);
INSERT INTO wellnode_sintetico.etp_groups (id, name, idcompany, is_default, is_active) VALUES
    (1, 'Operación Atlas', 10, TRUE, TRUE),
    (2, 'Caribe', 20, TRUE, TRUE),
    (3, 'Inactivo', 10, FALSE, FALSE);
INSERT INTO wellnode_sintetico.etp_group_user (id, idgroup, iduser) VALUES
    (1, 1, 1001),
    (2, 1, 1003),
    (3, 2, 1008),
    (4, 3, 1002);
INSERT INTO wellnode_sintetico.etp_group_well (id, idgroup, idsubscription) VALUES
    (1, 1, 1),
    (2, 1, 2),
    (3, 2, 3),
    (4, 3, 4);
INSERT INTO wellnode_sintetico.bkw_cat_activity (id, value, id_company) VALUES
    (1, 'DRILL', 1),
    (2, 'CONN', 1),
    (3, 'TRIP', 1);
INSERT INTO wellnode_sintetico.bkw_aliases_activity (id, id_activity, id_language, alias) VALUES
    (1, 1, 1, 'Perforación'),
    (2, 1, 2, 'Drilling'),
    (3, 2, 1, 'Conexión'),
    (4, 2, 2, 'Connection'),
    (5, 3, 1, 'Viaje'),
    (6, 3, 2, 'Trip');
INSERT INTO wellnode_sintetico.bkw_electronic_logs (id, uid_well, uid_wellbore, id_user, start_date, id_activity, description) VALUES
    (1, 'uid-w-101', 'uid-wb-101', 1001, '2026-10-01 06:00:00', 1, 'Inicio de perforación'),
    (2, 'uid-w-101', 'uid-wb-101', 1001, '2026-10-01 14:30:00', 2, 'Conexión'),
    (3, 'uid-w-101', 'uid-wb-101', 1003, '2026-10-02 08:15:00', 1, NULL),
    (4, 'uid-w-201', 'uid-wb-201', 1008, '2026-10-03 09:00:00', 3, 'Viaje');

-- Valores sensibles FICTICIOS: existen solo para comprobar que Nexora nunca los lee.
UPDATE wellnode_sintetico.users SET email = username || '@ejemplo.invalid',
       uuid = md5('ficticio-' || id)::uuid, preferences = '{"tema": "oscuro"}'::jsonb;
INSERT INTO wellnode_sintetico.auth_user (id, username, password, email) VALUES
    (1, 'admin-ficticio', 'pbkdf2_sha256$FICTICIO$no-es-una-contrasena', 'admin@ejemplo.invalid');
UPDATE wellnode_sintetico.etp_subscription SET host = 'https://witsml.ejemplo.invalid',
       username = 'usuario-ficticio', password = 'contrasena-ficticia';

-- Variante de volumen (truncado): 12 000 pozos deterministas, sin aleatoriedad.
INSERT INTO wellnode_sintetico_volumen.wm_well (dbid, uid, name, field, country)
SELECT g, 'uid-v-' || g, 'VOL-' || lpad(g::text, 5, '0'), 'Campo V' || (g % 7),
       (ARRAY['Argentina', 'Colombia', 'Ecuador', 'México', 'Perú'])[1 + (g % 5)]
FROM generate_series(1, 12000) AS g;

UPDATE wellnode_sintetico.bench_marker SET datos_cargados = TRUE;
COMMIT;
