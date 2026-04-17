---
description: Cria boilerplate de um novo Tool em tools/ (classe + teste + registro em Main.java).
argument-hint: "<NomeDoTool> — CamelCase sem o sufixo 'Tool'. Ex: /new-tool Sleep"
---

Crie um novo tool no módulo `tools/` seguindo **exatamente** os padrões do repositório. Nome do tool: **$ARGUMENTS**.

## Convenções (validadas contra o código existente)

- **Package**: `dev.openclaude.tools.<nome-minusculo>` — pasta em `tools/src/main/java/dev/openclaude/tools/<nome-minusculo>/`.
- **Classe**: `<Nome>Tool.java` implementa `dev.openclaude.tools.Tool`.
- **Schema**: use `dev.openclaude.tools.SchemaBuilder` (`.object().stringProp(...).build()` etc.).
- **Retorno**: `ToolResult.success(text)` / `ToolResult.error(text)`.
- **Readonly flag**: implemente `isReadOnly()` retornando `true` só se o tool não altera estado externo (FS, rede, processos).
- **Teste**: `tools/src/test/java/dev/openclaude/tools/<nome-minusculo>/<Nome>ToolTest.java` — JUnit 5 com `@BeforeEach`, `@Nested @DisplayName(...)` agrupando por concern (mínimo: `Metadata` + `execute`). Use `ObjectMapper` e `ObjectNode` para montar inputs. `ToolUseContext` padrão: `new ToolUseContext(Path.of("."), false)`.

**Arquivo de referência (copie o estilo, não o conteúdo):**
- Classe: [tools/src/main/java/dev/openclaude/tools/kill/KillProcessTool.java](tools/src/main/java/dev/openclaude/tools/kill/KillProcessTool.java)
- Teste: [tools/src/test/java/dev/openclaude/tools/kill/KillProcessToolTest.java](tools/src/test/java/dev/openclaude/tools/kill/KillProcessToolTest.java)

## Passos obrigatórios

1. **Valide o argumento** `$ARGUMENTS`:
   - Deve ser CamelCase, começar com letra maiúscula, sem sufixo `Tool`.
   - Se vazio/inválido: peça ao usuário o nome correto e pare.
2. **Pergunte ao usuário** (brevemente, em uma única mensagem AskUserQuestion se disponível, senão texto) apenas o que não dá pra inferir:
   - Descrição curta (vira `description()` e o javadoc).
   - Principais parâmetros de input (nome + tipo + obrigatório? + descrição).
   - É read-only? (não muda FS, rede, processos nem estado.)
3. **Crie** `tools/src/main/java/dev/openclaude/tools/<nome-minusculo>/<Nome>Tool.java` — implementação mínima funcional (não deixe TODO no `execute()`; se o usuário não detalhou a lógica, entregue uma implementação óbvia ou marque claramente).
4. **Crie** o teste em `tools/src/test/java/dev/openclaude/tools/<nome-minusculo>/<Nome>ToolTest.java` com no mínimo:
   - `@Nested Metadata` → testa `name()` e `isReadOnly()`.
   - `@Nested execute` → happy path + pelo menos um caminho de erro (input inválido).
5. **REGISTRE o tool** em [cli/src/main/java/dev/openclaude/cli/Main.java](cli/src/main/java/dev/openclaude/cli/Main.java) — esse passo é **load-bearing**, sem ele o tool não aparece no runtime:
   - Local da edição: dentro do método `createToolRegistry`, junto com as outras linhas `registry.register(new ...Tool());` (hoje por volta das linhas 189–213).
   - Adicione o import no topo do arquivo.
   - Posicione a linha de registro seguindo o agrupamento lógico existente (bash/file/web/todo/etc.).
6. **Rode o teste** do novo tool: `./gradlew :tools:test --tests "<Nome>ToolTest"`. Se falhar, **conserte antes de terminar** — não entregue teste vermelho.
7. **Build quick check**: `./gradlew :cli:compileJava` para garantir que a edição em Main.java compilou.
8. Reporte ao final: caminhos dos 3 arquivos tocados (classe, teste, Main.java) e resultado do `./gradlew :tools:test`.

## Não faça

- Não registre em lugares além de `Main.java` — não há auto-discovery para tools built-in.
- Não invente parâmetros que o usuário não pediu (principle: "don't design for hypothetical future requirements").
- Não pule o teste nem o build check — o tool de registro central é frágil e um erro de import/sintaxe quebra o CLI inteiro.
