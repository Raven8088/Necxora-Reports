-- SYNTHETIC enterprise data for local development (no real company data).
-- Same physical model as Semantic's SyntheticCatalog, so plans created in Semantic over an import of this
-- database (Discovery) run unchanged in Reports. Rows match the integration tests, except that the test-only
-- columns (measured_local, recorded_on) and the unmatched sensor are absent, and sensors has a real foreign key.

CREATE SCHEMA plant;
CREATE SCHEMA finance;
CREATE SCHEMA docs;

CREATE TABLE plant.equipment (
    id   int8 PRIMARY KEY,
    dbid int8 NOT NULL,
    name varchar(100)
);

CREATE TABLE plant.measurements (
    id             int8 PRIMARY KEY,
    equipment_dbid int8 NOT NULL,
    value          numeric(12, 3),
    measured_at    timestamptz NOT NULL,
    note           varchar(200)
);

CREATE TABLE plant.sensors (
    id           int8 PRIMARY KEY,
    equipment_id int8 NOT NULL REFERENCES plant.equipment (id),
    vendor_code  varchar(10),
    label        varchar(50)
);

CREATE TABLE finance.transactions (
    id              int8 PRIMARY KEY,
    company_code    bpchar(4) NOT NULL,
    document_number varchar(20),
    amount          numeric(12, 2) NOT NULL
);

CREATE TABLE docs.documents (
    company_code bpchar(4) NOT NULL,
    number       varchar(20) NOT NULL,
    title        varchar(200),
    PRIMARY KEY (company_code, number)
);

INSERT INTO plant.equipment (id, dbid, name) VALUES
    (1, 101, 'Bomba'), (2, 102, 'Compresor'), (3, 103, NULL);

INSERT INTO plant.measurements (id, equipment_dbid, value, measured_at, note) VALUES
    (1, 1, 50.000, '2025-12-31T23:30:00Z', 'ok'),
    (2, 1, 70.000, '2026-01-10T10:00:00Z', 'ok'),
    (3, 2, 200.500, '2026-01-15T12:00:00Z', 'revisar'),
    (4, 2, NULL, '2026-02-01T00:30:00Z', 'ok'),
    (5, 99, 10.000, '2026-02-03T08:00:00Z', NULL),
    (6, 3, 30.000, '2026-02-10T09:00:00Z', 'descartada'),
    (7, 1, 5.250, '2025-11-20T15:00:00Z', 'revisar'),
    (8, 1, 1.000, '2026-01-12T02:00:00Z', 'ok');

INSERT INTO plant.sensors (id, equipment_id, vendor_code, label) VALUES
    (1, 1, 'V1', 'S1'), (2, 1, 'V1', 'S2'), (3, 2, 'V2', 'S3');

INSERT INTO finance.transactions (id, company_code, document_number, amount) VALUES
    (1, 'C1', 'D-1', 100.00), (2, 'C1', 'D-1', 50.50), (3, 'C1', 'D-2', 20.00),
    (4, 'C2', 'D-1', 7.00), (5, 'C9', 'D-9', 999.99);

INSERT INTO docs.documents (company_code, number, title) VALUES
    ('C1', 'D-1', 'Factura A'), ('C1', 'D-2', 'Factura B'), ('C2', 'D-1', 'Factura C');
