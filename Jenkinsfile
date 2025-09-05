pipeline {
  agent any
  options { timestamps() }

  parameters {
    choice(name: 'ENV', choices: ['dev', 'qa', 'staging', 'prod'], description: 'Target environment')
    string(name: 'RF_HOST', defaultValue: '0.0.0.0', description: 'KeywordServer bind host')
    string(name: 'RF_PORT', defaultValue: '8270', description: 'KeywordServer port')
    string(name: 'BASE',    defaultValue: 'https://httpbin.org', description: 'Base API URL for tests')
    string(name: 'PROCESSES', defaultValue: '4', description: 'pabot parallel processes')
    // Windows Python root (so it can be changed per agent without editing the file)
    string(name: 'PY_HOME', defaultValue: 'C:\\Users\\Anjaly\\AppData\\Local\\Programs\\Python\\Python313', description: 'Root folder containing python.exe and Scripts\\')
  }

  environment {
    RF_HOST = "${params.RF_HOST}"
    RF_PORT = "${params.RF_PORT}"
    BASE    = "${params.BASE}"

    PY_HOME = "${params.PY_HOME}"
    PATH    = "${env.PATH};${env.PY_HOME};${env.PY_HOME}\\Scripts"

    ALLURE_RESULTS = 'results/allure'
    ROBOT_RESULTS  = 'results/robot'

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
        // Triple single quotes => Groovy won’t try to interpolate PowerShell variables ($false, $env:..., etc.)
        powershell(script: '''
          $ErrorActionPreference = "Stop"

          # Find the newest jar in server\\target (works for shaded/non-shaded)
          $targetDir = "server\\target"
          if (-not (Test-Path $targetDir)) { throw "Target folder not found: $targetDir" }

          $jar = Get-ChildItem -Path $targetDir -Filter "rf-keywords-rbc-*.jar" |
                 Sort-Object LastWriteTime -Descending | Select-Object -First 1
          if (-not $jar) { throw "Server jar not found in $targetDir" }

          $java = if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin\\java.exe"))) {
                     Join-Path $env:JAVA_HOME "bin\\java.exe"
                  } else { "java" }

          $args = @(
            "-Dlog4j2.is.threadcontextmapinheritable=true",
            "-Dserver.host=$($env:RF_HOST)",
            "-Dserver.port=$($env:RF_PORT)",
            "-Dapi.base=$($env:BASE)",
            "-jar", $jar.FullName
          )

          Write-Host "Starting KeywordServer: $($jar.FullName) on $($env:RF_HOST):$($env:RF_PORT)"

          $serverOut = "server.out.log"
          $serverErr = "server.err.log"

          $p = Start-Process -FilePath $java `
                             -ArgumentList $args `
                             -WorkingDirectory (Resolve-Path ".") `
                             -WindowStyle Hidden `
                             -PassThru `
                             -RedirectStandardOutput $serverOut `
                             -RedirectStandardError  $serverErr

          # Save PID (avoid using special $PID variable)
          Set-Content -Path "server.pid" -Value $p.Id

          # Always probe loopback to avoid 0.0.0.0 resolution
          $probeHost = "127.0.0.1"
          $deadline  = (Get-Date).AddMinutes(2)
          $ok = $false

          do {
            Start-Sleep -Seconds 2
            try {
              # Preferred probe
              $t = Test-NetConnection -ComputerName $probeHost -Port ([int]$env:RF_PORT) -WarningAction SilentlyContinue
              $ok = $t.TcpTestSucceeded
              if (-not $ok) {
                # Fallback using netstat (PS v4 environments)
                $net = netstat -ano | Select-String -Pattern ("LISTENING.*:{0}\\s" -f $env:RF_PORT)
                if ($net) { $ok = $true }
              }
            } catch { $ok = $false }
          } until ($ok -or (Get-Date) -ge $deadline)

          if (-not $ok) {
            throw "KeywordServer not ready on $($probeHost):$($env:RF_PORT)"
          }

          Write-Host "KeywordServer is accepting connections on $($probeHost):$($env:RF_PORT) (PID=$($p.Id))"
        ''')
      }
    }

    stage('Run Robot (pabot)') {
      steps {
        bat """
          if not exist "results\\allure" mkdir "results\\allure"
          if not exist "results\\robot"  mkdir "results\\robot"

          "%PY_HOME%\\python.exe" -m pip install -U pip
          "%PY_HOME%\\python.exe" -m pip install robotframework robotframework-pabot allure-robotframework requests mysql-connector-python

          "%PY_HOME%\\Scripts\\pabot.exe" --no-pabotlib --processes ${params.PROCESSES} --testlevelsplit ^
            --listener "allure_robotframework;results/allure" ^
            --outputdir "results/robot" ^
            tests\\api_smoke.robot tests\\sql_demo.robot tests\\fix_demo.robot
        """
      }
    }

    stage('Publish Allure Report') {
      steps {
        // Requires Jenkins Allure plugin
        allure includeProperties: false, jdk: '', results: [[path: 'results/allure']]
      }
    }
  }

  post {
    always {
      archiveArtifacts artifacts: 'results/**, server.out.log, server.err.log, server.pid', fingerprint: true
      powershell(script: '''
        $ErrorActionPreference = "SilentlyContinue"
        if (Test-Path "server.pid") {
          $sid = Get-Content "server.pid" | Select-Object -First 1
          if ($sid) { Stop-Process -Id $sid -Force -ErrorAction SilentlyContinue }
          Remove-Item -Force "server.pid"
        }
      ''')
    }
    cleanup {
      cleanWs()
    }
  }
}