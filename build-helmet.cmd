@echo off
rem Builds the helmet fork as an arm64 debug APK on Windows (see HELMET.md).
rem The repo has no gradlew.bat, so this calls the Gradle wrapper jar directly.
rem Gradle 8.13 can't run on Java 25 (Android Studio's bundled JDK), so point HELMET_JDK at a JDK 17 or 21.
rem Output APK: TMessagesProj_App\build\intermediates\apk\afat\debug\app.apk
rem (AGP leaves it in intermediates when the build is limited to one device ABI)
rem Extra arguments are passed to Gradle, e.g. "build-helmet.cmd --stacktrace".
setlocal
if not defined HELMET_JDK set "HELMET_JDK=%LOCALAPPDATA%\Programs\jdk-21.0.13+9"
if not exist "%HELMET_JDK%\bin\java.exe" (
    echo No JDK found at "%HELMET_JDK%". Set HELMET_JDK to a JDK 17 or 21.
    exit /b 1
)
set "JAVA_HOME=%HELMET_JDK%"
if not defined ANDROID_HOME set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
set "ANDROID_SDK_ROOT=%ANDROID_HOME%"
set "REPO=%~dp0"
set "LOG=%REPO%build-helmet.log"

cd /d "%REPO%"
echo Building... (the first run compiles all native code and can take a long time)
echo Log: %LOG%
"%JAVA_HOME%\bin\java.exe" -Xmx64m -Xms64m "-Dorg.gradle.appname=gradlew" -classpath "%REPO%gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain :TMessagesProj_App:assembleAfatDebug -Pandroid.injected.build.abi=arm64-v8a --console=plain %* > "%LOG%" 2>&1
set RESULT=%ERRORLEVEL%
>> "%LOG%" echo GRADLE_EXIT=%RESULT%
if %RESULT%==0 (echo BUILD SUCCEEDED) else (echo BUILD FAILED - see "%LOG%")
exit /b %RESULT%
