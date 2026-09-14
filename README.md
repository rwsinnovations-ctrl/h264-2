# H264-2 Android Build

## Qualcomm FastCV SDK Integration

This project includes Qualcomm FastCV SDK for hardware-accelerated computer vision on Android devices with Qualcomm Snapdragon processors.

### FastCV SDK Setup in GitHub Actions

The CI/CD workflow automatically downloads and extracts the Qualcomm FastCV SDK during the build process.

#### Current Setup

The workflow includes a step to download the FastCV SDK from Google Drive:

```yaml
- name: Download FastCV SDK from Google Drive
  run: |
    FILE_ID="1ehXinOqb1xnxgiGKsI70ExJWuqoXdVra"
    curl -L "https://drive.google.com/uc?export=download&id=${FILE_ID}" -o fastcv.zip
    unzip -q fastcv.zip
    ls -la
    # Extract the self-extracting binary if needed
    if [ -f "Qualcomm_Fast_CV"* ]; then
      chmod +x Qualcomm_Fast_CV*
      ./Qualcomm_Fast_CV* --silent --accept-license
    fi
```

**File Details:**
- **SDK:** `Qualcomm_Fast_CV.Core.1.7.2.Linux-AnyCPU.zip`
- **Location:** Google Drive (MyDrive)
- **File ID:** `1ehXinOqb1xnxgiGKsI70ExJWuqoXdVra`
- **Architecture:** Linux x86_64 (Debian/Ubuntu compatible)

#### To Enable FastCV in Your Code

1. **Read the FastCV documentation** included in the SDK after extraction
2. **Update CMakeLists.txt** to include FastCV headers and link libraries:
   ```cmake
   find_package(FastCV REQUIRED)
   target_link_libraries(your_library FastCV::FastCV)
   ```
3. **Add FastCV includes** in your C/C++ source files:
   ```cpp
   #include <fastcv.h>
   ```
4. **Configure your build.gradle** to support the necessary Android ABIs:
   ```gradle
   android {
       defaultConfig {
           ndk {
               abiFilters 'armeabi-v7a', 'arm64-v8a'
           }
       }
   }
   ```

### Build Environment

- **OS:** Ubuntu Latest (Debian x86_64)
- **Java:** JDK 17 (Temurin)
- **Gradle:** 8.5
- **Android NDK:** 25.2.9519653
- **CMake:** 3.22.1

### Building Locally

```bash
cd Rover_Hardware_Verification_Android_NDK
gradle assembleDebug
```

### CI/CD Workflow

Builds are automatically triggered on pushes to `main`, `master`, or `develop` branches. Output APKs are uploaded as artifacts.
