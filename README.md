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

## UDP heartbeat and failure detection

The detector stays in `ticketing-api`'s `health` package because it observes these API instances and needs no separate operations deployment yet. Run these from the repository root in separate terminals:

```sh
./mvnw -q -pl ticketing-api exec:java -Dexec.mainClass=com.elsworne.stubline.health.HeartbeatMain -Dexec.args='detector 9999 700'
./mvnw -q -pl ticketing-api exec:java -Dexec.mainClass=com.elsworne.stubline.health.HeartbeatMain -Dexec.args='sender api-a 127.0.0.1 9999 200 3'
./mvnw -q -pl ticketing-api exec:java -Dexec.mainClass=com.elsworne.stubline.health.HeartbeatMain -Dexec.args='sender api-b 127.0.0.1 9999 200'
```

Expected detector transitions include `api-a ALIVE` and `api-b ALIVE`. Sender `api-a` logs `dropped api-a sequence=3` (and every third sequence); the detector remains alive if subsequent packets arrive before 700 ms. Stop a sender with Ctrl-C: about 700 ms after its last accepted packet the detector logs `api-a SUSPECTED`. Temporarily suspend the same sender process with Ctrl-Z and resume it with `fg`: it logs `api-a ALIVE` when a newer sequence arrives. A fresh sender process must use a new instance id until the detector restarts, because sequence numbers restart at 1. The tests also inject reordered and duplicate sequences and show a slow instance can be suspected and recover. `SUSPECTED` is only an observation about missing timely packets; it cannot distinguish a stopped process from delay or packet loss.

To inspect the UDP datagrams on Linux, in another terminal run `sudo tcpdump -i lo -n -X udp port 9999`; on macOS use `-i lo0`. Expected: UDP packets to port 9999 with a version byte, length-prefixed UTF-8 instance id, 64-bit sequence and 64-bit timestamp. This command needs capture privileges.

Run the CI checks locally with `./mvnw -q verify`; this also writes `target/bom.json`. CI scans the file system and SBOM for fixable HIGH and CRITICAL vulnerabilities. Renovate groups Maven, Docker image, and GitHub Actions updates once the Renovate app is installed.

## License

This repository is licensed under the [MIT License](LICENSE).

## About this repository

Elsworne, Stubline and the team are fictional.

The code and documents in this repository were written with the help of AI tools; every recorded output comes from actually running them.
