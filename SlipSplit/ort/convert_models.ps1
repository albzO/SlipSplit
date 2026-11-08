# Converts every .onnx model in the app's assets to ORT format next to it and rewrites ops.config.
# Needs a Python with the same onnxruntime version as the app's AAR: pip install onnxruntime==1.31.1 onnx
param([string]$Python = "python")

$ErrorActionPreference = "Stop"
$assets = Resolve-Path "$PSScriptRoot\..\app\src\main\assets"
$work = Join-Path ([IO.Path]::GetTempPath()) "slipsplit-ort-convert"
Remove-Item -Recurse -Force $work -ErrorAction SilentlyContinue

$version = & $Python -c "import onnxruntime; print(onnxruntime.__version__)"
if ($version -ne "1.31.1") { throw "onnxruntime $version found; the AAR is 1.31.1, and the ORT format must match it." }

# 'Fixed' bakes the optimisations into the file because the minimal build has no graph optimiser.
# 'arm' keeps out the x86-only layouts, so the same files also run on the x86_64 emulator.
& $Python -m onnxruntime.tools.convert_onnx_models_to_ort `
    --optimization_style Fixed --enable_type_reduction --target_platform arm `
    --output_dir $work $assets
if ($LASTEXITCODE -ne 0) { throw "Conversion failed." }

Get-ChildItem $work -Recurse -Filter *.ort | ForEach-Object {
    $relative = $_.FullName.Substring($work.Length + 1)
    Copy-Item $_.FullName (Join-Path $assets $relative) -Force
    Write-Host "ORT  $relative"
}

# The generated header lists absolute paths, so keep only the operator lines.
$ops = Get-Content (Join-Path $work "required_operators_and_types.config") | Where-Object { $_ -notmatch '^#' }
$header = "# Operators and types the app's models need, written by convert_models.ps1. Rebuild ORT after it changes."
Set-Content "$PSScriptRoot\ops.config" (@($header) + $ops) -Encoding utf8NoBOM
Remove-Item -Recurse -Force $work
git -C $PSScriptRoot diff --stat -- ops.config
