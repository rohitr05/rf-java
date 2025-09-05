pipeline {
  agent any

  options {
    timestamps()
    timeout(time: 45, unit: 'MINUTES')
  }

  parameters {
    string(name: 'PYTHON_EXE',
           defaultValue: 'C:\\Users\\Anjaly\\AppData\\Local\\Programs\\Python\\Python313\\python.exe',
           description: 'Absolute path to python.exe used to run pabot')
    string(name: 'RF_HOST', defaultValue: '0.0.0.0', description: 'Bind host for KeywordServer')
    string(name: 'RF_PORT', defaultValue: '8270', description: 'Port for KeywordServer')
    string(name: 'PROCESSES', defaultValue: '4', description: 'Pabot parallel processes')
  }

  environment {
    // Make these available to Java (KeywordServer reads env or -D)
    RF_HOST = "${params.RF_HOST}"
    RF_PORT = "${params.RF_PORT}"
    PATH    = "${params.PYTHON_EXE}\\..\\Scripts;${env.PATH}"
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
        // IMPORTANT: launch the shaded jar (or the main jar that Maven replaces with shaded),
        // not the original-*.jar, and do NOT block this step on the Java process.
        powershell '''
$ErrorActionPreference = "Stop"

$bindHost = $env:RF_HOST
$port     = [int]$env:RF_PORT
$root     = (Resolve-Path ".").Path
$target   = Join-Path $root "server\\target"

# 1) Kill anything already using the port (leftover runs)
$inUse = netstat -ano | Select-String ":$port\\s" | ForEach-Object { ($_ -split "\\s+")[($PSItem -split "\\s+").Length-1] } | Select-Object -Unique
foreach ($pid in $inUse) {
  try { Stop-Process -Id $pid -Force -ErrorAction SilentlyContinue } catch {}
}

# 2) Pick the RIGHT jar:
# Prefer the main artifact rf-keywords-rbc-1.0.0.jar (Maven shade REPLACES this with shaded one),
# fallback to an explicit *-shaded.jar; never use original-*.jar
$mainJar    = Get-ChildItem -Path $target -Filter "rf-keywords-rbc-1.0.0.jar" -ErrorAction SilentlyContinue | Select-Object -First 1
$shadedJar  = Get-ChildItem -Path $target -Filter "rf-keywords-rbc-1.0.0-shaded.jar" -ErrorAction SilentlyContinue | Select-Object -First 1
$origJar    = Get-ChildItem -Path $target -Filter "original-rf-keywords-rbc-1.0.0.jar" -ErrorAction SilentlyContinue | Select-Object -First 1

if ($origJar) { Write-Host "NOTE: original-*.jar present, will NOT be used." }

if ($mainJar)       { $jarToRun = $mainJar.FullName }
elseif ($shadedJar) { $jarToRun = $shadedJar.FullName }
else { throw "No runnable KeywordServer jar found under $target" }

Write-Host "Starting KeywordServer: $jarToRun on $bindHost:$port"

# 3) Launch in background (non-blocking). Pass -D to be explicit alongside env.
$java = "java"
$arguments = @(
  "-Drf.host=$($bindHost)",
  "-Drf.port=$($port)",
  "-jar", "$jarToRun"
)

$proc = Start-Process -FilePath $java -ArgumentList $arguments -PassThru -WindowStyle Hidden
Set-Content -Path "server.pid" -Value $proc.Id

# 4) Readiness probe against loopback (works whether server binds 0.0.0.0 or 127.0.0.1)
$probeHost = "127.0.0.1"
$tries = 30
$ok = $false
for ($i=1; $i -le $tries; $i++) {
  Start-Sleep -Seconds 1
  $tcp = Test-NetConnection -ComputerName $probeHost -Port $port -WarningAction SilentlyContinue
  if ($tcp.TcpTestSucceeded) { $ok = $true; break }
  Write-Host ("[{0}] WARNING: TCP connect to ({1} : {2}) failed" -f (Get-Date).ToString("s"), $probeHost, $port)
}

if (-not $ok) {
  try { Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue } catch {}
  throw ("KeywordServer not ready on {0}:{1} after {2}s" -f $probeHost, $port, $tries)
}

Write-Host ("KeywordServer is accepting connections on {0}:{1} (PID={2})" -f $probeHost, $port, $proc.Id)
'''
      }
    }

    stage('Setup Python') {
      steps {
        bat """
IF NOT EXIST "${params.PYTHON_EXE}" (
  echo ERROR: Python not found at "${params.PYTHON_EXE}"
  exit /b 1
)
"${params.PYTHON_EXE}" -m pip install -U pip
"${params.PYTHON_EXE}" -m pip install robotframework robotframework-pabot allure-robotframework
"""
      }
    }

    stage('Run Robot (pabot)') {
      steps {
        bat """
IF NOT EXIST "results\\robot" mkdir "results\\robot"
IF NOT EXIST "results\\allure" mkdir "results\\allure"

"${params.PYTHON_EXE}" -m pabot ^
  --processes ${params.PROCESSES} ^
  --testlevelsplit ^
  --listener "allure_robotframework;results/allure" ^
  --outputdir "results/robot" ^
  tests\\api_smoke.robot tests\\sql_demo.robot tests\\fix_demo.robot
"""
      }
    }

    stage('Publish Allure Report') {
      steps {
        // Requires Allure Jenkins plugin + configured Allure commandline tool
        allure([
          includeProperties: false,
          jdk: '',
          results: [[path: 'results/allure']]
        ])
      }
    }
  }

  post {
    always {
      // Try to stop server cleanly; then make sure the port is free.
      powershell '''
$ErrorActionPreference = "Continue"
if (Test-Path "server.pid") {
  $pid = Get-Content "server.pid" | Select-Object -First 1
  if ($pid) {
    try { Stop-Process -Id $pid -Force -ErrorAction SilentlyContinue } catch {}
  }
  Remove-Item "server.pid" -ErrorAction SilentlyContinue
}

$port = [int]$env:RF_PORT
$inUse = netstat -ano | Select-String ":$port\\s" | ForEach-Object { ($_ -split "\\s+")[($_ -split "\\s+").Length-1] } | Select-Object -Unique
foreach ($p in $inUse) {
  try { Stop-Process -Id $p -Force -ErrorAction SilentlyContinue } catch {}
}
'''
      archiveArtifacts artifacts: 'results/**', fingerprint: true
      // Robot output.xml is not JUnit; we skip junit() to avoid false errors
    }
  }
}