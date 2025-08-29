*** Settings ***
Library   Remote   http://127.0.0.1:8270/sql   WITH NAME   SQL
Resource  resources/variables.resource

*** Test Cases ***
Query items
    # alias 'mysql' is just a handle; name it as you like
    SQL.Connect    mysql    ${JDBC}    ${DB_USER}    ${DB_PASS}

    ${rows}=    SQL.Select    mysql    SELECT id, name FROM items ORDER BY id
    Log To Console    ${rows}

    SQL.Close    mysql