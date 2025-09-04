pipeline {
  agent any
  options { timestamps() }

  /******** Parameters ********/
  parameters {
    choice(name: 'ENV', choices: ['dev', 'qa', 'staging', 'prod'], description: 'Target environment')
    string(name: 'RF_HOST', defaultValue: '0.0.0.0', description: 'KeywordServer bind host')
    string(name: 'RF_PORT', defaultValue: '8270', description: 'KeywordServer port')
    string(name: 'BASE',    defaultValue: 'https://httpbin.org', description: 'Base API URL for tests')
    string(name: 'PROCESSES', defaultValue: '4', description: 'pabot parallel processes')
    // Python installation is configurable per node
    string(name: 'PY_HOME', defaultValue: 'C:\\Users\\Anjaly\\AppData\\Local\\Programs\\Python\\Python313',
           description: 'Python home (folder with python.exe and Scripts)')
  }

  /******** Environment ********/
  environment {
    RF_HOST = "${params.RF_HOST}"
    RF_PORT = "${params.RF_PORT}"
    BASE    = "${params.BASE}"

    ALLURE_RESULTS = 'results/allure'
    ROBOT_RESULTS  = 'results/robot'

    PY_HOME     = "${params.PY_HOME}"
    PYTHON_EXE  = "${env.PY_HOME}\\python.exe"
    PY_SCRIPTS  = "${env.PY_HOME}\\Scripts"

    JAVA_TOOL_OPTIONS = '-Dfile.encoding=UTF-8'
  }

  stages {
    stage('Checkout') {
      steps { checkout scm }
    }

    stage('Build KeywordServer (Maven)') {
      steps { bat 'mvn -B -DskipTests -pl server -am clean package' }
    }

    stage('Start KeywordServer') {
      steps {
        // Detached start + robust readiness probe; Groovy-safe strings
        powershell '''
          $ErrorActionPreference = "Stop"

          $ws        = $env:WORKSPACE
          $targetDir = Join-Path $ws "server\\target"
          $jar       = Join-Path $targetDir "rf-keywords-rbc-1.0.0.jar"
          if (!(Test-Path $jar)) { throw "KeywordServer jar not found: $jar" }

          $stdout    = Join-Path $targetDir "keywordserver.out.log"
          $stderr    = Join-Path $targetDir "keywordserver.err.log"
          $pidFile   = Join-Path $targetDir "keywordserver.pid"

          # Stop previous instance via PID file (best effort)
          if (Test-Path $pidFile) {
            try {
              $oldPid = Get-Content $pidFile | Select-Object -First 1
              if ($oldPid) { Stop-Process -Id ([int]$oldPid) -Force -ErrorAction SilentlyContinue }
            } catch { }
            Remove-Item $pidFile -Force -ErrorAction SilentlyContinue
          }

          # Free RF_PORT if an orphan is listening
          try {
            $conn = Get-NetTCPConnection -State Listen -LocalPort ([int]$env:RF_PORT) -ErrorAction Stop | Select-Object -First 1
            if ($conn) {
              try { Stop-Process -Id $conn.OwningProcess -Force -ErrorAction SilentlyContinue } catch { }
              Start-Sleep -Seconds 2
            }
          } catch {
            # Fallback: netstat parsing (avoid \\s to keep Groovy parser happy)
            $pattern = ("LISTENING.*:{0} " -f $env:RF_PORT)  # literal space after port
            $line = netstat -ano | Select-String -Pattern $pattern
            if ($line) {
              $parts = ($line.ToString() -split "\\s+") | Where-Object { $_ -ne "" }
              $pidCol = $parts[-1]
              if ($pidCol -match '^\\d+$') {
                try { Stop-Process -Id ([int]$pidCol) -Force -ErrorAction SilentlyContinue } catch { }
                Start-Sleep -Seconds 2
              }
            }
          }

          # Start detached with host/port props
          $java = (Get-Command java).Source
          $args = @("-Drf.port=$($env:RF_PORT)", "-Drf.host=$($env:RF_HOST)", "-jar", "`"$jar`"")
          Write-Host ("Starting KeywordServer: {0} on {1}:{2}" -f $jar, $env:RF_HOST, $env:RF_PORT)

          $p = Start-Process -FilePath $java `
                             -ArgumentList $args `
                             -WorkingDirectory $targetDir `
                             -WindowStyle Hidden `
                             -RedirectStandardOutput $stdout `
                             -RedirectStandardError  $stderr `
                             -PassThru

          ($p.Id).ToString() | Set-Content -Path $pidFile -Encoding ascii

          # Probe 127.0.0.1 when binding to 0.0.0.0
          $probeHost = if ($env:RF_HOST -eq '0.0.0.0' -or [string]::IsNullOrWhiteSpace($env:RF_HOST)) { '127.0.0.1' } else { $env:RF_HOST }

          $deadline = (Get-Date).AddSeconds(120)
          $ok = $false
          do {
            Start-Sleep -Seconds 2
            try { $ok = Test-NetConnection -ComputerName $probeHost -Port ([int]$env:RF_PORT) -InformationLevel Quiet }
            catch { $ok = $false }
          } until ($ok -or (Get-Date) -gt $deadline)

          if (-not $ok) {
            Write-Warning "KeywordServer not ready. Recent stderr tail:"
            if (Test-Path $stderr) { Get-Content $stderr -Tail 100 | Write-Host }
            throw ("KeywordServer not ready on {0}:{1}" -f $probeHost, $env:RF_PORT)
          }

          Write-Host ("KeywordServer is accepting connections on {0}:{1} (PID={2})" -f $probeHost, $env:RF_PORT, $p.Id)

          # Ensure the step returns so downstream stages run
          exit 0
        '''
      }
    }

    stage('Run Robot (pabot)') {
      steps {
        bat '''
          if not exist "%ALLURE_RESULTS%" mkdir "%ALLURE_RESULTS%"
          if not exist "%ROBOT_RESULTS%"  mkdir "%ROBOT_RESULTS%"

          "%PYTHON_EXE%" -m pip install -U pip
          "%PYTHON_EXE%" -m pip install -U robotframework robotframework-pabot allure-robotframework

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
        '''
      }
    }

    stage('Publish Allure Report') {
      steps {
        allure includeProperties: false, jdk: '', reportBuildPolicy: 'ALWAYS',
               results: [[path: "${env.ALLURE_RESULTS}"]]
      }
    }
  }

  post {
    always {
      archiveArtifacts artifacts: 'server/target/*.jar, server/target/keywordserver.*.log, results/**', fingerprint: true
      junit allowEmptyResults: true, testResults: 'results/robot/output*.xml'

      // Clean shutdown using saved PID (avoid reserved $PID)
      powershell '''
        $ErrorActionPreference = "SilentlyContinue"
        $pidFile = Join-Path $env:WORKSPACE "server\\target\\keywordserver.pid"
        if (Test-Path $pidFile) {
          $ksPid = Get-Content $pidFile | Select-Object -First 1
          if ($ksPid) { Stop-Process -Id ([int]$ksPid) -Force -ErrorAction SilentlyContinue }
          Remove-Item $pidFile -Force -ErrorAction SilentlyContinue
        }
      '''
    }
  }
}