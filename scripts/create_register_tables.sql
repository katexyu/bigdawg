DROP TABLE IF EXISTS clientalert, dbalert, alertevent ;


CREATE TABLE IF NOT EXISTS dbalert (
  dbAlertID serial PRIMARY KEY,
  stroredProc varchar(250),
  oneTime boolean default false,
  receiveURL varchar(250),
  created timestamp default now()
);


CREATE TABLE IF NOT EXISTS clientalert (
  clientAlertID serial PRIMARY KEY,
  dbAlertID int references dbalert(dbAlertID),
  push boolean not null,
  oneTime boolean default false,
  active boolean default true,
  pulled boolean default false,
  lastPullTime timestamp,
  pushURL varchar(250),
  created timestamp default now()
);

CREATE TABLE IF NOT EXISTS alertevent (
  alertID serial PRIMARY KEY,
  dbAlertID int references dbalert(dbAlertID),
  ts timestamp default now(),
  body varchar(250)
);

CREATE TABLE IF NOT EXISTS monitoring (
  monitorID serial PRIMARY KEY,
  island varchar(50),
  query varchar(2000),
  lastRan int,
  duration int
);
