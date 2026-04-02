@echo off
title Compilador CoinRent v1.0 - Java 17

echo ============================================
echo Compilador do Plugin CoinRent (Sistema de Aluguel)
echo ============================================
echo.

echo Procurando Java 17 instalado...
echo.

set JDK_PATH=

rem Procura JDK 17 em locais comuns
for /d %%i in ("C:\Program Files\Java\jdk-17*") do set JDK_PATH=%%i
for /d %%i in ("C:\Program Files\Java\jdk17*") do set JDK_PATH=%%i
for /d %%i in ("C:\Program Files\Eclipse Adoptium\jdk-17*") do set JDK_PATH=%%i
for /d %%i in ("C:\Program Files\AdoptOpenJDK\jdk-17*") do set JDK_PATH=%%i
for /d %%i in ("C:\Program Files\OpenJDK\jdk-17*") do set JDK_PATH=%%i
for /d %%i in ("C:\Program Files\Amazon Corretto\jdk17*") do set JDK_PATH=%%i
for /d %%i in ("C:\Program Files\Microsoft\jdk-17*") do set JDK_PATH=%%i

if "%JDK_PATH%"=="" (
    echo ============================================
    echo ERRO: JDK 17 nao encontrado!
    echo Instale o Java 17 JDK e tente novamente.
    echo ============================================
    pause
    exit /b 1
)

echo Java 17 encontrado em: %JDK_PATH%
echo.

set JAVAC="%JDK_PATH%\bin\javac.exe"
set JAR="%JDK_PATH%\bin\jar.exe"

echo ============================================
echo Preparando ambiente de compilacao...
echo ============================================
echo.

echo Limpando pasta out...
if exist out (
    rmdir /s /q out >nul 2>&1
)
mkdir out
mkdir out\com
mkdir out\com\foxsrv
mkdir out\com\foxsrv\coinrent

echo.
echo ============================================
echo Verificando dependencias...
echo ============================================
echo.

REM Verificar Spigot API
if not exist spigot-api-1.20.1-R0.1-SNAPSHOT.jar (
    echo [ERRO] spigot-api-1.20.1-R0.1-SNAPSHOT.jar nao encontrado!
    echo.
    echo Certifique-se de que o arquivo esta na pasta raiz.
    pause
    exit /b 1
) else (
    echo [OK] Spigot API encontrado
)

REM Verificar Gson
if exist libs\gson-2.10.1.jar (
    echo [OK] Gson encontrado em libs\
    set GSON_PATH=libs\gson-2.10.1.jar
) else if exist gson-2.10.1.jar (
    echo [OK] Gson encontrado na pasta raiz
    set GSON_PATH=gson-2.10.1.jar
) else (
    echo [AVISO] gson-2.10.1.jar nao encontrado!
    echo O plugin CoinRent usa Gson para serializacao JSON.
    echo Continuando compilacao sem Gson...
    echo.
    set GSON_PATH=
)

REM Verificar CoinCard API (DEPENDENCIA OBRIGATORIA)
if not exist CoinCard.jar (
    echo ============================================
    echo ERRO: CoinCard.jar nao encontrado!
    echo ============================================
    echo.
    echo O plugin CoinRent REQUER o CoinCard.jar como dependencia!
    echo.
    echo Certifique-se de que o arquivo CoinCard.jar esta na pasta raiz.
    echo.
    echo Voce pode obter o CoinCard.jar em:
    echo - https://github.com/FoxSRV/CoinCard
    echo - Ou compilar o plugin CoinCard primeiro
    echo.
    pause
    exit /b 1
) else (
    echo [OK] CoinCard.jar encontrado (dependencia obrigatoria)
    set COINCARD_PATH=CoinCard.jar
)

REM Verificar Vault API (opcional para o CoinCard)
if not exist Vault.jar (
    echo [AVISO] Vault.jar nao encontrado na pasta raiz!
    echo O CoinCard requer Vault para funcionar corretamente.
    echo Certifique-se de ter o Vault instalado no servidor.
    echo Continuando compilacao mesmo assim...
    echo.
    set VAULT_PATH=
) else (
    echo [OK] Vault API encontrado (opcional)
    set VAULT_PATH=Vault.jar
)

