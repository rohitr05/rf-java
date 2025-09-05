pipeline {
  agent any
  options { timestamps() }

  // Parameters
  parameters {
    choice(name: 'ENV', choices: ['dev','qa','staging','prod'], description: 'Target environment')
    string(name: 'RF_HOST', defaultValue: '0.0.0.0', description: 'KeywordServer bind host')
    string(name: 'RF_PORT', defaultValue: '8270', description: 'KeywordServer port')
    string(name: 'BASE', defaultValue: 'https://httpbin.org', description: 'Base API URL for tests')
    string(name: 'PROCESSES', defaultValue: '4', description: 'pabot parallel processes')
    // configurable Python installation
    string(name: 'PY_HOME', defaultValue: 'C:\\Users\\Anjaly\\AppData\\Local\\Programs\\Python\\Python313',
           description: 'Python home (contains python.exe and Scripts)')
  }

  // Environment setup
  environment {
    RF_HOST = "${params.RF_HOST}"
    RF_PORT = "${params.RF_PORT}"
    BASE    = "${params.BASE}"
    PY_HOME = "${params.PY_HOME}"
    PYTHON_EXE = "${env.PY_HOME}\\python.exe"
    PY_SCRIPTS = "${env.PY_HOME}\\Scripts"
    ALLURE_RESULTS = 'results/allure'
    ROBOT_RESULTS  = 'results/robot'
    JAVA_TOOL_OPTIONS = '-Dfile.encoding=UTF-8'
  }

  stages {
    stage('Checkout') {
      steps {
        checkout scm
      }
    }

    stage('Build KeywordServer (Maven)') {
      steps {
        bat 'mvn -B -DskipTests -pl server -am clean package'
      }
    }

    stage('Start KeywordServer') {
      steps {
        // Start the jar in the background via cmd; this returns immediately
        bat """
          setlocal
          for %%f in (server\\target\\rf-keywords-rbc-*.jar) do set JAR_FILE=%%f
          echo Starting KeywordServer: %%JAR_FILE%% on %RF_HOST%:%RF_PORT%
          start "" /b java -Drf.host=%RF_HOST% -Drf.port=%RF_PORT% -jar "%%JAR_FILE%%" > server\\target\\keywordserver.out.log 2> server\\target\\keywordserver.err.log
          endlocal
        """
        // Poll the port in a separate PowerShell step; if unreachable, fail the stage
        powershell """
          $probeHost = if ($env:RF_HOST -eq '0.0.0.0' -or [string]::IsNullOrWhiteSpace($env:RF_HOST)) { '127.0.0.1' } else { $env:RF_HOST }
          $deadline = (Get-Date).AddSeconds(120)
          $ok = $false
          do {
            Start-Sleep -Seconds 2
            try {
              $ok = Test-NetConnection -ComputerName $probeHost -Port ([int]$env:RF_PORT) -InformationLevel Quiet
            } catch {
              $ok = $false
            }
          } until ($ok -or (Get-Date) -gt $deadline)
          if (-not $ok) {
            throw "KeywordServer not ready on $probeHost:$env:RF_PORT"
          }
        """
      }
    }

    stage('Run Robot (pabot)') {
      steps {
        bat """
          if not exist "%ALLURE_RESULTS%" mkdir "%ALLURE_RESULTS%"
          if not exist "%ROBOT_RESULTS%"  mkdir "%ROBOT_RESULTS%"

          "%PYTHON_EXE%" -m pip install -U pip
          "%PYTHON_EXE%" -m pip install -U robotframework robotframework-pabot allure-robotframework
          
          set PATH=%PY_SCRIPTS%;%PY_HOME%;%PATH%
          

          "%PY_SCRIPTS%\\pabot.exe" ^
            --no-pabotlib ^
            --processes %PROCESSES% ^
            --testlevelsplit ^
            --listener "allure_robotframework;%ALLURE_RESULTS%" ^
            --outputdir "%ROBOT_RESULTS%" ^
            tests\\api_smoke.robot tests\\sql_demo.robot tests\\fix_demo.robot

          if errorlevel 1 (
            echo ========= Worker stderr (if any) =========
            for /R "%ROBOT_RESULTS%\\pabot_results" %%F in (robot_stderr.out) do @echo --- %%F --- & type "%%F"
            exit /b 1
          )
        """
      }
    }

    stage('Publish Allure Report') {
      steps {
        allure includeProperties: false,
               jdk: '',
               reportBuildPolicy: 'ALWAYS',
               results: [[path: "${env.ALLURE_RESULTS}"]]
      }
    }
  }

  post {
    always {
      archiveArtifacts artifacts: 'server/target/*.jar, server/target/keywordserver.*.log, results/**', fingerprint: true
      junit allowEmptyResults: true, testResults: 'results/robot/output*.xml'

      // Best-effort cleanup: stop any java process with our rf.port
      powershell '''
        try {
          $procs = Get-WmiObject -Class Win32_Process | Where-Object { $_.CommandLine -like "*-Drf.port=$env:RF_PORT*" }
          foreach ($proc in $procs) { Stop-Process -Id $proc.ProcessId -Force -ErrorAction SilentlyContinue }
        } catch {}
      '''
    }
  }
}