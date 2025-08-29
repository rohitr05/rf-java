*** Settings ***
Library   Remote   http://127.0.0.1:8270/rest   WITH NAME   REST
Library   Remote   http://127.0.0.1:8270/json   WITH NAME   JSON
Resource  resources/variables.resource

*** Test Cases ***
Echo GET should return 200 and args
    REST.Create API Session    echo    ${BASE}    &{EMPTY}
    # You can keep the query inline...
    REST.Get                   echo    /get?foo=bar    ${EMPTY}    last
    REST.Status Should Be      last    200
    # Works because library now normalizes $.a.b -> a.b
    ${val}=    REST.Extract Json Path    last    $.args.foo
    Should Be Equal    ${val}    bar
    # Or even shorter:
    # REST.Json Path Should Be    last    $.args.foo    bar

POST body and save to file
    ${payload}=    Set Variable    {"hello":"world"}
    REST.Post              echo    /post    ${payload}    post1
    REST.Status Should Be  post1   200
    REST.Save Body         post1   out/api/post-response.json
    ${v}=    REST.Extract Json Path    post1    $.json.hello
    Should Be Equal        ${v}    world
    ${pretty}=    JSON.Pretty     ${payload}
    Log To Console         ${pretty}