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
- Always run `mvn clean test` (never plain `mvn test`) before building production artifacts, so stale classes cannot hide a failure.
- Bump the version in `pom.xml` before building any new release.
- Build production JAR with `mvn package -DskipTests`.
- Update the md files in the same change (`evidence_log.md` for findings/decisions/release contents; the design files for anything that changed). Fix any statement that is now wrong; do not leave stale documentation.
- Commit with `git add *` as per project standards; commit messages describe the business rule or bug addressed.
- The replay must run production classes (the live conductor, cache, market filter, strategy, handler, risk and order services). If live and replay must behave differently, change production code so both change together; never add replay-only logic.

## 3. Production layout (verified 2026-11-22)
- Prod runs from `D:\projects\mithron-prod` (`app\Mithron.jar`, `config\mithron-prod.env`, `scripts\restart-mithron-prod.bat` which copies the JAR from `D:\projects\market-scanner\target`, `logs\`, `backups\db-backups`, `backups\raw-eod`, `backups\historical-raw`).
- The repo-root `.env` and log files are the local DEV instance (`mithron_dev`). Do not treat them as production evidence.
- Nightly dumps are `backups\db-backups\<database>_<date>.dump`; a dump is the rollback point before any deploy or backfill.
