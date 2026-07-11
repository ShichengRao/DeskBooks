# API Contract Snapshots

`python-openapi.json` is the frozen declared contract of the Python FastAPI
backend during the Java migration. It preserves request and response schemas,
paths, methods, and validation metadata before the Python implementation is
retired.

Refresh it intentionally after a Python API change:

```sh
make api-contract-python
```

Verify that the committed snapshot is current:

```sh
make api-contract-check
```

The side-by-side parity harness complements this declaration by checking
runtime JSON, mutations, and error responses that OpenAPI cannot fully express.
