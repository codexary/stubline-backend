English | [简体中文](README.zh-CN.md)

# Stubline

Stubline is Elsworne's event ticketing platform for events, seats, orders, payments, notifications and e-tickets. The current modular monolith has `catalog`, `inventory`, `orders`, `health` packages in `ticketing-api`. Inventory is in memory and resets on restart. Later features are added in subsequent chapters.

## Build and test

Install Java 21. The included Maven wrapper downloads Maven 3.9.16. From this repository root:

```sh
./mvnw -q verify
```

Expected: exit status 0; `-q` suppresses normal build output. The build runs JUnit tests and Spotless checks. To apply formatting, run `./mvnw spotless:apply`.

See [the project team](docs/team.md).

Run the CI checks locally with `./mvnw -q verify`; this also writes `target/bom.json`. CI scans the file system and SBOM for fixable HIGH and CRITICAL vulnerabilities. Renovate groups Maven, Docker image, and GitHub Actions updates once the Renovate app is installed.

## License

This repository is licensed under the [MIT License](LICENSE).

## About this repository

Elsworne, Stubline and the team are fictional.

The code and documents in this repository were written with the help of AI tools; every recorded output comes from actually running them.
