-- H2 only: Postgres has num_nonnulls built in (used by V32).
CREATE ALIAS IF NOT EXISTS NUM_NONNULLS DETERMINISTIC FOR 'org.andre.construx.H2Functions.numNonnulls';
