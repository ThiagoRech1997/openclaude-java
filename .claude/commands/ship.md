---
description: Valida build + testes completos e sugere o commit — não faz push.
argument-hint: "(sem args)"
---

Executa o checklist pré-envio do projeto. **Este comando nunca faz push.** Operações de alto impacto (push, force-push, branch manipulation remota) exigem confirmação humana explícita.

## Passos

1. Rode em paralelo: `git status`, `git diff --stat`, `git branch --show-current`.
2. Se working tree estiver limpo **e** não houver commits ahead de `origin/<branch>`: reporte "nada a shippar" e pare.
3. Rode `./gradlew clean build` (este comando inclui `test` na pipeline do Gradle).
4. Se falhar:
   - **Pare imediatamente**. Mostre a saída de erro relevante (não cole a saída inteira — só as linhas de erro).
   - Sugira o(s) arquivo(s) a investigar. **Não** tente "consertar por reflexo" — espere direção do usuário.
5. Se passar:
   - Mostre resumo do que mudou: arquivos alterados, nº de linhas, módulos tocados.
   - Se houver mudanças não commitadas: sugira invocar `/commit` para criar o commit seguindo o padrão do repo.
   - Se já estiver tudo commitado: informe o nº de commits ahead e **pergunte se o usuário quer fazer push** (não execute sem confirmação).

## Não faça

- Nunca `git push` sem o usuário confirmar explicitamente nesta conversa.
- Nunca use `--no-verify` em nada.
- Não commite automaticamente — delegue para `/commit`.
- Não rode testes de módulos individualmente pulando o `clean build` — o ponto é validar o build inteiro.
