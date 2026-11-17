@echo off
rem Run from the folder this script lives in (admin launch / shortcuts change the working dir).
cd /d "%~dp0"
start "" "%~dp0jre\bin\javaw.exe" --add-exports=java.desktop/sun.awt=ALL-UNNAMED -jar "%~dp0Suwayomi-Launcher.jar" %*
