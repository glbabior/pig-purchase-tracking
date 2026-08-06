@echo off
setlocal
rem Stop this app if it is holding port 8080. Safe to run anytime: if nothing is
rem listening it says so. Useful when a previous run was stopped with Ctrl+C and left an
rem orphaned java.exe holding the port.
rem
rem It IDENTIFIES the process before killing it, rather than killing whatever happens to
rem be on 8080. The port is not owned by this project -- a dev server, a proxy, anything
rem could be there -- and launch.cmd and demo.cmd both call this unconditionally, so an
rem untargeted kill would take down a bystander every time you started the app.
rem
rem The match is on the main class in the process command line. The classpath is passed
rem through an argfile, so the project directory does NOT appear there and cannot be
rem matched on; the main class is what identifies it.
rem
rem Two things this script is written around, both found by testing it rather than
rem reading it:
rem
rem   * netstat lists the same listener twice (IPv4 and IPv6). A per-PID loop killed the
rem     process on the first line and then reported the now-dead PID as a foreign process
rem     on the second. Hence one pass, with the PIDs de-duplicated first.
rem   * A pipe inside a double-quoted argument is NOT escaped by cmd, so "... ^| Select-
rem     Object ..." reached PowerShell as a literal ^| and silently produced nothing --
rem     which looked exactly like "the port is free". There is deliberately no pipe below.
powershell -NoProfile -Command ^
 "$app = 'com.pigpurchases.PigPurchasesApplication';" ^
 "$conns = @(Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue);" ^
 "$ids = @(); foreach ($c in $conns) { if ($ids -notcontains $c.OwningProcess) { $ids += $c.OwningProcess } };" ^
 "if ($ids.Count -eq 0) { Write-Host 'Port 8080 is already free.'; exit 0 };" ^
 "foreach ($id in $ids) {" ^
 "  $pr = Get-CimInstance Win32_Process -Filter ('ProcessId=' + $id) -ErrorAction SilentlyContinue;" ^
 "  if (-not $pr) { continue };" ^
 "  if ($pr.CommandLine -like ('*' + $app + '*')) {" ^
 "    Write-Host ('Stopping PigPurchases (PID ' + $id + ')...');" ^
 "    Stop-Process -Id $id -Force -ErrorAction SilentlyContinue" ^
 "  } else {" ^
 "    Write-Host ('Port 8080 is held by PID ' + $id + ' (' + $pr.Name + '), which is NOT this app. Leaving it alone.');" ^
 "    Write-Host 'If you meant to free the port, stop that process yourself.'" ^
 "  }" ^
 "}"
endlocal
