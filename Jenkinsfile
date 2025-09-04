pipeline {
  agent any

  parameters {
    choice(name: 'ENV', choices: ['dev', 'qa', 'staging', 'prod'], description: 'Target environment')
    string(name: 'RF_HOST', defaultValue: '0.0.0.0', description: 'KeywordServer bind host')
    string(name: 'RF_PORT', defaultValue: '8270', description: 'KeywordServer port')
    string(name: 'BASE',    defaultValue: 'https://httpbin.org', description: 'Base API URL for tests')
    string(name: 'PROCESSES', defaultValue: '4', description: 'pabot parallel processes')
    // NEW: make the Python install configurable per machine
    string(name: 'PYTHON_HOME', defaultValue: 'C:\\Users\\Anjaly\\AppData\\Local\\Programs\\Python\\Python313', description: 'Python root (contains python.exe and Scripts)')
  }

  environment {
    // expose parameters to env
    RF_HOST = "${params.RF_HOST}"
    RF_PORT = "${params.RF_PORT}"
    BASE    = "${params.BASE}"

    // results dirs
    ALLURE_RESULTS = 'results/allure'
    ROBOT_RESULTS  = 'results/robot'

    // ensure we call the exact interpreter the user gave us
    PYTHON_EXE = "${params.PYTHON_HOME}\\python.exe"
    SCRIPTS_DIR = "${params.PYTHON_HOME}\\Scripts"

    JAVA_TOOL_OPTIONS = '-Dfile.encoding=UTF-8'
    // make sure python & scripts are first in PATH for this run
    PATH = "${params.PYTHON_HOME};${params.PYTHON_HOME}\\Scripts;${env.PATH}"
  }

  options {
    timestamps()
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
        powershell '''
          $ErrorActionPreference = "Stop"
          $java      = (Get-Command java).Source
          $jar       = Join-Path $PWD "server\\target\\rf-keywords-rbc-1.0.0.jar"
          $bindHost  = $env:RF_HOST
          $port      = [int]$env:RF_PORT

          Write-Host "Starting KeywordServer: $jar on $port (bind $bindHost)"

          # Launch in background; separate out/err files (do NOT use same file)
          $outFile = "server-out.log"
          $errFile = "server-err.log"

          $args = @("-Drf.host=$bindHost","-Drf.port=$port","-jar",$jar)

          $p = Start-Process -FilePath $java `
                             -ArgumentList $args `
                             -WorkingDirectory $PWD `
                             -WindowStyle Hidden `
                             -RedirectStandardOutput $outFile `
                             -RedirectStandardError  $errFile `
                             -PassThru

          # Save PID (avoid using $pid which is reserved)
          Set-Content -Path "server.pid" -Value $p.Id -Encoding ascii

          # Probe 127.0.0.1:<port> (we always probe loopback, never 0.0.0.0)
          $deadline = (Get-Date).AddSeconds(30)
          $ok = $false
          while((Get-Date) -lt $deadline) {
            try {
              $client = New-Object System.Net.Sockets.TcpClient
              $iar = $client.BeginConnect("127.0.0.1", $port, $null, $null)
              $connected = $iar.AsyncWaitHandle.WaitOne(1000)
              if ($connected -and $client.Connected) { $ok = $true; $client.Close(); break }
              $client.Close()
            } catch { Start-Sleep -Milliseconds 500 }
          }
          if (-not $ok) {
            Write-Host "---- server-err.log ----"
            if (Test-Path $errFile) { Get-Content $errFile | Select-Object -Last 200 }
            throw "KeywordServer not ready on port $port"
          } else {
            Write-Host "KeywordServer is accepting connections on 127.0.0.1:$port"
          }
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

          "%SCRIPTS_DIR%\\pabot.exe" ^
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
      when { expression { fileExists(env.ALLURE_RESULTS) } }
      steps {
        // keep your Allure publishing as before; Jenkins Allure plugin will pick up this dir
        allure([
          results: [[path: "${ALLURE_RESULTS}"]],
          reportBuildPolicy: 'ALWAYS'
        ])
      }
    }
  }

  post {
    always {
      archiveArtifacts artifacts: 'results/**/*, server-*.log, server.pid', fingerprint: true
      junit allowEmptyResults: true, testResults: 'results/robot/*.xml'

      // Stop KeywordServer by PID (no WMI queries, no reserved $pid)
      powershell '''
        if (Test-Path "server.pid") {
          try {
            $serverPid = Get-Content "server.pid" | Select-Object -First 1
            if ($serverPid) { Stop-Process -Id $serverPid -Force -ErrorAction SilentlyContinue }
          } catch { }
        }
      '''
    }
  }
}
