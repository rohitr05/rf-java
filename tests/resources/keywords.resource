*** Settings ***
Library    Remote    http://127.0.0.1:8270/rest    WITH NAME    REST
Library    Remote    http://127.0.0.1:8270/json    WITH NAME    JSON

*** Keywords ***
Login And Get Token
    [Arguments]    ${base}    ${user}    ${pass}
    REST.Create API Session    auth    ${base}    {}
    ${payload}=    Set Variable    {"username":"${user}","password":"${pass}"}
    REST.Post    auth    /post    ${payload}    login
    REST.Status Should Be    login    200
    ${token}=    Set Variable    dummy-token   # echo service doesn't issue real tokens
    REST.Set Bearer    auth    ${token}
    RETURN    ${token}