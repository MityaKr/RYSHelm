#!/bin/sh
# Сервер и два клиента на этой машине (сценарий DuoTest). Вердикты: строки [duo] в run-duo/*/logs/latest.log
# Тяжёлый запуск: только когда не запущены сервер и игра автора и не идёт другой тяжёлый запуск.
cd "$(dirname "$0")/.." || exit 1
sh tools/duo-setup.sh
./gradlew runDuoServer --console=plain > run-duo/server.log 2>&1 &
sleep 45
./gradlew runDuoAlpha --console=plain > run-duo/alpha.log 2>&1 &
sleep 20
./gradlew runDuoBeta --console=plain > run-duo/beta.log 2>&1 &
wait
grep -h "\[duo\]" run-duo/alpha/logs/latest.log run-duo/beta/logs/latest.log | grep -E "PASS|FAIL"
