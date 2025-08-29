*** Settings ***
Library  Remote  http://127.0.0.1:8270/fix  WITH NAME  FIX

*** Test Cases ***
Send NOS and await ER
    [Tags]    requires_fix_acceptor
    # Absolute path or classpath:fixInitiator.cfg (if bundled in JAR)
    FIX.Start Initiator      C:/Users/Anjaly/OneDrive/Documents/Rohit/rf-java-rbc/server/src/main/resources/fixInitiator.cfg
    FIX.Await Logon          15
    FIX.Send NOS             IBM   100   BUY   150.25
    ${msg}=    FIX.Await Execution Report    20
    Log To Console           ${msg}
    FIX.Stop Initiator