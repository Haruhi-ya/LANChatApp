@echo off
setlocal enabledelayedexpansion

REM ============================================================
REM  LanChat 一键构建脚本（本文件编码：GBK/ANSI，勿改为 UTF-8，
REM  否则 cmd 按 GBK 解析会把中文行解析错乱）
REM  产物:
REM    release\LanChat-<版本>\客户端\LanChat-Client.jar   客户端
REM    release\LanChat-<版本>\服务端\LanChat-Server.jar   服务端（内置 MySQL 驱动）
REM    release\LanChat-<版本>\runtime\                    便携 Java 运行时（两端共用，
REM                                                        没有安装 Java 的电脑也能直接跑）
REM    release\LanChat-<版本>.zip                        完整发布包（含版本号）
REM  构建环境: 本机安装 JDK 17+（含 jlink）
REM  若已设置 JAVA_HOME 则优先使用，否则回退到默认路径
REM  发新版本时只需修改下面的 VERSION
REM ============================================================

REM ---- 版本号（发版时修改这里）----
set "VERSION=1.1.0"

REM ---- 确定 JDK ----
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\javac.exe" goto :javahome_ok
set "JAVA_HOME=D:\DevJDK\JDK25"
if not exist "%JAVA_HOME%\bin\javac.exe" (
  echo [错误] 未找到 JDK 的 javac.exe，请在 build.bat 中修改 JAVA_HOME 路径
  pause
  exit /b 1
)
:javahome_ok

set "ROOT=%~dp0"
set "SRC=%ROOT%src"
set "LIB=%ROOT%lib\mysql-connector-j-9.7.0.jar"
set "BUILD=%ROOT%build"
set "CLASSES=%BUILD%\classes"
set "CLIENT=%BUILD%\client"
set "SERVER=%BUILD%\server"
set "RELEASE=%ROOT%release"
set "PKG=%RELEASE%\LanChat-%VERSION%"

echo [1/6] 清空编译产物...
if exist "%BUILD%" rmdir /s /q "%BUILD%"
if exist "%RELEASE%" rmdir /s /q "%RELEASE%"
mkdir "%CLASSES%"
mkdir "%PKG%\客户端" "%PKG%\服务端"

echo [2/6] 编译源码 (Java 17 字节码)...
dir /b /s "%SRC%\chatPackage\*.java" > "%BUILD%\sources.txt"
"%JAVA_HOME%\bin\javac.exe" -encoding UTF-8 --release 17 -cp "%LIB%" -d "%CLASSES%" @"%BUILD%\sources.txt"
if errorlevel 1 goto :fail

echo [3/6] 生成便携 Java 运行时 (jlink，客户端/服务端共用)...
"%JAVA_HOME%\bin\jlink.exe" --add-modules java.desktop,java.management,java.naming,java.security.sasl,java.sql --strip-debug --no-header-files --no-man-pages --compress=zip-6 --output "%PKG%\runtime"
if errorlevel 1 goto :fail

echo [4/6] 打包客户端 jar（去掉数据库相关类）...
mkdir "%CLIENT%"
xcopy "%CLASSES%\chatPackage" "%CLIENT%\chatPackage" /s /e /q /i >nul
del /q "%CLIENT%\chatPackage\dbManager*.class" "%CLIENT%\chatPackage\chatServer*.class" >nul 2>&1
"%JAVA_HOME%\bin\jar.exe" --create --file "%PKG%\客户端\LanChat-Client.jar" --main-class chatPackage.chatEntryUI -C "%CLIENT%" .
if errorlevel 1 goto :fail

echo [5/6] 打包服务端 jar（内置 MySQL 驱动）...
mkdir "%SERVER%"
pushd "%SERVER%"
"%JAVA_HOME%\bin\jar.exe" xf "%LIB%"
popd
xcopy "%CLASSES%\chatPackage" "%SERVER%\chatPackage" /s /e /q /i >nul
"%JAVA_HOME%\bin\jar.exe" --create --file "%PKG%\服务端\LanChat-Server.jar" --main-class chatPackage.chatServer -C "%SERVER%" .
if errorlevel 1 goto :fail

