# Aether

A minimal but powerful agent in your terminal.

<img width="775" height="597" alt="aether-screenshot" src="https://github.com/user-attachments/assets/c8e4d98e-14db-42e7-80c2-06d0e6cfd52f" />

https://github.com/user-attachments/assets/97576240-74d8-47e3-9987-e99313c0ed5f

## Quickstart

macOS:

```bash
brew install lingjiuu/aether/aether
```

macOS and Windows:

```bash
npm install -g @lingjiuu/aether@latest
```

Aether requires Node.js 22+. The backend Java runtime is bundled.

## Start

Run Aether from your project directory:

```bash
aether
```

On first launch, Aether guides you through the model provider settings and writes them to:

```text
~/.aether/config.toml
```

You can edit this file later to change the provider, base URL, API key, model, or context window.

## Evaluate

Aether includes a Terminal-Bench 2.1 runner. Clone the source repository first:

```bash
git clone https://github.com/lingjiuu/aether.git
cd aether
```

Then prepare Java and the local evaluation config.

See the full guide:

```text
evals/terminal-bench/README.md
```

Create the eval config:

```bash
cp evals/aether-eval.example.toml evals/aether-eval.toml
```

Edit `evals/aether-eval.toml`, then run one task first:

```bash
evals/terminal-bench/run.sh --n-tasks 1
```

Results and logs are saved under:

```text
evals/results/terminal-bench
```
