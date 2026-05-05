English | [简体中文](README.zh-CN.md)

Run the ticketing API on host port 8080, then run k6 from the repository root:

```sh
docker run --rm --add-host=host.docker.internal:host-gateway -v "$PWD/loadtest:/scripts:ro" grafana/k6:2.3.0 run /scripts/seats.js
```

The script uses 32 virtual users for 20 seconds. Compare `http_reqs` and latency percentiles across executor choices. The Docker image is the official `grafana/k6` image. The command prints request counts and latency percentiles for the selected executor.

## About this repository

The code and documents in this repository were written with the help of AI tools; every recorded output comes from actually running them.
