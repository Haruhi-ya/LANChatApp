@echo off
setlocal

REM ============================================================
REM  LanChat 局域网聊天室 - 一键打包脚本
REM  产物:
REM    release\客户端\LanChat-Client.jar   客户端
REM    release\服务端\LanChat-Server.jar   服务端(内置MySQL驱动)
REM  运行环境: 本机需安装 JDK 17+
REM  如需修改 JDK 路径, 改下面 JAVA_HOME 即可
REM ============================================================

set "JAVA_HOME=D:\DevJDK\JDK25"
set "ROOT=%~dp0"
set "SRC=%ROOT%src"
set "LIB=%ROOT%lib\mysql-connector-j-9.7.0.jar"
set "BUILD=%ROOT%build"
set "CLASSES=%BUILD%\classes"
set "CLIENT=%BUILD%\client"
set "SERVER=%BUILD%\server"
set "RELEASE=%ROOT%release"

echo [1/5] 清理旧构建产物...
if exist "%BUILD%" rmdir /s /q "%BUILD%"
if exist "%RELEASE%" rmdir /s /q "%RELEASE%"
mkdir "%CLASSES%"
mkdir "%RELEASE%\客户端" "%RELEASE%\服务端"

echo [2/5] 编译源码 (Java 17 字节码)...
dir /b /s "%SRC%\chatPackage\*.java" > "%BUILD%\sources.txt"
"%JAVA_HOME%\bin\javac.exe" -encoding UTF-8 --release 17 -cp "%LIB%" -d "%CLASSES%" @"%BUILD%\sources.txt"
if errorlevel 1 goto :fail

echo [3/5] 打包客户端 jar (不含服务端代码)...
mkdir "%CLIENT%"
xcopy "%CLASSES%\chatPackage" "%CLIENT%\chatPackage" /s /e /q /i >nul
del /q "%CLIENT%\chatPackage\dbManager*.class" "%CLIENT%\chatPackage\chatServer*.class" >nul 2>&1
"%JAVA_HOME%\bin\jar.exe" --create --file "%RELEASE%\客户端\LanChat-Client.jar" --main-class chatPackage.chatEntryUI -C "%CLIENT%" .
if errorlevel 1 goto :fail

echo [4/5] 打包服务端 jar (内置 MySQL 驱动)...
mkdir "%SERVER%"
pushd "%SERVER%"
"%JAVA_HOME%\bin\jar.exe" xf "%LIB%"
popd
xcopy "%CLASSES%\chatPackage" "%SERVER%\chatPackage" /s /e /q /i >nul
> "%SERVER%\MANIFEST.MF" echo Main-Class: chatPackage.chatServer
>> "%SERVER%\MANIFEST.MF" echo.
"%JAVA_HOME%\bin\jar.exe" --create --file "%RELEASE%\服务端\LanChat-Server.jar" -m "%SERVER%\MANIFEST.MF" -C "%SERVER%" .
if errorlevel 1 goto :fail

echo [5/5] 生成启动脚本与配置文件...
copy /y "%ROOT%config.properties.example" "%RELEASE%\服务端\config.properties" >nul

> "%RELEASE%\客户端\启动客户端.bat" echo @echo off
>> "%RELEASE%\客户端\启动客户端.bat" echo setlocal
>> "%RELEASE%\客户端\启动客户端.bat" echo set "DIR=%%~dp0"
>> "%RELEASE%\客户端\启动客户端.bat" echo set "JAVAW_EXE=javaw"
>> "%RELEASE%\客户端\启动客户端.bat" echo if defined JAVA_HOME if exist "%%JAVA_HOME%%\bin\javaw.exe" set "JAVAW_EXE=%%JAVA_HOME%%\bin\javaw.exe"
>> "%RELEASE%\客户端\启动客户端.bat" echo start "" "%%JAVAW_EXE%%" -jar "%%DIR%%LanChat-Client.jar"

> "%RELEASE%\服务端\启动服务端.bat" echo @echo off
>> "%RELEASE%\服务端\启动服务端.bat" echo setlocal
>> "%RELEASE%\服务端\启动服务端.bat" echo cd /d "%%~dp0"
>> "%RELEASE%\服务端\启动服务端.bat" echo set "JAVA_EXE=java"
>> "%RELEASE%\服务端\启动服务端.bat" echo if defined JAVA_HOME if exist "%%JAVA_HOME%%\bin\java.exe" set "JAVA_EXE=%%JAVA_HOME%%\bin\java.exe"
>> "%RELEASE%\服务端\启动服务端.bat" echo "%%JAVA_EXE%%" -jar LanChat-Server.jar
>> "%RELEASE%\服务端\启动服务端.bat" echo pause

> "%RELEASE%\客户端\使用说明.txt" echo 局域网聊天室 - 客户端
>> "%RELEASE%\客户端\使用说明.txt" echo ================================
>> "%RELEASE%\客户端\使用说明.txt" echo 运行方式：
>> "%RELEASE%\客户端\使用说明.txt" echo   1. 双击 启动客户端.bat（需已安装 Java 17 或更高版本）
>> "%RELEASE%\客户端\使用说明.txt" echo   2. 或在命令行执行: java -jar LanChat-Client.jar
>> "%RELEASE%\客户端\使用说明.txt" echo.
>> "%RELEASE%\客户端\使用说明.txt" echo 使用方法：
>> "%RELEASE%\客户端\使用说明.txt" echo   打开后输入服务器 IP、端口和账号密码即可登录聊天。
>> "%RELEASE%\客户端\使用说明.txt" echo   服务器端口默认为 8080，以服务端实际配置为准。

> "%RELEASE%\服务端\使用说明.txt" echo 局域网聊天室 - 服务端
>> "%RELEASE%\服务端\使用说明.txt" echo ================================
>> "%RELEASE%\服务端\使用说明.txt" echo 运行方式：
>> "%RELEASE%\服务端\使用说明.txt" echo   1. 双击 启动服务端.bat（需已安装 Java 17 或更高版本）
>> "%RELEASE%\服务端\使用说明.txt" echo   2. 或在命令行执行: java -jar LanChat-Server.jar
>> "%RELEASE%\服务端\使用说明.txt" echo.
>> "%RELEASE%\服务端\使用说明.txt" echo 配置说明：
>> "%RELEASE%\服务端\使用说明.txt" echo   服务端启动时读取同目录下的 config.properties，
>> "%RELEASE%\服务端\使用说明.txt" echo   请先编辑该文件填入正确的 MySQL 数据库账号密码。
>> "%RELEASE%\服务端\使用说明.txt" echo   默认监听端口 8080，可通过命令行参数 --port 覆盖。
>> "%RELEASE%\服务端\使用说明.txt" echo   例如: java -jar LanChat-Server.jar --port 9000
>> "%RELEASE%\服务端\使用说明.txt" echo.
>> "%RELEASE%\服务端\使用说明.txt" echo 客户端连接时填写的 IP 就是本机的局域网 IP。

echo.
echo ============================================
echo  打包完成!
echo    客户端: release\客户端\LanChat-Client.jar
echo    服务端: release\服务端\LanChat-Server.jar
echo ============================================
pause
exit /b 0

:fail
echo.
echo 打包失败! 请检查上方错误信息。
pause
exit /b 1
