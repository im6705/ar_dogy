param(
    [Parameter(Mandatory=$true)][string]$Source,
    [string]$Blender = 'C:/Program Files/Blender Foundation/Blender 5.2/blender.exe',
    [string]$Profile = "$PSScriptRoot/dog-profile.json",
    [string]$Output = "$PSScriptRoot/../../app/src/main/assets/models/dog.glb",
    [string]$WorkDir = "$PSScriptRoot/../../../asset-pipeline",
    [switch]$Render
)
$ErrorActionPreference='Stop'
$arguments=@('--background','--factory-startup','--python-exit-code','1','--python',"$PSScriptRoot/prepare_dog.py",'--','--source',[IO.Path]::GetFullPath($Source),'--profile',[IO.Path]::GetFullPath($Profile),'--output',[IO.Path]::GetFullPath($Output),'--work-dir',[IO.Path]::GetFullPath($WorkDir))
if ($Render) {$arguments+='--render'}
& $Blender @arguments
if ($LASTEXITCODE -ne 0) {throw "Asset conversion failed: $LASTEXITCODE"}
& $Blender --background --factory-startup --python-exit-code 1 --python "$PSScriptRoot/validate_dog.py" -- ([IO.Path]::GetFullPath($Output)) ([IO.Path]::GetFullPath($WorkDir))
if ($LASTEXITCODE -ne 0) {throw "GLB integration validation failed: $LASTEXITCODE"}
