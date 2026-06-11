#!/bin/bash

while :
do
cat PROMPT.md | codex --search --dangerously-bypass-approvals-and-sandbox exec
done
