#!/bin/sh
# Папка run-duo/server для DuoTest: EULA, порт 25578 без проверки входа, плоский мир, Alpha оператор.
cd "$(dirname "$0")/.." || exit 1
mkdir -p run-duo/server run-duo/alpha run-duo/beta
[ -f run-duo/server/eula.txt ] || echo "eula=true" > run-duo/server/eula.txt
if [ ! -f run-duo/server/server.properties ]; then
  printf 'online-mode=false\nserver-port=25578\nlevel-type=minecraft\:flat\nspawn-protection=0\nspawn-monsters=false\nmotd=RYSHelm duo\n' > run-duo/server/server.properties
fi
# UUID игрока Alpha без проверки входа: d4f0704a-3a4c-3f30-80e5-695c1cf28fe2
[ -f run-duo/server/ops.json ] || printf '[{"uuid":"d4f0704a-3a4c-3f30-80e5-695c1cf28fe2","name":"Alpha","level":4,"bypassesPlayerLimit":false}]\n' > run-duo/server/ops.json
for c in alpha beta; do
  [ -f run-duo/$c/options.txt ] || printf 'soundCategory_master:0.0\npauseOnLostFocus:false\nonboardAccessibility:false\n' > run-duo/$c/options.txt
done
