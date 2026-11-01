#!/usr/bin/env bash
# Проверка ЛКС Сириуса: вход и журнал успеваемости — так же, как это делает приложение «Наставник».
# Запускать на своём компьютере с российским интернетом (без VPN): bash lks_check.sh
# Пароль спрашивается при запуске, нигде не сохраняется и в вывод не попадает.
# Куки и страницы лежат во временной папке и удаляются в конце.
set -u
BASE="https://lks.siriusuniversity.ru"
UA="Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
D="$(mktemp -d)"
trap 'rm -rf "$D"' EXIT
J="$D/cookies"
C=(curl -sS -c "$J" -b "$J" -A "$UA" -H "Accept-Language: ru-RU,ru;q=0.9" --max-time 40)

read -rp "Почта ЛКС: " EMAIL
read -rsp "Пароль ЛКС (не отображается): " PASS
echo
echo

echo "== 1. Страница входа"
"${C[@]}" -o "$D/login.html" -w "код %{http_code}, адрес %{url_effective}\n" "$BASE/login"
if grep -q "ограничен доступ" "$D/login.html"; then
  echo "!! ЛКС считает, что вы не в России (включён VPN?). Выключите VPN и запустите снова."
  exit 1
fi
TOKEN="$(grep -o 'name="_token" value="[^"]*"' "$D/login.html" | head -1 | sed 's/.*value="//; s/"$//')"
echo "токен формы: $([ -n "$TOKEN" ] && echo "есть" || echo "НЕТ")"
echo "поля формы: $(grep -o '<input[^>]*name="[^"]*"' "$D/login.html" | sed 's/.*name="//; s/"$//' | tr '\n' ' ')"
echo "куда отправляется форма: $(grep -o '<form[^>]*action="[^"]*"' "$D/login.html" | head -1 | sed 's/.*action="//; s/"$//')"
echo

echo "== 2. Вход"
"${C[@]}" -L -o "$D/after.html" \
  -w "код %{http_code}, итоговый адрес %{url_effective}, переходов %{num_redirects}\n" \
  -H "Referer: $BASE/login" -H "Origin: $BASE" \
  --data-urlencode "_token=$TOKEN" \
  --data-urlencode "login=$EMAIL" \
  --data-urlencode "password=$PASS" \
  --data-urlencode "remember=on" \
  "$BASE/login"
unset PASS
echo "куки после входа (только имена): $(awk '!/^#/ && NF>=7 {printf "%s ", $6}' "$J")"
echo "заголовок страницы: $(grep -o '<title>[^<]*' "$D/after.html" | head -1 | sed 's/<title>//')"
ERR="$(grep -oiE '(неверн|ошибк|не найден|не совпада|слишком много|подтверд|код из|invalid|throttle|credentials)[^<]{0,140}' "$D/after.html" | head -5)"
[ -n "$ERR" ] && echo "сообщения на странице:" && echo "$ERR"
grep -q 'name="password"' "$D/after.html" && echo "!! после входа снова видна форма пароля — вход не удался"
echo

echo "== 3. Страница журнала"
"${C[@]}" -L -o "$D/journal.html" -w "код %{http_code}, адрес %{url_effective}\n" "$BASE/journal"
CSRF="$(grep -o 'name="csrf-token" content="[^"]*"' "$D/journal.html" | head -1 | sed 's/.*content="//; s/"$//')"
echo "csrf-токен: $([ -n "$CSRF" ] && echo "есть" || echo "НЕТ")"
START="$(grep -oE 'startPeriod:[[:space:]]*'"'"'?[0-9]{4}-[0-9]{2}-[0-9]{2}' "$D/journal.html" | head -1 | grep -oE '[0-9]{4}-[0-9]{2}-[0-9]{2}')"
END="$(grep -oE 'endPeriod:[[:space:]]*'"'"'?[0-9]{4}-[0-9]{2}-[0-9]{2}' "$D/journal.html" | head -1 | grep -oE '[0-9]{4}-[0-9]{2}-[0-9]{2}')"
echo "период на странице: ${START:-?} … ${END:-?}"
echo "адреса запросов на странице: $(grep -oE "fetch\('[^']+'" "$D/journal.html" | sed "s/fetch('//; s/'$//" | sort -u | tr '\n' ' ')"
echo

echo "== 4. Запрос оценок (/journal/get)"
BODY="{\"startPeriod\":\"$START\",\"interval\":\"year\",\"col\":400,\"endPeriod\":\"$END\",\"numPeriod\":0,\"search\":\"\",\"lectures\":false,\"seminars\":false,\"practices\":false,\"laboratories\":false}"
XSRF="$(awk '!/^#/ && $6=="XSRF-TOKEN" {print $7}' "$J" | python3 -c 'import sys,urllib.parse;print(urllib.parse.unquote(sys.stdin.read().strip()))' 2>/dev/null)"
for i in $(seq 1 12); do
  CODE="$("${C[@]}" -o "$D/get.json" -w "%{http_code}" \
    -H "Content-Type: application/json" -H "Accept: application/json" \
    -H "X-Requested-With: XMLHttpRequest" -H "X-CSRF-TOKEN: $CSRF" \
    ${XSRF:+-H "X-XSRF-TOKEN: $XSRF"} \
    -H "Referer: $BASE/journal" -H "Origin: $BASE" \
    --data "$BODY" "$BASE/journal/get")"
  echo "попытка $i: код $CODE, размер $(wc -c < "$D/get.json") байт"
  if grep -q '"cacheBuilding":true' "$D/get.json"; then echo "  сайт ещё собирает журнал, жду 3 с…"; sleep 3; continue; fi
  break
done
echo
echo "начало ответа:"
head -c 1500 "$D/get.json"
echo
echo
python3 - "$D/get.json" <<'PY' 2>/dev/null || echo "(ответ не JSON)"
import json, sys
d = json.load(open(sys.argv[1]))
print("ключи ответа:", list(d.keys()) if isinstance(d, dict) else type(d).__name__)
ls = d.get("lessons") if isinstance(d, dict) else None
ls = list(ls.values()) if isinstance(ls, dict) else (ls or [])
print("дисциплин:", len(ls))
for l in ls[:5]:
    items = l.get("items") or {}
    marks = sum(len(e.get("marks") or []) for v in (items.values() if isinstance(items, dict) else []) for e in (v if isinstance(v, list) else []))
    print(" -", l.get("title"), "| ср.балл:", l.get("avg_mark"), "| оценок:", marks, "| поля:", sorted(l.keys()))
PY
echo
echo "Готово. Скопируйте весь вывод выше и пришлите. Пароля в нём нет."
