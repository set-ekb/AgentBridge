#!/usr/bin/env bash
# One-time private signing setup. Run locally (Termux/Linux/macOS); NEVER commit the keystore.
set +x
set -euo pipefail
umask 077

REPO="set-ekb/AgentBridge"
STORE_DIR="${HOME}/.config/agentbridge/signing"
STORE="${STORE_DIR}/agentbridge-release.p12"
ALIAS="agentbridge"

for program in keytool gh base64 tr sed; do
  if ! command -v "$program" >/dev/null 2>&1; then
    echo "Требуется команда: $program" >&2
    exit 1
  fi
done

mkdir -p "$STORE_DIR"
chmod 700 "$STORE_DIR"

if [[ ! -f "$STORE" ]]; then
  echo "Создаётся постоянный закрытый ключ: $STORE"
  echo "Выбери длинный пароль и сохрани его в менеджере паролей."
  keytool -genkeypair -v -storetype PKCS12 \
    -keystore "$STORE" -alias "$ALIAS" -keyalg RSA -keysize 3072 \
    -sigalg SHA256withRSA -validity 10000 \
    -dname "CN=AgentBridge, O=set-ekb"
  chmod 600 "$STORE"
else
  echo "Найден прежний ключ: $STORE (он НЕ будет заменён)"
fi

printf 'Пароль от постоянного ключа (ввод скрыт): '
IFS= read -r -s PASS
printf '\n'
if [[ -z "$PASS" ]]; then echo "Пароль не введён" >&2; exit 1; fi

# Verify the supplied password before writing any GitHub secrets.
if ! keytool -list -v -storetype PKCS12 -keystore "$STORE" \
    -storepass "$PASS" -alias "$ALIAS" >/dev/null 2>&1; then
  echo "Неверный пароль или отсутствует alias '$ALIAS'; секреты не изменены." >&2
  exit 1
fi

FP=$(keytool -list -v -storetype PKCS12 -keystore "$STORE" \
    -storepass "$PASS" -alias "$ALIAS" |
    sed -n 's/^[[:space:]]*SHA256: *//p' | head -n1 |
    tr -d ':' | tr '[:lower:]' '[:upper:]')
if [[ ! "$FP" =~ ^[A-F0-9]{64}$ ]]; then
  echo "Не удалось прочитать отпечаток сертификата" >&2
  exit 1
fi

echo "Проверяем доступ GitHub CLI к $REPO..."
gh auth status -h github.com >/dev/null || gh auth login

# All three repository Actions secrets are stored encrypted by GitHub.
# The private key and the password NEVER appear in the public repository.
printf '%s' "$PASS" | gh secret set AGENTBRIDGE_SIGNING_PASSWORD --repo "$REPO"
base64 < "$STORE" | tr -d '\n' | gh secret set AGENTBRIDGE_SIGNING_KEYSTORE_B64 --repo "$REPO"
printf '%s' "$FP" | gh secret set AGENTBRIDGE_SIGNING_CERT_SHA256 --repo "$REPO"
unset PASS

echo
echo "Подпись настроена. SHA-256 сертификата: $FP"
echo "Ключ: $STORE"
echo "ВАЖНО: сделай личную резервную копию этого .p12 и сохрани пароль."
echo "Не загружай файл ключа в публичные репозитории и не присылай его в чат."
echo
echo "Запускаем публикацию APK..."
gh workflow run build-apk.yml --repo "$REPO" --ref main
echo "Готовые APK появятся по постоянным ссылкам:"
echo "https://github.com/$REPO/releases/latest/download/AgentBridge.apk"
echo "https://github.com/$REPO/releases/latest/download/AgentBridge-Hooks.apk"
