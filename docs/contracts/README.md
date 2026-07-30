# API Contract Snapshots

`python-openapi.json` is the declared contract of the FastAPI backend:
paths, methods, request and response schemas, and validation metadata.
CI fails when the committed snapshot no longer matches the running app,
so accidental API changes surface in review instead of in the frontend.

Refresh it intentionally after an API change:

```sh
make api-contract-python
```

Verify that the committed snapshot is current:

```sh
make api-contract-check
```

The snapshot only freezes what FastAPI declares. Routes need a
`response_model` for their response shape to be captured here.
