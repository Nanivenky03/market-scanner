# Mithron Market Scanner - Agent Rules & Guidelines

## 1. Production Application Restart & Deployment
When deploying or restarting the production application via `Restart-Mithron.bat` (located on the user's Desktop at `C:\Users\venka\OneDrive\Desktop\Restart-Mithron.bat`):
- **NEVER** run the script inside a synchronous/sandboxed agent subshell that terminates upon tool completion. On Windows, child processes inside the runner's Job Object get terminated when the command session closes.
- **ALWAYS** launch the script detached in the host laptop's native session using WMI:
  ```powershell
  Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{ CommandLine = 'cmd.exe /c "C:\Users\venka\OneDrive\Desktop\Restart-Mithron.bat"' }
  ```
  or instruct the user to run `Restart-Mithron.bat` directly on their laptop as Administrator.
- If an old instance is running under Windows Session 0, ensure the process is terminated with appropriate privileges or let the host run `taskkill /F /PID <pid>`.

## 2. Testing & Building
- Always run `mvn test` before building production artifacts.
- Bump the version in `pom.xml` before building any new release.
- Build production JAR with `mvn package -DskipTests`.
- Commit with `git add *` as per project standards.
