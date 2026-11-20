"""Argument-vector boundary for native and WSL Windows PowerShell fixtures."""
import os
from pathlib import Path
import shutil
import subprocess
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'host'))
from owned_process import run as run_owned


def executable():
    shell = shutil.which('pwsh') or shutil.which('pwsh.exe')
    if not shell:
        raise RuntimeError('PowerShell 7.2+ (pwsh) is required for qualification fixtures')
    return shell


def native_path(path, shell=None):
    shell = shell or executable()
    value = os.fspath(path)
    if os.name != 'nt' and shell.lower().endswith('.exe'):
        value = subprocess.check_output(['wslpath','-w',value], text=True, timeout=10).strip()
        if not value:
            raise RuntimeError('PowerShell fixture path conversion returned empty output')
    return value


def run_script(script, *paths, timeout=30):
    shell = executable()
    return run_owned([shell,'-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass',
                           '-File',native_path(script,shell),
                           *(native_path(path,shell) for path in paths)],
                          timeout=timeout,check=False)


def literal_path(path, shell=None):
    return "'" + native_path(Path(path),shell).replace("'", "''") + "'"


def read_launcher_source(path):
    path = Path(path)
    source = path.read_text(encoding='utf-8-sig')
    modules = ('host/Qualification-Evidence.ps1', 'host/Qualification-Report.ps1', 'device/Device-Lanes.ps1', 'device/Device-Profile.ps1', 'device/Device-Inventory.ps1')
    for module in modules:
        marker = ". (Join-Path $PSScriptRoot '" + module + "')"
        if marker in source:
            source = source.replace(marker, (path.parent / module).read_text(encoding='utf-8-sig'))
    return source
