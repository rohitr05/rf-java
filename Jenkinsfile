pipeline {
  agent any
  options {
    timestamps()
    skipDefaultCheckout(true)
  }

  parameters {
    choice(name: 'ENV', choices: ['dev', 'qa', 'staging', 'prod'], description: 'Target environment')
    string(name: 'RF_HOST', defaultValue: '0.0.0.0', description: 'KeywordServer bind host')
    string(name: 'RF_PORT', defaultValue: '8270', description: 'KeywordServer port')
    string(name: 'BASE',    defaultValue: 'https://httpbin.org', description: 'Base API URL for tests')
    string(name: 'PROCESSES', defaultValue: '4', description: 'pabot parallel processes')
    // Windows Python locations (editable per machine)
    string(name: 'PYTHON_HOME',    defaultValue: 'C:\\Users\\Anjaly\\AppData\\Local\\Programs\\Python\\Python313', description: 'Python installation dir')
    string(name: 'PYTHON_SCRIPTS', defaultValue: 'C:\\Users\\Anjaly\\AppData\\Local\\Programs\\Python\\Python313\\Scripts', description: 'Python Scripts dir')
  }

  environment {
    // Defaults (driven by parameters)
    RF_HOST = "${params.RF_HOST}"
    RF_PORT = "${params.RF_PORT}"
    BASE    = "${params.BASE}"
    PROCESSES = "${params.PROCESSES}"

    // Results (forward slashes are OK on Windows, and friendlier for tools)
    ALLURE_RESULTS = 'results/allure'
    ROBOT_RESULTS  = 'results/robot'

    // Encoding for Maven/Java build
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
        // Start detached, probe loopback (127.0.0.1), then return
        powershell '''
          $ErrorActionPreference = "Stop"

          $ws   = $env:WORKSPACE
          $jarDir = Join-Path $ws "server\\target"
          $jar = Get-ChildItem -Path $jarDir -Filter "*-shaded.jar" -ErrorAction SilentlyContinue | Select-Object -First 1
          if (-not $jar) {
            $jar = Get-ChildItem -Path $jarDir -Filter "*.jar" -ErrorAction SilentlyContinue | Where-Object { $_.Name -notmatch "-sources|-javadoc" } | Select-Object -First 1
          }
          if (-not $jar) {
            throw "Could not find server jar in $jarDir"
          }

          $java = (Get-Command java).Source
          if (-not $java) { throw "java not found in PATH" }

          $bindHost  = $env:RF_HOST
          $probeHost = "127.0.0.1"

          $args = @(
            "-jar", $jar.FullName,
            "--host", $bindHost,
            "--port", $env:RF_PORT
          )

          Write-Host ("Starting KeywordServer: {0} on {1}:{2}" -f $jar.FullName, $bindHost, $env:RF_PORT)

          # Start detached (no -Wait); WindowStyle Hidden prevents console pop-ups on Windows agents
          $p = Start-Process -FilePath $java -ArgumentList $args -WorkingDirectory $jarDir -WindowStyle Hidden -PassThru

          # Persist PID for later cleanup
          Set-Content -Path (Join-Path $ws "server.pid") -Value $p.Id

          # Readiness probe on loopback only (never probe 0.0.0.0)
          $deadline = (Get-Date).AddMinutes(2)
          $ok = $false
          do {
            try {
              $ok = Test-NetConnection -ComputerName $probeHost -Port $env:RF_PORT -InformationLevel Quiet
            } catch {
              $ok = $false
            }
            if (-not $ok) { Start-Sleep -Seconds 2 }
          } until ($ok -or (Get-Date) -gt $deadline)

          if (-not $ok) {
            throw ("KeywordServer not ready on {0}:{1}" -f $probeHost, $env:RF_PORT)
          }

          Write-Host ("KeywordServer is accepting connections on {0}:{1} (PID={2})" -f $probeHost, $env:RF_PORT, $p.Id)
          # PowerShell step ends here; process stays running in background for next stages
        '''
      }
    }

    stage('Setup Python') {
      steps {
        bat """
          if not exist "%PYTHON_HOME%\\python.exe" (
            echo Python not found at %PYTHON_HOME%\\python.exe
            exit /b 1
          )
          set "PATH=%PYTHON_HOME%;%PYTHON_SCRIPTS%;%PATH%"
          "%PYTHON_SCRIPTS%\\pip.exe" --version
          "%PYTHON_SCRIPTS%\\pip.exe" install --upgrade pip
          "%PYTHON_SCRIPTS%\\pip.exe" install --disable-pip-version-check robotframework robotframework-pabot allure-robotframework
        """
      }
    }

    stage('Run Robot (pabot)') {
      steps {
        bat """
          setlocal
          set "PATH=%PYTHON_HOME%;%PYTHON_SCRIPTS%;%PATH%"
          if not exist "%ALLURE_RESULTS%" mkdir "%ALLURE_RESULTS%"
          if not exist "%ROBOT_RESULTS%"  mkdir "%ROBOT_RESULTS%"

          "%PYTHON_SCRIPTS%\\pabot.exe" ^
            --no-pabotlib ^
            --processes %PROCESSES% ^
            --testlevelsplit ^
            --listener "allure_robotframework;%ALLURE_RESULTS%" ^
            --outputdir "%ROBOT_RESULTS%" ^
            tests\\api_smoke.robot tests\\sql_demo.robot tests\\fix_demo.robot

          endlocal
        """
      }
    }

    stage('Publish Allure Report') {
      steps {
        // Requires the Jenkins Allure Plugin to be installed
        allure([
          includeProperties: false,
          jdk: '',
          reportBuildPolicy: 'ALWAYS',
          results: [[path: "${env.ALLURE_RESULTS}"]]
        ])
      }
    }
  }

  post {
    always {
      // Archive run artifacts (don’t fail build if empty)
      archiveArtifacts artifacts: 'results/**/*', allowEmptyArchive: true

      // Robot's output.xml is not JUnit; keep non-fatal
      junit allowEmptyResults: true, testResults: "${env.ROBOT_RESULTS}/**/*.xml"

      // Best-effort server cleanup
      powershell '''
        $ws = $env:WORKSPACE
        $pidFile = Join-Path $ws "server.pid"
        if (Test-Path $pidFile) {
          $serverPid = Get-Content $pidFile | Select-Object -First 1
          if ($serverPid) {
            try {
              $proc = Get-Process -Id $serverPid -ErrorAction SilentlyContinue
              if ($proc) { Stop-Process -Id $serverPid -Force -ErrorAction SilentlyContinue }
            } catch { }
          }
        }
      '''
    }
  }
}