echo [6/6] 生成配置文件、启动脚本、使用说明...

REM ---- 服务端配置模板（不含真实密码，首次使用请自行填写）----
copy /y "%ROOT%config.properties.example" "%PKG%\服务端\config.properties" >nul

REM ---- 客户端启动脚本：优先内置 runtime，回退 JAVA_HOME，再回退系统 java ----
> "%PKG%\客户端\启动客户端.bat" echo @echo off
>> "%PKG%\客户端\启动客户端.bat" echo setlocal
>> "%PKG%\客户端\启动客户端.bat" echo REM 启动客户端：优先使用同目录 ..\runtime 内置运行时
>> "%PKG%\客户端\启动客户端.bat" echo set "DIR=%%~dp0"
>> "%PKG%\客户端\启动客户端.bat" echo set "JAVA="
>> "%PKG%\客户端\启动客户端.bat" echo if exist "%%DIR%%..\runtime\bin\javaw.exe" set "JAVA=%%DIR%%..\runtime\bin\javaw.exe"
>> "%PKG%\客户端\启动客户端.bat" echo if not defined JAVA if defined JAVA_HOME if exist "%%JAVA_HOME%%\bin\javaw.exe" set "JAVA=%%JAVA_HOME%%\bin\javaw.exe"
>> "%PKG%\客户端\启动客户端.bat" echo if not defined JAVA ^(where javaw ^>nul 2^>^&1^)
>> "%PKG%\客户端\启动客户端.bat" echo if not defined JAVA if not errorlevel 1 set "JAVA=javaw"
>> "%PKG%\客户端\启动客户端.bat" echo if not defined JAVA ^(
>> "%PKG%\客户端\启动客户端.bat" echo   echo.
>> "%PKG%\客户端\启动客户端.bat" echo   echo [错误] 未找到 Java 运行时！
>> "%PKG%\客户端\启动客户端.bat" echo   echo 请保留发布包中的 runtime 文件夹（内置 Java），
>> "%PKG%\客户端\启动客户端.bat" echo   echo 或在本机安装 Java 17 及以上版本后重试。
>> "%PKG%\客户端\启动客户端.bat" echo   echo.
>> "%PKG%\客户端\启动客户端.bat" echo   pause
>> "%PKG%\客户端\启动客户端.bat" echo   exit /b 1
>> "%PKG%\客户端\启动客户端.bat" echo ^)
>> "%PKG%\客户端\启动客户端.bat" echo start "" "%%JAVA%%" -jar "%%DIR%%LanChat-Client.jar"
>> "%PKG%\客户端\启动客户端.bat" echo exit /b 0

REM ---- 服务端启动脚本（逻辑同上，用 java.exe 便于看到日志）----
> "%PKG%\服务端\启动服务端.bat" echo @echo off
>> "%PKG%\服务端\启动服务端.bat" echo setlocal
>> "%PKG%\服务端\启动服务端.bat" echo REM 启动服务端：优先使用同目录 ..\runtime 内置运行时
>> "%PKG%\服务端\启动服务端.bat" echo cd /d "%%~dp0"
>> "%PKG%\服务端\启动服务端.bat" echo set "JAVA="
>> "%PKG%\服务端\启动服务端.bat" echo if exist "%%CD%%..\runtime\bin\java.exe" set "JAVA=%%CD%%..\runtime\bin\java.exe"
>> "%PKG%\服务端\启动服务端.bat" echo if not defined JAVA if defined JAVA_HOME if exist "%%JAVA_HOME%%\bin\java.exe" set "JAVA=%%JAVA_HOME%%\bin\java.exe"
>> "%PKG%\服务端\启动服务端.bat" echo if not defined JAVA ^(where java ^>nul 2^>^&1^)
>> "%PKG%\服务端\启动服务端.bat" echo if not defined JAVA if not errorlevel 1 set "JAVA=java"
>> "%PKG%\服务端\启动服务端.bat" echo if not defined JAVA ^(
>> "%PKG%\服务端\启动服务端.bat" echo   echo.
>> "%PKG%\服务端\启动服务端.bat" echo   echo [错误] 未找到 Java 运行时！
>> "%PKG%\服务端\启动服务端.bat" echo   echo 请保留发布包中的 runtime 文件夹（内置 Java），
>> "%PKG%\服务端\启动服务端.bat" echo   echo 或在本机安装 Java 17 及以上版本后重试。
>> "%PKG%\服务端\启动服务端.bat" echo   echo.
>> "%PKG%\服务端\启动服务端.bat" echo   pause
>> "%PKG%\服务端\启动服务端.bat" echo   exit /b 1
>> "%PKG%\服务端\启动服务端.bat" echo ^)
>> "%PKG%\服务端\启动服务端.bat" echo "%%JAVA%%" -jar "%%~dp0LanChat-Server.jar"
>> "%PKG%\服务端\启动服务端.bat" echo pause
>> "%PKG%\服务端\启动服务端.bat" echo exit /b 0

