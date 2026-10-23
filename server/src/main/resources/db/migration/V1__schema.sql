-- Community road reports (police, accidents, hazards...). Reports expire on
-- their own; drivers extend them ("still there") or vote them away ("gone").
CREATE TABLE reports (
    id                 UUID PRIMARY KEY,
    type               VARCHAR(16) NOT NULL,
    lat                DOUBLE PRECISION NOT NULL,
    lon                DOUBLE PRECISION NOT NULL,
    note               VARCHAR(140),
    reporter           VARCHAR(64) NOT NULL,
    confirmations      INT NOT NULL,
    dismissals         INT NOT NULL,
    created_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    last_confirmed_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT reports_type_valid CHECK (type IN ('POLICE', 'ACCIDENT', 'HAZARD', 'JAM', 'CLOSURE')),
    CONSTRAINT reports_lat_range CHECK (lat BETWEEN -90 AND 90),
    CONSTRAINT reports_lon_range CHECK (lon BETWEEN -180 AND 180)
);

-- Map-viewport queries filter on a lat/lon box; expiry filters every query.
CREATE INDEX reports_lat_lon_idx ON reports (lat, lon);
CREATE INDEX reports_expires_idx ON reports (expires_at);
CREATE INDEX reports_reporter_idx ON reports (reporter, created_at);

-- One vote per device per report, enforced by the primary key.
CREATE TABLE votes (
    report_id    UUID NOT NULL REFERENCES reports (id) ON DELETE CASCADE,
    device       VARCHAR(64) NOT NULL,
    still_there  BOOLEAN NOT NULL,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (report_id, device)
);
