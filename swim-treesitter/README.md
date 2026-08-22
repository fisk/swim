# SWIM Tree-sitter plugin

This is the Java-only foundation for Tree-sitter support. It deliberately starts
with the portable output of `tree-sitter generate` rather than JNI, Wasm, or an
attempt to execute `grammar.js`.

## Current phase

`TreeSitterGrammarInspector` reads `grammar.json` into a recursive Java model,
including rule children, fields, aliases, precedence, repeats, and conflicts.
`TreeSitterQueryParser` reads structural `highlights.scm` and `injections.scm`
patterns, captures, predicates, and alternatives; predicates are preserved for later evaluation. `TreeSitterGrammarAssetDiscoveryService` discovers conventional `grammar.json` and `queries/` assets on plugin-owned workers. The plugin exposes grammar inspection through the read-only Nemo tool
`inspect_tree_sitter_grammar` for paths inside the active project.

Every analysis request receives an immutable `TreeSitterGrammarSnapshot`.
`TreeSitterGrammarAnalysisService` runs it on plugin-owned workers and publishes
only the newest submitted snapshot. This is the concurrency contract that later
parser, query, and highlight work must retain.

`TreeSitterSubsetRuntime` is an executable, Java-only experiment for generated
rules using `STRING`, `PATTERN`, `SYMBOL`, `SEQ`, `CHOICE`, repetition,
optionals, fields, aliases, and simple precedence/token wrappers. It emits
immutable syntax spans and errors through `TreeSitterSyntaxSnapshot` and
`TreeSitterSyntaxAnalysisService`. Recursive rules, complex extras, and all
unrecognized generated rule types are reported explicitly; this is not yet a
general Tree-sitter LR/GLR runtime.

Before parsing, `TreeSitterSubsetCompatibilityAnalyzer` reports unsupported
generated rule forms, external scanners, complex extras, and recursive symbol
cycles. This is the integration gate for future C/C++ and Java assets. Syntax
analysis is newest-per-document, so a slow C++ snapshot never suppresses an
unrelated Java buffer's result. `TreeSitterBufferSyntaxBridge` connects editor
content revisions to those snapshots, returns results through the UI event
thread, and writes a version-gated syntax overlay below language-mode/LSP
colouring. Its grammar-literal fallback derives keyword colouring from grammar
literals instead of a maintained keyword list.

`TreeSitterQueryExecutor` now executes parsed structural patterns over portable
syntax spans, including captures, fields, alternatives, and `#match?` / `#eq?`
predicates. Its output is the stable capture boundary for converting official
`highlights.scm` files into syntax overlays once a full grammar tree is
available.

The bundled upstream C, C++, and Java generated grammars plus highlight queries
are packaged with the plugin. C++ inherits its C grammar before terminal
extraction. This immediate fallback deliberately only colours literal terminals
declared by the assets; a token absent from an upstream grammar (such as the
current C++ asset's `static_cast`) is not special-cased. Full structural parsing
and query execution remain the route for that case.

## Upstream grammar dependencies

The grammar sources are pinned Git submodules under `third_party/` from the
official Tree-sitter organization: `tree-sitter-c`, `tree-sitter-cpp`,
`tree-sitter-java`, and the maintained split-parser `tree-sitter-markdown`.
Builds package their generated `grammar.json`, queries, and
parser tables directly from those revisions. To deliberately advance a grammar,
run `git submodule update --remote third_party/tree-sitter-cpp` (or the matching
language), review the generated-asset tests, and commit the new submodule SHA.

The matching generated `parser.c` tables for Java and C++ are also bundled as
data. `TreeSitterGeneratedParserInspector` reads their state, symbol, token,
field, and external-scanner requirements without compiling or loading C. This
is the compatibility boundary for a future Java parser-table interpreter:
Java has 1,385 states and no external scanner; C++ has 11,734 states and two
external tokens that need Java scanner implementations.

## Planned phases

1. Add asset inspection commands/panels and capture-to-theme mappings.
2. Add parser benchmarks, recovery fixtures, and a broader generated-rule
   compatibility matrix.
3. Implement the generated parser-table interpreter, beginning with Java's
   no-external-scanner tables, then C++ scanner support.
4. Integrate C/C++ and Java grammar/query assets as syntax overlays beneath
   clangd and the Java language server.

The existing C/C++ and Java LSP plugins remain the authority for diagnostics,
completion, navigation, and refactoring. Tree-sitter is intended to supply
immediate syntax information and, later, structural editor features.
