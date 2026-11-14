# Start the Pleos Connect emulator with a cold boot (VHAL is unavailable with quick boot).
# Software rendering: host GPU mode crashed nvoglv64.dll on this PC (2026-10-30).
# -Headless: no Qt window. The window falls back to the system (NVIDIA) OpenGL because opengl32sw.dll
#            is missing from the emulator package; use headless + `adb screencap` for automated checks.
param([switch]$Headless)
$env:ANDROID_AVD_HOME = 'C:\Android\avd'
$emuArgs = @('-avd', 'Pleos_Connect_v2.1', '-no-snapshot', '-gpu', 'swiftshader_indirect')
if ($Headless) { $emuArgs += '-no-window' }
& C:\Android\Sdk\emulator\emulator.exe @emuArgs
