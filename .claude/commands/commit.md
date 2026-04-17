---
description: Cria um commit seguindo o padrão do repo — "<tipo>: <desc> (TFR-XX)" + trailer Co-Authored-By.
argument-hint: "[corpo curto opcional — se omitido, infira a partir do diff]"
---

Crie um commit no repositório atual seguindo **exatamente** a convenção observada no histórico deste projeto.

## Contexto do usuário
Corpo/observação adicional (pode estar vazio): **$ARGUMENTS**

## Passos

1. Rode em paralelo: `git status`, `git diff`, `git diff --cached`, `git log -5 --oneline`, `git branch --show-current`.
2. **Descubra o TFR-XX**:
   - Primeiro: procure `TFR-\d+` no nome do branch atual.
   - Se não achar: procure nas mensagens dos últimos commits em que issue está sendo trabalhada.
   - Se ainda não achar: **pergunte ao usuário** (não invente).
3. **Escolha o tipo** a partir do diff: `feat` (funcionalidade nova), `fix` (correção), `docs` (somente docs/markdown), `refactor` (sem mudança de comportamento), `test` (só testes), `chore` (build, deps, configs).
4. **Monte o subject**: `<tipo>: <descrição curta em inglês, imperativo> (TFR-XX)` — máximo ~72 chars. Foque no *porquê* quando couber, não só no *o quê*.
5. **NÃO adicione** arquivos sensíveis. Se houver `.env`, `*.local.json` ou segredos no `git status`, **alerte** e pare.
6. Faça `git add` **somente** dos arquivos relevantes (por nome — evite `-A`).
7. Crie o commit com a mensagem via HEREDOC. Se o usuário forneceu `$ARGUMENTS`, use como corpo (linha em branco antes). Termine **sempre** com o trailer:

```
Co-Authored-By: Claude <noreply@anthropic.com>
```

8. Rode `git status` depois para confirmar que o commit saiu limpo.

## Exemplos do histórico (siga este estilo)

- `feat: TodoWrite tool for session task tracking (TFR-63)`
- `feat: FileWriteTool requires prior Read before overwrite (TFR-58)`
- `feat: FileReadTool supports images, PDFs, and Jupyter notebooks (TFR-57)`
- `feat: add security sandbox to BashTool (TFR-55)`

## Não faça

- **Nunca** use `--no-verify` ou `--amend` sem pedido explícito.
- **Nunca** faça `git push` — commit é o escopo deste comando.
- **Nunca** commite `.env`, `settings.local.json`, `build/` ou `.gradle/`.
- Não invente o TFR-XX — pergunte se não conseguir inferir.
