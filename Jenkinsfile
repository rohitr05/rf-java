// Jenkinsfile — Windows-native, AnsiColor, Environment switching, pabot x4, Allure
pipeline {
  agent any

  // ---- Runtime parameters for easy environment switching ----
  parameters {
    choice(name: 'ENV', choices: ['dev', 'qa', 'stage', 'prod'], description: 'Target environment profile')
    string(name: 'RF_HOST_OVERRIDE',  defaultValue: '', description: 'Optional override for RF_HOST (blank = use profile)')
    string(name: 'RF_PORT_OVERRIDE',  defaultValue: '', description: 'Optional override for RF_PORT (blank = use profile)')
    string(name: 'BASE_OVERRIDE',     defaultValue: '', description: 'Optional override for BASE URL (blank = use profile)')
    string(name: 'ALLURE_RESULTS_DIR', defaultValue: 'results/allure', description: 'Where Allure raw results go')
    string(name: 'ROBOT_RESULTS_DIR',  defaultValue: 'results/robot',  description: 'Where Robot HTML & XML go')
  }

  options {
    timestamps()
    ansiColor('xterm')    // pretty, colored console
    buildDiscarder(logRotator(numToKeepStr: '20'))
  }

  environment {
    // These will be filled in the 'Init Environment' stage using the chosen profile + overrides
    RF_HOST = ''
    RF_PORT = ''
    BASE    = ''
    ALLURE_RESULTS = "${params.ALLURE_RESULTS_DIR}"
    ROBOT_RESULTS  = "${params.ROBOT_RESULTS_DIR}"
  }

  stages {
    stage('Checkout') {
      steps { checkout scm }
    }

    stage('Init Environment') {
      steps {
        script {
          // Centralized defaults for each environment profile.
          // Update only here when your endpoints change.
          def profiles = [
            dev  : [ RF_HOST: '0.0.0.0', RF_PORT: '8270', BASE: 'https://httpbin.org' ],
            qa   : [ RF_HOST: '0.0.0.0', RF_PORT: '8270', BASE: 'https://httpbin.org' ],
            stage: [ RF_HOST: '0.0.0.0', RF_PORT: '8270', BASE: 'https://httpbin.org' ],
            prod : [ RF_HOST: '0.0.0.0', RF_PORT: '8270', BASE: 'https://httpbin.org' ]
          ]
          def prof = profiles[params.ENV] ?: profiles.dev

          // Use overrides if provided, else profile defaults
          env.RF_HOST = params.RF_HOST_OVERRIDE?.trim() ? params.RF_HOST_OVERRIDE.trim() : prof.RF_HOST
          env.RF_PORT = params.RF_PORT_OVERRIDE?.trim() ? params.RF_PORT_OVERRIDE.trim() : prof.RF_PORT
          env.BASE    = params.BASE_OVERRIDE?.trim()    ? params.BASE_OVERRIDE.trim()    : prof.BASE

          echo "Selected ENV: ${params.ENV}"
          echo "RF_HOST=${env.RF_HOST}, RF_PORT=${env.RF_PORT}"
          echo "BASE=${env.BASE}"
          echo "ALLURE_RESULTS=${env.ALLURE_RESULTS}, ROBOT_RESULTS=${env.ROBOT_RESULTS}"
        }
      }
    }

    stage('Build KeywordServer (Maven)') {
      steps {
        bat 'mvn -B -DskipTests package'
      }
    }

    stage('Start KeywordServer') {
      steps {
        powershell '''
          $ErrorActionPreference = "Stop"
          New-Item -ItemType Directory -Force -Path "results" | Out-Null

          # Prefer the module path if present; fall back to any shaded jar
          $jar = Get-ChildItem -Path "server\\target" -Filter "*-shaded.jar" -ErrorAction SilentlyContinue | Select-Object -First 1
          if (-not $jar) { $jar = Get-ChildItem -Recurse -Filter "*-shaded.jar" | Select-Object -First 1 }
          if (-not $jar) { throw "Shaded jar not found. Ensure maven-shade-plugin is configured." }

          Write-Host "Starting KeywordServer: $($jar.FullName)"
          $args = "-Drf.port=$env:RF_PORT -Drf.host=$env:RF_HOST -jar `"$($jar.FullName)`""
          $proc = Start-Process -FilePath "java" -ArgumentList $args -PassThru -WindowStyle Hidden
          $proc.Id | Out-File -FilePath "keywordserver.pid" -Encoding ascii

          # Wait for /rest readiness (max 60s)
          $deadline = (Get-Date).AddSeconds(60)
          $ready = $false
          while ((Get-Date) -lt $deadline) {
            try {
              $resp = Invoke-WebRequest -UseBasicParsing -Uri ("http://127.0.0.1:{0}/rest" -f $env:RF_PORT) -TimeoutSec 2
              if ($resp.StatusCode -eq 200) { $ready = $true; break }
            } catch { }
            Start-Sleep -Seconds 2
          }
          if (-not $ready) {
            try { $proc | Stop-Process -Force } catch {}
            throw "KeywordServer not ready on port $env:RF_PORT"
          }
          Write-Host "KeywordServer is ready on $env:RF_HOST:$env:RF_PORT"
        '''
      }
    }

    stage('Install Python deps') {
      steps {
        powershell '''
          $ErrorActionPreference = "Stop"
          if (Get-Command py -ErrorAction SilentlyContinue) {
            py -3 -m pip install --upgrade pip
            py -3 -m pip install robotframework==6.1 robotframework-pabot==3.2 allure-robotframework==2.13.5
          } elseif (Get-Command python -ErrorAction SilentlyContinue) {
            python -m pip install --upgrade pip
            python -m pip install robotframework==6.1 robotframework-pabot==3.2 allure-robotframework==2.13.5
          } else {
            throw "Python 3.x not found on agent."
          }
        '''
      }
    }

    stage('Run tests with pabot (4)') {
      steps {
        powershell '''
          $ErrorActionPreference = "Stop"
          New-Item -ItemType Directory -Force -Path $env:ALLURE_RESULTS | Out-Null
          New-Item -ItemType Directory -Force -Path $env:ROBOT_RESULTS  | Out-Null

          $start = Get-Date
          $args = @(
            "--processes","4",
            "--testlevelsplit",
            "--consolecolors","on",
            "--consolemarkers","on",
            "--listener","allure_robotframework;$env:ALLURE_RESULTS",
            "--outputdir","$env:ROBOT_RESULTS",
            "--variable","BASE:$env:BASE",
            "api_smoke.robot","sql_demo.robot","fix_demo.robot"
          )
          if (Get-Command py -ErrorAction SilentlyContinue) { py -3 -m pabot $args } else { pabot $args }

          $elapsed = [int]((Get-Date) - $start).TotalSeconds
          "ELAPSED_SECONDS=$elapsed" | Out-File -FilePath "results\\time.txt" -Encoding ascii
          Write-Host "Parallel run finished in $elapsed s"
        '''
      }
      post {
        always {
          archiveArtifacts artifacts: 'results/**,keywordserver.pid', fingerprint: true
        }
      }
    }

    stage('Publish Allure Report') {
      when { expression { return fileExists(env.ALLURE_RESULTS) } }
      steps {
        allure includeProperties: false, jdk: '', results: [[path: "${ALLURE_RESULTS}"]]
      }
    }
  }

  post {
    always {
      echo 'Stopping Keyword Server (if running)...'
      powershell '''
        if (Test-Path "keywordserver.pid") {
          $pid = Get-Content "keywordserver.pid" | Select-Object -First 1
          if ($pid) { Stop-Process -Id $pid -Force -ErrorAction SilentlyContinue }
        }
      '''
    }
  }
}
