pipeline {
  agent any

  options {
    timestamps()
    buildDiscarder(logRotator(numToKeepStr: '20'))
  }

  // Unchanged: your job parameters
  parameters {
    choice(name: 'ENV', choices: ['dev', 'qa', 'staging', 'prod'], description: 'Target environment')
    string(name: 'RF_HOST', defaultValue: '0.0.0.0', description: 'KeywordServer bind host')
    string(name: 'RF_PORT', defaultValue: '8270', description: 'KeywordServer port')
    string(name: 'BASE',    defaultValue: 'https://httpbin.org', description: 'Base API URL for tests')
    string(name: 'PROCESSES', defaultValue: '4', description: 'pabot parallel processes')
  }

  // Unchanged: your env section (add your Python vars here if you already had them)
  environment {
    RF_HOST = "${params.RF_HOST}"
    RF_PORT = "${params.RF_PORT}"
    BASE    = "${params.BASE}"

    ALLURE_RESULTS = 'results/allure'
    ROBOT_RESULTS  = 'results/robot'

    JAVA_TOOL_OPTIONS = '-Dfile.encoding=UTF-8'

    // (Optional but recommended – you already asked for this earlier)
    // PYTHON_HOME and SCRIPTS can be overridden per-node without editing stages.
    PYTHON_HOME   = 'C:\\Users\\Anjaly\\AppData\\Local\\Programs\\Python\\Python313'
    PYTHON_SCRIPTS = "${env.PYTHON_HOME}\\Scripts"
    PATH = "${env.PATH};${env.PYTHON_HOME};${env.PYTHON_SCRIPTS}"
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

          New-Item -ItemType Directory -Force -Path "$env:WORKSPACE/$env:ALLURE_RESULTS" | Out-Null
          New-Item -ItemType Directory -Force -Path "$env:WORKSPACE/$env:ROBOT_RESULTS"  | Out-Null

          # Shaded jar path (as built by your server/pom.xml)
          $jar = Join-Path $env:WORKSPACE "server\\target\\rf-keywords-rbc-1.0.0.jar"
          if (!(Test-Path $jar)) { throw "Shaded jar not found: $jar" }

          $log     = Join-Path $env:WORKSPACE "keywordserver.log"
          $pidFile = Join-Path $env:WORKSPACE "keywordserver.pid"

          # Start detached
          Write-Host "Starting KeywordServer: $jar on $env:RF_HOST:$env:RF_PORT"
          $java = (Get-Command java).Source
          $args = @('-jar', "`"$jar`"")
          $p = Start-Process -FilePath $java -ArgumentList $args -WorkingDirectory $env:WORKSPACE `
                 -PassThru -RedirectStandardOutput $log -RedirectStandardError $log

          $ksPid = $p.Id
          "$ksPid" | Set-Content -Path $pidFile -Encoding ascii

          # Use 127.0.0.1 when RF_HOST is 0.0.0.0 (Windows can't connect to 0.0.0.0)
          $probeHost = if ($env:RF_HOST -eq '0.0.0.0' -or [string]::IsNullOrWhiteSpace($env:RF_HOST)) { '127.0.0.1' } else { $env:RF_HOST }

          # Wait for port to open
          $deadline = (Get-Date).AddSeconds(40)
          $ok = $false
          do {
            Start-Sleep -Seconds 2
            $ok = Test-NetConnection -ComputerName $probeHost -Port ([int]$env:RF_PORT) -InformationLevel Quiet
          } until ($ok -or (Get-Date) -ge $deadline)

          if (-not $ok) { throw "KeywordServer not ready on $probeHost:$env:RF_PORT" }
          Write-Host "KeywordServer is accepting connections on $probeHost:$env:RF_PORT (PID=$ksPid)"

          # IMPORTANT: ensure the PowerShell step returns so the next stage runs.
          exit 0
        '''
      }
    }

    stage('Install Python deps') {
      steps {
        // If you keep a requirements.txt, this uses it; otherwise installs the known good set.
        bat '''
        if exist requirements.txt (
          python -m pip install -r requirements.txt
        ) else (
          python -m pip install --upgrade pip
          python -m pip install robotframework==7.3.2 robotframework-pabot==5.0.0 allure-robotframework==2.9.0 robotframework-requests
        )
        '''
      }
    }

    stage('Run Robot (pabot)') {
      steps {
        // Call the EXE directly so we’re not dependent on PATH shims
        bat """
        \\""${env.PYTHON_SCRIPTS}\\\\pabot.exe\\"" --no-pabotlib --processes ${params.PROCESSES} --testlevelsplit ^
          --listener \\"allure_robotframework;${env.ALLURE_RESULTS}\\" ^
          --outputdir \\"${env.ROBOT_RESULTS}\\" ^
          tests\\\\api_smoke.robot tests\\\\sql_demo.robot tests\\\\fix_demo.robot
        """
      }
    }

    stage('Publish Allure Report') {
      steps {
        // Requires the Allure Jenkins plugin; unchanged from your setup
        allure includeProperties: false, jdk: '', results: [[path: "${env.ALLURE_RESULTS}"]]
      }
    }
  }

  post {
    always {
      // Stop server if it is still running; avoid using reserved $PID variable name
      powershell '''
        $pidFile = Join-Path $env:WORKSPACE "keywordserver.pid"
        if (Test-Path $pidFile) {
          $ksPid = Get-Content $pidFile | Select-Object -First 1
          if ($ksPid) {
            Write-Host "Stopping KeywordServer PID=$ksPid"
            try { Stop-Process -Id ([int]$ksPid) -Force -ErrorAction Stop } catch { Write-Warning "Stop-Process failed: $_" }
          }
          Remove-Item $pidFile -Force
        } else {
          Write-Host "No PID file; nothing to stop."
        }
      '''
      archiveArtifacts artifacts: "${env.ROBOT_RESULTS}/**, ${env.ALLURE_RESULTS}/**", allowEmptyArchive: true
      junit allowEmptyResults: true, testResults: "${env.ROBOT_RESULTS}/output*.xml"
    }
  }
}