# Reduced ONNX Runtime

The app uses its own build of ONNX Runtime 1.31.1 instead of the Maven package. It is a minimal build: it reads only ORT-format models and has kernels only for the operators and types in `ops.config`. The AAR is checked in at `app/libs/onnxruntime-android-minimal-1.31.1.aar`, so a normal Gradle build needs none of the tools below.

| File | Purpose |
| --- | --- |
| `ops.config` | Operators and input types the app's models use, generated from the models |
| `build_settings.json` | ABIs and build flags for ONNX Runtime's AAR script |
| `convert_models.ps1` | Writes an `.ort` next to every `.onnx` in `app/src/main/assets` and rewrites `ops.config` |
| `build_ort.ps1` | Builds the AAR from `ops.config` and copies it into `app/libs` |

The `.onnx` files stay in the assets folder as the conversion source; Gradle leaves them out of the APK and stops the build if an `.ort` is missing or older than its `.onnx`.

## After adding or replacing a model

1. Install the converter in Python: `pip install onnxruntime==1.31.1 onnx`. The version must match the AAR.
2. Run `./convert_models.ps1` (pass `-Python` for a virtual environment).
3. If `ops.config` changed, rebuild the AAR. Otherwise the AAR is still good.

The conversion bakes all graph optimisations into the `.ort` files (`Fixed` style), because a minimal build has no optimiser. It targets ARM, which also runs on the x86_64 emulator.

## Rebuilding the AAR

Needs the ONNX Runtime source at tag `v1.31.1`, Android NDK `28.0.13004108`, JDK 17 (its Android Gradle plugin 7.4 fails under JDK 21), and `pip install cmake ninja`. Keep the source and build folders at short paths such as `C:\ort`: Windows path limits break the build otherwise.

```powershell
git clone --depth 1 --branch v1.31.1 https://github.com/microsoft/onnxruntime.git C:\ort\src
$env:JAVA_HOME = "<JDK 17>"
./build_ort.ps1 -Python <python with cmake and ninja>
```

It builds the `Release` configuration like the Maven package: on the phone it matched the full runtime's speed, while `MinSizeRel` saved another 0.9 MB but ran 8–10% slower.

A clean build of both ABIs takes about an hour. NNAPI, XNNPACK, ML operators and RTTI are left out; the app runs on the CPU provider only.
