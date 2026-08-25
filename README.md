# Language Server Based on Incremental LL Parsing

This repository contains a language server for the educational programming language Prev with incremental LL(1) parsing and table-driven error recovery. Name resolution is based on an incrementally updated tree of lexical scopes. Type checking relies on recorded dependencies between language constructs. After an edit, the server re-lexes only the affected lines and incrementally updates the results of syntax and semantic analyses.

The specification of the Prev language is available in [prev26.pdf](prev26.pdf).

## Repository contents

- [prev26lsp](prev26lsp/): the main project, containing the Prev language server.
    - [Lexer](prev26lsp/src/main/jflex/): recognizes Prev tokens and re-lexes the affected lines
      after an edit.
    - [Parser](prev26lsp/src/main/java/prev26lsp/parser/): updates the LL(1) parse tree, reuses
      unchanged subtrees, and recovers from syntax errors.
    - [Semantic analysis](prev26lsp/src/main/java/prev26lsp/semantics/): incrementally updates name
      resolution and type checking, and produces semantic tokens.
    - [Semantic benchmarks](prev26lsp/src/jmh/): compare incremental semantic updates with a full
      semantic reanalysis.
- [parser-gen](parser-gen/): constructs the extended LL(1) parse table and adds the error recovery
  entries used by the parsers.
- [parser-bench](parser-bench/): compares incremental parsers using an unflattened tree, a dynamic
  array, or an implicit treap against a parser that reparses the entire document.

The server supports go to definition, finding and highlighting references, renaming, hover and
signature help, completion with visible names, document symbols, semantic highlighting of names,
and diagnostics. Each document is analyzed independently, so name and reference resolution across
files is not supported.

## Building and running

JDK 25 is required to build the `prev26lsp` and `parser-bench` projects. The benchmark result
scripts require Python 3, and generating `positions.pdf` also requires Matplotlib.

```bash
cd prev26lsp

# Build an executable JAR containing the server and its dependencies.
./gradlew shadowJar

# Start the language server over standard input and output.
java -jar build/libs/prev26lsp-1.0-SNAPSHOT-all.jar
```

The server uses the LSP4J library for the LSP interfaces, data types, and JSON-RPC communication
with the client over standard input and output.

## Evaluation

### Syntax analysis

The `parser-bench` project compares four parsing implementations:

- `baseline`: incremental parsing with an unflattened tree;
- `array`: a flattened representation backed by a dynamic array;
- `treap`: a flattened representation backed by a randomized balanced tree;
- `prevFull`: a full reparse with an unflattened tree.

```bash
cd parser-bench

# Run the edit-position benchmarks and write benchmarks/positions.csv.
./gradlew jmh

# Run the expression-tail benchmarks and write benchmarks/tail.csv.
./gradlew jmhTail

cd benchmarks

# Generate positions.pdf from positions.csv.
python3 plot.py

# Format and print the benchmark measurements.
python3 table.py
```

The first Gradle task writes `benchmarks/positions.csv`, and the second writes
`benchmarks/tail.csv`. `plot.py` generates
[`positions.pdf`](parser-bench/benchmarks/positions.pdf), and `table.py` prints the benchmark
measurements.

`jmhTail` measures a change to the first operand of an arithmetic expression in a function body.
After a valid replacement, the unchanged suffix remains reusable. Replacing the operand with
`end` terminates the function body early, forcing the parser to attach the remaining children
individually.

### Semantic analysis

The generated program contains three mutually dependent named types, `n` functions that use one
of those types, and a shared global variable. It also contains an auxiliary named type and function
to measure a named type with a single use.

The benchmarks compare an incremental update with a full reanalysis, separately for name
resolution and type checking.

```bash
cd prev26lsp

# Run the semantic-update benchmarks and write benchmarks/semantic-update-jmh.json.
./gradlew jmh

cd benchmarks

# Format and print the benchmark measurements.
python3 table.py
```

The results are written to `benchmarks/semantic-update-jmh.json`. `table.py` formats them for
terminal output.
