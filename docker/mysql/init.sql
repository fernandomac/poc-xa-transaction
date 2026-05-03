-- MySQL 8.0.31+ requires XA_RECOVER_ADMIN to call XA RECOVER.
-- Without it Atomikos recovery returns XAER_RMERR on every scan.
GRANT XA_RECOVER_ADMIN ON *.* TO 'xapoc'@'%';
FLUSH PRIVILEGES;