REM ---- 构建信息（版本号、构建时间、JDK、git commit）----
> "%PKG%\构建信息.txt" echo LanChat 发布包 - 构建信息
>> "%PKG%\构建信息.txt" echo ================================
>> "%PKG%\构建信息.txt" echo 版本:     %VERSION%
>> "%PKG%\构建信息.txt" echo 构建时间: %date% %time%
>> "%PKG%\构建信息.txt" echo JDK:      %JAVA_HOME%
>> "%PKG%\构建信息.txt" echo.
>> "%PKG%\构建信息.txt" echo 包含:
>> "%PKG%\构建信息.txt" echo   runtime\    内置 Java 运行时（免安装）
>> "%PKG%\构建信息.txt" echo   客户端\     LanChat-Client.jar + 启动脚本 + 使用说明
>> "%PKG%\构建信息.txt" echo   服务端\     LanChat-Server.jar + 启动脚本 + 使用说明
>> "%PKG%\构建信息.txt" echo.
>> "%PKG%\构建信息.txt" echo 启动方式:
>> "%PKG%\构建信息.txt" echo   客户端: 双击 客户端\启动客户端.bat
>> "%PKG%\构建信息.txt" echo   服务端: 双击 服务端\启动服务端.bat

REM ---- 客户端使用说明 ----
> "%PKG%\客户端\使用说明.txt" echo 局域网聊天室 - 客户端
>> "%PKG%\客户端\使用说明.txt" echo ================================
>> "%PKG%\客户端\使用说明.txt" echo.
>> "%PKG%\客户端\使用说明.txt" echo 运行方式：
>> "%PKG%\客户端\使用说明.txt" echo   双击 启动客户端.bat 即可（无需安装 Java，
>> "%PKG%\客户端\使用说明.txt" echo   runtime 文件夹已内置运行环境）。
>> "%PKG%\客户端\使用说明.txt" echo.
>> "%PKG%\客户端\使用说明.txt" echo 使用方法：
>> "%PKG%\客户端\使用说明.txt" echo   打开后填写服务器 IP、端口和账号密码即可登录聊天。
>> "%PKG%\客户端\使用说明.txt" echo   服务器端口默认为 8080，以对方服务端实际配置为准。
>> "%PKG%\客户端\使用说明.txt" echo   客户端不需要安装 MySQL，也不需要联网。
>> "%PKG%\客户端\使用说明.txt" echo.
>> "%PKG%\客户端\使用说明.txt" echo 注意：
>> "%PKG%\客户端\使用说明.txt" echo   runtime 文件夹是内置的 Java 运行环境，请勿删除。
>> "%PKG%\客户端\使用说明.txt" echo   连接别人电脑上的服务端时，IP 填那台电脑的 IP。