echo.
echo ============================================
echo Compilando CoinRent...
echo ============================================
echo.

REM Montar classpath
set CLASSPATH="spigot-api-1.20.1-R0.1-SNAPSHOT.jar";"%COINCARD_PATH%"
if defined GSON_PATH (
    set CLASSPATH=%CLASSPATH%;"%GSON_PATH%"
)
if defined VAULT_PATH (
    set CLASSPATH=%CLASSPATH%;"%VAULT_PATH%"
)

REM Mostrar classpath para debug
echo Classpath: %CLASSPATH%
echo.

REM Verificar se o arquivo fonte existe
if not exist src\com\foxsrv\coinrent\CoinRent.java (
    echo ============================================
    echo ERRO: Arquivo fonte nao encontrado!
    echo ============================================
    echo.
    echo Caminho esperado: src\com\foxsrv\coinrent\CoinRent.java
    echo.
    echo Estrutura de diretorios atual:
    echo.
    if exist src (
        echo Conteudo de src:
        dir /s /b src
    ) else (
        echo Pasta src nao encontrada!
    )
    echo.
    echo Criando estrutura de diretorios necessaria...
    mkdir src\com\foxsrv\coinrent 2>nul
    echo Por favor, coloque o arquivo CoinRent.java em src\com\foxsrv\coinrent\
    pause
    exit /b 1
)

REM Compilar com as dependências necessárias
echo Compilando CoinRent.java...
%JAVAC% --release 17 -d out ^
-classpath %CLASSPATH% ^
-sourcepath src ^
-encoding UTF-8 ^
src\com\foxsrv\coinrent\CoinRent.java

if %errorlevel% neq 0 (
    echo ============================================
    echo ERRO AO COMPILAR O PLUGIN!
    echo ============================================
    echo.
    echo Verifique os erros acima e corrija o codigo.
    echo.
    echo Possiveis causas:
    echo 1 - Erro de sintaxe no codigo
    echo 2 - Versao do Java incorreta
    echo 3 - CoinCard.jar nao encontrado ou incompativel
    echo 4 - Gson nao encontrado ou incompativel
    echo 5 - Spigot API nao encontrada ou incompativel
    pause
    exit /b 1
)

echo.
echo Compilacao concluida com sucesso!
echo.

echo ============================================
echo Copiando arquivos de recursos...
echo ============================================
echo.

REM Copiar plugin.yml
if exist resources\plugin.yml (
    copy resources\plugin.yml out\ >nul
    echo [OK] plugin.yml copiado
) else (
    echo [AVISO] plugin.yml nao encontrado em resources\
    echo Criando plugin.yml padrao...
    
    (
        echo name: CoinRent
        echo version: 1.0.0
        echo main: com.foxsrv.coinrent.CoinRent
        echo api-version: 1.20
        echo depend: [CoinCard]
        echo softdepend: [Vault]
        echo author: FoxSRV
        echo description: Rent system for CoinCard plugin - Rent items per hour
        echo.
        echo commands:
        echo   crent:
        echo     description: Main CoinRent command
        echo     usage: /crent
        echo     aliases: [coinrent, rentshop]
        echo     permission: coinrent.use
        echo.
        echo permissions:
        echo   coinrent.use:
        echo     description: Allows using /crent command
        echo     default: true
        echo   coinrent.admin:
        echo     description: Allows admin commands
        echo     default: op
    ) > out\plugin.yml
    echo [OK] plugin.yml criado automaticamente
)

