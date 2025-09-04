pipeline {
  agent any
  options { timestamps() }

  parameters {
    choice(name: 'ENV', choices: ['dev', 'qa', 'staging', 'prod'], description: 'Target environment')
    string(name: 'RF_HOST', defaultValue: '0.0.0.0', description: 'KeywordServer bind host')
    string(name: 'RF_PORT', defaultValue: '8270', description: 'KeywordServer port')
    string(name: 'BASE',    defaultValue: 'https://httpbin.org', description: 'Base API URL for tests')
    string(name: 'PROCESSES', defaultValue: '4', description: 'pabot parallel processes')

    // NEW: make Python location configurable per machine (per your paths)
    string(name: 'WIN_PYTHON_HOME',
           defaultValue: 'C:\\Users\\Anjaly\\AppData\\Local\\Programs\\Python\\Python313',
           description: 'Folder containing python.exe and the Scripts subfolder')
  }

  environment {
    // Defaults; can be overridden by parameters above
    RF_HOST = "${params.RF_HOST}"
    RF_PORT = "${params.RF_PORT}"
    BASE    = "${params.BASE}"

    // POSIX-style for tools that prefer forward slashes
    ALLURE_RESULTS = 'results/allure'
    ROBOT_RESULTS  = 'results/robot'

    // Make Python/Scripts easy to reference on Windows
    WIN_PYTHON_HOME = "${params.WIN_PYTHON_HOME}"
    PYTHON_EXE      = "${params.WIN_PYTHON_HOME}\\python.exe"
    PY_SCRIPTS_DIR  = "${params.WIN_PYTHON_HOME}\\Scripts"

    JAVA_TOOL_OPTIONS = '-Dfile.encoding=UTF-8'
  }

  stages {
    stage('Checkout') {
      steps { checkout scm }
    }

    stage('Build KeywordServer (Maven)') {
      steps {
        bat 'mvn -B -DskipTests -pl server -am clean package'
      }
    }

    stage('Start KeywordServer') {
      steps {
        powershell '''
          $ErrorActionPreference = "Stop"

          $targetDir = Join-Path $env:WORKSPACE "server\\target"
          $jar = Get-ChildItem -Path $targetDir -Filter "rf-keywords-rbc-*.jar" |
                 Sort-Object LastWriteTime -Descending | Select-Object -First 1
          if (-not $jar) { throw "KeywordServer jar not found under $targetDir" }

          $java = "java"
          $args = @("-Drf.host=$env:RF_HOST","-Drf.port=$env:RF_PORT","-jar",$jar.FullName)

          Write-Host "Starting KeywordServer: $($jar.FullName) on $env:RF_PORT (bind $env:RF_HOST)"
          $p = Start-Process -FilePath $java -ArgumentList $args -WindowStyle Hidden -PassThru
          Set-Content -Path "server.pid" -Value $p.Id

          # Probe 127.0.0.1 regardless of bind to avoid 0.0.0.0 name resolution noise
          $deadline = (Get-Date).AddSeconds(20)
          do {
            Start-Sleep -Seconds 1
            try {
              $ok = Test-NetConnection -ComputerName '127.0.0.1' -Port [int]$env:RF_PORT -InformationLevel Quiet
              if ($ok) { break }
            } catch { $ok = $false }
          } while ((Get-Date) -lt $deadline)

          if (-not $ok) { throw "KeywordServer not ready on port $env:RF_PORT" }
          Write-Host "KeywordServer is accepting connections on 127.0.0.1:$($env:RF_PORT)"
        '''
      }
    }

    stage('Run Robot (pabot)') {
      steps {
        bat '''
          if not exist "%PYTHON_EXE%" (
            echo ERROR: Python not found at "%PYTHON_EXE%"
            exit /b 1
          )

          "%PYTHON_EXE%" -m pip install -U pip
          "%PYTHON_EXE%" -m pip install robotframework robotframework-pabot allure-robotframework

          if not exist "results\\allure" mkdir "results\\allure"
          if not exist "results\\robot"  mkdir "results\\robot"

          rem *** IMPORTANT: On Windows use Scripts\\pabot.exe (not "python -m pabot") ***
          "%PY_SCRIPTS_DIR%\\pabot.exe" --processes %PROCESSES% --testlevelsplit ^
            --listener "allure_robotframework;results/allure" ^
            --outputdir "results/robot" ^
            api_smoke.robot sql_demo.robot fix_demo.robot
        '''
      }
    }

    stage('Publish Allure Report') {
      steps {
        allure includeProperties: false, jdk: '', reportBuildPolicy: 'ALWAYS',
               results: [[path: 'results/allure']]
      }
    }
  }

  post {
    always {
      // Save logs and XML even if tests fail
      archiveArtifacts artifacts: 'results/**', fingerprint: true
      junit allowEmptyResults: true, testResults: 'results/robot/output*.xml'

      // Cleanly stop KeywordServer
      powershell '''
        if (Test-Path "server.pid") {
          $pid = Get-Content "server.pid" | Select-Object -First 1
          try { Stop-Process -Id $pid -Force -ErrorAction SilentlyContinue } catch {}
          Remove-Item "server.pid" -Force -ErrorAction SilentlyContinue
        }
      '''
    }
  }
}