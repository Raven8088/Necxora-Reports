-- Banco sintético de reportes (A0) — ESTRUCTURA.
-- Reproduce la FORMA de las tablas de Wellnode que usarán los reportes básicos, con los nombres de tabla y
-- columna de las entidades JPA de back_end (Well, CompanyBase, Operator, LocalUser, User, EtpSubscription,
-- Group*, ElectronicLogs, CatActivity, AliasesActivity). No contiene ningún dato de Wellnode.
--
-- Claves: PK en todas las tablas y UNIQUE en wm_well.uid. SIN claves foráneas: en Wellnode no está confirmado
-- que existan (no hay DDL en el código), así que Nexora debe funcionar sin ellas. Las FK de prueba están en
-- sql/opcional/fk.sql y NO se cargan automáticamente.
--
-- Este archivo solo CREA objetos. No contiene DROP, TRUNCATE ni DELETE. Se ejecuta una única vez, cuando el
-- contenedor inicializa un volumen vacío (docker-entrypoint-initdb.d).

BEGIN;

CREATE SCHEMA wellnode_sintetico;
CREATE SCHEMA wellnode_sintetico_volumen;

COMMENT ON SCHEMA wellnode_sintetico IS 'Nexora testbench: datos FICTICIOS con la forma de Wellnode (A0)';
COMMENT ON SCHEMA wellnode_sintetico_volumen IS 'Nexora testbench: 12 000 pozos ficticios para probar el truncado';

-- Marcador: identifica esta base como banco de pruebas. Los scripts se niegan a trabajar sin él.
CREATE TABLE wellnode_sintetico.bench_marker (
    banco          text PRIMARY KEY CHECK (banco = 'nexora-testbench-reportes'),
    version        integer NOT NULL,
    creado_en      timestamptz NOT NULL DEFAULT now(),
    datos_cargados boolean NOT NULL DEFAULT FALSE
);
INSERT INTO wellnode_sintetico.bench_marker (banco, version) VALUES ('nexora-testbench-reportes', 1);

CREATE TABLE wellnode_sintetico.bkw_operators (
    id   integer PRIMARY KEY,
    name varchar(255) NOT NULL,
    logo jsonb                               -- sensible (excluido)
);

CREATE TABLE wellnode_sintetico.etp_company (
    id               integer PRIMARY KEY,
    name             varchar(255) NOT NULL,
    contact          varchar(255),           -- sensible (excluido)
    parentid         integer,
    code             varchar(50),
    logo             jsonb,                  -- sensible (excluido)
    is_active        boolean,
    id_profile_units integer,
    id_operator      integer
);

CREATE TABLE wellnode_sintetico.users (
    id          integer PRIMARY KEY,
    first_name  varchar(150),
    last_name   varchar(150),
    email       varchar(254),                -- sensible (excluido)
    username    varchar(150),
    id_company  integer,
    uuid        uuid,                        -- sensible (excluido)
    id_rol      integer,
    is_active   boolean,
    preferences jsonb                        -- sensible (excluido)
);

-- Tabla de autenticación: completamente excluida.
CREATE TABLE wellnode_sintetico.auth_user (
    id       integer PRIMARY KEY,
    username varchar(150) NOT NULL,
    password varchar(128) NOT NULL,
    email    varchar(254)
);

CREATE TABLE wellnode_sintetico.wm_well (
    dbid       integer PRIMARY KEY,
    "WellID"   varchar(64),
    uid        varchar(64) NOT NULL UNIQUE,
    name       varchar(255) NOT NULL,
    namelegal  varchar(255),
    field      varchar(255),
    country    varchar(255),
    state      varchar(255),
    county     varchar(255),
    region     varchar(255),
    timezone   varchar(16),
    operator   varchar(255),
    statuswell varchar(64),
    dtimspud   timestamp
);

CREATE TABLE wellnode_sintetico.etp_subscription (
    idsubscription     integer PRIMARY KEY,
    host               varchar(255),         -- sensible (excluido)
    uidwell            varchar(64) NOT NULL,
    defaultuidwellbore varchar(64),
    namewell           varchar(255),
    username           varchar(255),         -- sensible (excluido)
    password           varchar(255),         -- sensible (excluido)
    enabled            boolean NOT NULL DEFAULT TRUE,
    id_company         integer
);

CREATE TABLE wellnode_sintetico.etp_groups (
    id         integer PRIMARY KEY,
    name       varchar(255) NOT NULL,
    idcompany  integer,
    is_default boolean,
    is_active  boolean
);

CREATE TABLE wellnode_sintetico.etp_group_user (
    id      integer PRIMARY KEY,
    idgroup integer NOT NULL,
    iduser  integer NOT NULL
);

CREATE TABLE wellnode_sintetico.etp_group_well (
    id             integer PRIMARY KEY,
    idgroup        integer NOT NULL,
    idsubscription integer NOT NULL
);

CREATE TABLE wellnode_sintetico.bkw_cat_activity (
    id         integer PRIMARY KEY,
    value      varchar(100) NOT NULL,
    id_company integer,
    color      varchar(16)
);

CREATE TABLE wellnode_sintetico.bkw_aliases_activity (
    id          integer PRIMARY KEY,
    id_activity integer NOT NULL,
    id_language integer NOT NULL,
    alias       varchar(255) NOT NULL
);

CREATE TABLE wellnode_sintetico.bkw_electronic_logs (
    id           integer PRIMARY KEY,
    uid_well     varchar(64) NOT NULL,
    uid_wellbore varchar(64) NOT NULL,
    id_user      integer NOT NULL,
    start_date   timestamp NOT NULL,
    id_activity  integer NOT NULL,
    description  text
);

-- Variante de volumen: misma forma de wm_well (solo las columnas de PA-A).
CREATE TABLE wellnode_sintetico_volumen.wm_well (
    dbid       integer PRIMARY KEY,
    uid        varchar(64) NOT NULL UNIQUE,
    name       varchar(255) NOT NULL,
    field      varchar(255),
    country    varchar(255),
    state      varchar(255),
    statuswell varchar(64)
);

COMMIT;
