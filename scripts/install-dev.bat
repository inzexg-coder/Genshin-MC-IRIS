@echo off
setlocal
REM Живая установка для Windows (junction, без прав админа).
REM Если .minecraft в другом месте: set MC_DIR=C:\путь\к\.minecraft
if "%MC_DIR%"=="" set "MC_DIR=%APPDATA%\.minecraft"
set "REPO=%~dp0.."
if not exist "%MC_DIR%\shaderpacks"   mkdir "%MC_DIR%\shaderpacks"
if not exist "%MC_DIR%\resourcepacks" mkdir "%MC_DIR%\resourcepacks"
if not exist "%MC_DIR%\shaderpacks\TeyvatShader" (
  mklink /J "%MC_DIR%\shaderpacks\TeyvatShader" "%REPO%\shader\TeyvatShader"
) else (
  echo shaderpacks\TeyvatShader уже существует
)
if not exist "%MC_DIR%\resourcepacks\Teyvat" (
  mklink /J "%MC_DIR%\resourcepacks\Teyvat" "%REPO%\resourcepack"
) else (
  echo resourcepacks\Teyvat уже существует
)
echo Готово. В игре: F3+R — шейдеры, F3+T — ресурспаки.
pause
