# Security

This is a local-first desktop/browser app. It is designed to bind to loopback
addresses and store data in local SQLite files, not to run as a public web
service.

## Supported Use

- Run the backend on `127.0.0.1`.
- Keep the SQLite database files on a trusted local disk.
- Do not expose the FastAPI server to a LAN or the public internet.

## Reporting Issues

Report security issues through GitHub's private vulnerability reporting flow
on this repository. Do not open a public issue with exploit details or private
financial data.

## Data Safety

The app does not encrypt local SQLite data. If you share a computer account or
disk with another person, use separate local profiles and operating-system disk
encryption.