REM Copiar config.yml
if exist resources\config.yml (
    copy resources\config.yml out\ >nul
    echo [OK] config.yml copiado
) else (
    echo [AVISO] config.yml nao encontrado em resources\
    echo Criando config.yml padrao...
    
    (
        echo # CoinRent Configuration
        echo # Server Card ID for collecting taxes
        echo ServerCard: ""
        echo.
        echo # Tax rate (0.1 = 10%%^)
        echo Tax: 0.1
        echo.
        echo # Minimum and maximum price for rentals
        echo Min: 0.00000001
        echo Max: 1000.0
        echo.
        echo # Cooldown between transactions in milliseconds
        echo Cooldown: 1000
    ) > out\config.yml
    echo [OK] config.yml criado automaticamente
)

echo.
echo ============================================
echo Criando arquivo JAR...
echo ============================================
echo.

cd out

REM Criar JAR com todos os recursos
echo Criando CoinRent.jar...
%JAR% cf CoinRent.jar com plugin.yml config.yml

cd ..

echo.
echo ============================================
echo PLUGIN COMPILADO COM SUCESSO!
echo ============================================
echo.
echo Arquivo gerado: out\CoinRent.jar
echo.
dir out\CoinRent.jar
echo.
echo ============================================
echo RESUMO DA COMPILACAO:
echo ============================================
echo.
echo - Data/Hora: %date% %time%
echo - Java Version: 17
echo - Spigot API: OK
echo - CoinCard API: OK (OBRIGATORIO)
if defined VAULT_PATH (
    echo - Vault API: OK (para CoinCard)
) else (
    echo - Vault API: NAO ENCONTRADO (necessario para CoinCard)
)
if defined GSON_PATH (
    echo - Gson: OK
) else (
    echo - Gson: NAO ENCONTRADO (opcional, mas recomendado)
)
echo - Arquivo fonte: src\com\foxsrv\coinrent\CoinRent.java
echo.
echo ============================================
echo ARQUIVOS COMPILADOS:
echo ============================================
echo.
dir /b src\com\foxsrv\coinrent\*.java
echo.
echo ============================================
echo REQUISITOS PARA EXECUCAO:
echo ============================================
echo.
echo 1 - Spigot/Paper 1.20+ necessario
echo 2 - Java 17 ou superior
echo 3 - CoinCard.jar instalado no servidor (OBRIGATORIO)
echo 4 - Vault.jar instalado no servidor (OBRIGATORIO para o CoinCard)
echo.
echo ============================================
echo Para instalar:
echo ============================================
echo.
echo 1 - Copie CoinCard.jar e Vault.jar para a pasta plugins do servidor
echo 2 - Copie out\CoinRent.jar para a pasta plugins do servidor
echo 3 - Reinicie o servidor ou use /reload confirm
echo 4 - Configure o ServerCard no arquivo plugins/CoinRent/config.yml
echo 5 - Os dados serao salvos em plugins/CoinRent/rentals.dat
echo.
echo ============================================
echo COMANDOS DO PLUGIN:
echo ============================================
echo.
echo JOGADORES:
echo /crent - Abre o menu principal
echo /crent rent ^<amount^> ^<price^> - Aluga o item na mao
echo /crent cancel - Abre menu de cancelamento
echo /crent name ^<shop name^> - Define nome da loja
echo.
echo ADMIN:
echo /crent reload - Recarrega a configuracao
echo.
echo ============================================
echo PERMISSOES:
echo ============================================
echo.
echo coinrent.use - Pode usar /crent (default: true)
echo coinrent.admin - Pode usar comandos admin (default: op)
echo.
echo ============================================
echo FUNCIONALIDADES:
echo ============================================
echo.
echo - Aluguel de itens por hora
echo - Cobranca automatica a cada 1 hora
echo - Sistema de fila para evitar conflitos
echo - Protecao contra dupe: itens nao podem ser dropados
echo - Itens nao podem ser armazenados em baus
echo - Durabilidade preservada ao retornar
echo - Cancelamento de aluguel a qualquer momento
echo - Taxa do servidor configuravel
echo - Integracao total com CoinCard API
echo - BigDecimal com 8 casas decimais para precisao
echo - Processamento assincrono sem lag
echo - Suporte apenas a ferramentas, armaduras e utilitarios
echo - Bloqueio de itens consumiveis
echo.
echo ============================================
echo.

pause