REM ---- 服务端使用说明 ----
> "%PKG%\服务端\使用说明.txt" echo 局域网聊天室 - 服务端
>> "%PKG%\服务端\使用说明.txt" echo ================================
>> "%PKG%\服务端\使用说明.txt" echo.
>> "%PKG%\服务端\使用说明.txt" echo 运行方式：
>> "%PKG%\服务端\使用说明.txt" echo   双击 启动服务端.bat 即可（无需安装 Java，
>> "%PKG%\服务端\使用说明.txt" echo   runtime 文件夹已内置运行环境）。
>> "%PKG%\服务端\使用说明.txt" echo.
>> "%PKG%\服务端\使用说明.txt" echo 依赖说明（重要）：
>> "%PKG%\服务端\使用说明.txt" echo   服务端需要本机安装 MySQL，且允许局域网内其他
>> "%PKG%\服务端\使用说明.txt" echo   电脑访问。首次使用请把同目录 config.properties
>> "%PKG%\服务端\使用说明.txt" echo   中的数据库密码改成你自己 MySQL 的密码：
>> "%PKG%\服务端\使用说明.txt" echo     db.host=localhost    MySQL 地址
>> "%PKG%\服务端\使用说明.txt" echo     db.port=3306         MySQL 端口
>> "%PKG%\服务端\使用说明.txt" echo     db.user=root         MySQL 账号
>> "%PKG%\服务端\使用说明.txt" echo     db.password=你的MySQL密码
>> "%PKG%\服务端\使用说明.txt" echo   服务器启动时会自动创建数据库和表，无需手动建库。
>> "%PKG%\服务端\使用说明.txt" echo.
>> "%PKG%\服务端\使用说明.txt" echo 参数说明：
>> "%PKG%\服务端\使用说明.txt" echo   默认监听端口 8080，可用命令行参数覆盖：
>> "%PKG%\服务端\使用说明.txt" echo     java -jar LanChat-Server.jar 9000
>> "%PKG%\服务端\使用说明.txt" echo   （配置优先级：命令行参数 ^> config.properties ^> 默认值）
>> "%PKG%\服务端\使用说明.txt" echo.
>> "%PKG%\服务端\使用说明.txt" echo 注意：
>> "%PKG%\服务端\使用说明.txt" echo   runtime 文件夹是内置的 Java 运行环境，请勿删除。
>> "%PKG%\服务端\使用说明.txt" echo   客户端填写 IP 时填这台电脑的局域网 IP。

echo.
echo ============================================
echo  构建完成! 版本 %VERSION%
echo    发布目录: release\LanChat-%VERSION%\
echo      客户端: 客户端\LanChat-Client.jar
echo      服务端: 服务端\LanChat-Server.jar
echo      运行时: runtime\（便携 Java）
echo    发布包:   release\LanChat-%VERSION%.zip
echo ============================================

REM ---- 打包 zip（优先 tar，失败回退 PowerShell）----
echo.
echo 正在打包 LanChat-%VERSION%.zip ...
tar -a -c -f "%RELEASE%\LanChat-%VERSION%.zip" -C "%RELEASE%" "LanChat-%VERSION%" >nul 2>&1
if errorlevel 1 (
  powershell -NoProfile -Command "Compress-Archive -Path '%RELEASE%\LanChat-%VERSION%' -DestinationPath '%RELEASE%\LanChat-%VERSION%.zip' -Force" >nul 2>&1
)

REM ---- 校验产物：zip 必须存在且非空 ----
if not exist "%RELEASE%\LanChat-%VERSION%.zip" (
  echo [警告] 打包失败，请手动压缩 release\LanChat-%VERSION% 文件夹为 zip
) else (
  for %%A in ("%RELEASE%\LanChat-%VERSION%.zip") do set "ZIPSIZE=%%~zA"
  if "!ZIPSIZE!" LSS "10000" (
    echo [警告] 发布包异常偏小（!ZIPSIZE! 字节），请检查产物是否完整
  ) else (
    echo [完成] 发布包已生成: release\LanChat-%VERSION%.zip （!ZIPSIZE! 字节）
  )
)
echo.
echo 全部完成！
pause
exit /b 0

:fail
echo.
echo 构建失败! 请检查上方错误信息。
pause
exit /b 1
