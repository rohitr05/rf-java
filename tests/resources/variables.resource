*** Variables ***
${MYSQL_HOST}    127.0.0.1
${MYSQL_PORT}    3306
${MYSQL_DB}      rf_demo
${DB_USER}       rfuser
${DB_PASS}       S3cureP@ss!

# JDBC URL for MySQL 8
${JDBC}          jdbc:mysql://${MYSQL_HOST}:${MYSQL_PORT}/${MYSQL_DB}?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC
${BASE}      https://postman-echo.com
&{EMPTY}