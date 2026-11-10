#!/usr/bin/env bash
# Подтягивает свежий upstream и сливает его с русской локализацией.
set -euo pipefail

UPSTREAM_URL="${UPSTREAM_URL:-https://github.com/StepaandAlex69/DriftWM-for-Android.git}"
UPSTREAM_BRANCH="${UPSTREAM_BRANCH:-main}"

if ! git remote get-url upstream >/dev/null 2>&1; then
  git remote add upstream "$UPSTREAM_URL"
fi

echo "→ fetch upstream"
git fetch upstream

echo "→ merge upstream/$UPSTREAM_BRANCH"
if git merge --no-edit "upstream/$UPSTREAM_BRANCH"; then
  echo
  echo "Готово. Если всё чисто — запушьте: git push"
else
  echo
  echo "Есть конфликты. Исправьте файлы:"
  git diff --name-only --diff-filter=U
  echo
  echo "После исправления: git add -A && git commit && git push"
  exit 1
fi
